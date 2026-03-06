// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.kotlinLsp

import com.intellij.ide.plugins.ClassLoaderConfigurator
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.plugins.loadDescriptors
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.fileLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.platform.diagnostic.telemetry.TelemetryManager
import com.intellij.platform.diagnostic.telemetry.impl.TelemetryManagerImpl
import com.intellij.platform.ide.bootstrap.ZipFilePoolImpl
import com.intellij.util.PlatformUtils
import com.intellij.util.SystemProperties
import com.intellij.util.lang.PathClassLoader
import com.intellij.util.lang.UrlClassLoader
import com.intellij.util.lang.ZipFilePool
import com.jetbrains.analyzer.api.defaultPluginSet
import com.jetbrains.analyzer.bootstrap.pluginSet
import com.jetbrains.analyzer.filewatcher.FileWatcher
import com.jetbrains.analyzer.filewatcher.downloadFileWatcherBinaries
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.LSServerStarter
import com.jetbrains.ls.api.features.AnalyzerContainerType
import com.jetbrains.ls.api.features.ApplicationInitEntry
import com.jetbrains.ls.api.features.InvalidateHookEntry
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.LanguageServerExtension
import com.jetbrains.ls.api.features.ProjectInitEntry
import com.jetbrains.ls.api.features.RhizomeEntityTypeEntry
import com.jetbrains.ls.api.features.RhizomeLowMemoryWatcherHook
import com.jetbrains.ls.api.features.RhizomeWorkspaceInitEntry
import com.jetbrains.ls.api.features.analyzerContainerBuilderEntries
import com.jetbrains.ls.api.features.impl.common.configuration.DapCommonConfiguration
import com.jetbrains.ls.api.features.impl.common.configuration.LSCommonConfiguration
import com.jetbrains.ls.kotlinLsp.connection.Client
import com.jetbrains.ls.kotlinLsp.logging.initKotlinLspLogger
import com.jetbrains.ls.kotlinLsp.requests.core.fileUpdateRequests
import com.jetbrains.ls.kotlinLsp.requests.core.initializeRequest
import com.jetbrains.ls.kotlinLsp.requests.core.setTraceNotification
import com.jetbrains.ls.kotlinLsp.requests.core.shutdownRequest
import com.jetbrains.ls.kotlinLsp.requests.features
import com.jetbrains.ls.kotlinLsp.util.getSystemInfo
import com.jetbrains.ls.snapshot.api.impl.core.ProjectEnvConfig
import com.jetbrains.ls.snapshot.api.impl.core.withLSServerStarter
import com.jetbrains.lsp.implementation.LspConnection
import com.jetbrains.lsp.implementation.LspHandlers
import com.jetbrains.lsp.implementation.lspHandlers
import com.jetbrains.lsp.implementation.withBaseProtocolFraming
import com.jetbrains.lsp.implementation.withLsp
import com.jetbrains.rhizomedb.ChangeScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.io.RandomAccessFile
import java.nio.file.Path
import java.util.ServiceLoader
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    SystemProperties.setProperty("idea.platform.prefix", "LanguageServer")
    when (val command = parseArguments(args)) {
        is KotlinLspCommand.Help -> {
            println(command.message)
            exitProcess(0)
        }

        is KotlinLspCommand.RunLsp -> {
            val runConfig = command.config
            try {
                run(runConfig)
                exitProcess(0)
            } catch (e: Throwable) {
                e.printStackTrace(System.err)
                exitProcess(1)
            }
        }
    }
}

// use after `initKotlinLspLogger` call
private val LOG by lazy { fileLogger() }

fun LSConfiguration.lowMemoryHooks(): List<context(ChangeScope) () -> Unit> =
    entries.mapNotNull {
        (it as? RhizomeLowMemoryWatcherHook)?.let { hook ->
            val hookFun: context(ChangeScope) () -> Unit = {
                hook.onLowMemory()
            }
            hookFun
        }
    }

private fun run(runConfig: KotlinLspServerRunConfig) {
    val stdout = System.out
    val mode = runConfig.mode

    if (mode == KotlinLspServerMode.Stdio) {
        System.setOut(System.err)
    }

    println(buildString {
        "IJ_JAVA_OPTIONS".let { k ->
            appendLine("$k=${System.getenv(k)}")
        }
        appendLine("idea.home.path=${PathManager.getHomeDir()}")
        appendLine("idea.config.path=${PathManager.getConfigDir()}")
        appendLine("idea.system.path=${PathManager.getSystemDir()}")
        appendLine()
    })

    initKotlinLspLogger(
        writeToStdout = mode != KotlinLspServerMode.Stdio,
        defaultLogLevel = runConfig.defaultLogLevel,
        logCategories = runConfig.logCategories,
    )
    initIdeaPaths(runConfig.systemPath)
    initExtraProperties()

    runBlocking(CoroutineName("root") + Dispatchers.Default) root@{
        TelemetryManager.setTelemetryManager(
            provider = async(CoroutineName("opentelemetry configuration")) {
                runCatching {
                    TelemetryManagerImpl(
                        coroutineScope = this@root,
                        isUnitTestMode = false,
                        loadExporterExtensions = false,
                    )
                }.getOrElse { e ->
                    LOG.warn("Can't initialize OpenTelemetry: will use NOOP implementation", e)
                    null
                }
            },
        )

        val zipPoolDeferred = async {
            val result = ZipFilePoolImpl()
            ZipFilePool.PATH_CLASSLOADER_POOL = result
            result
        }
        val languageServerExtensions = loadDescriptors(zipPoolDeferred, null).let { (_, pluginLoadingResult) ->
            val coreLoader = PluginManagerCore::class.java.classLoader
            val plugins = pluginLoadingResult.pluginLists.flatMap { it.plugins }
            val configurator = ClassLoaderConfigurator(pluginSet(emptyList()), coreLoader)

            // Keep using combined classloader for now
            plugins.asSequence().flatMap { descriptor ->
                sequenceOf(descriptor) + descriptor.contentModules.asSequence()
            }
                .mapNotNull { descriptor ->
                    configurator.configureModule(descriptor)
                    descriptor.pluginClassLoader
                }
                .filter { it != coreLoader }
                .map { (it as? PathClassLoader)?.classPath ?: (it as UrlClassLoader).classPath }
                .flatMap { cp -> cp.files }
                .toList()
                .let { files ->
                    (coreLoader as PathClassLoader).classPath.addFiles(files)
                }

            ServiceLoader.load(LanguageServerExtension::class.java, coreLoader)
                .asSequence().distinctBy { it.javaClass }.toList()
        }
        val config = createConfiguration(languageServerExtensions)

        withLSServerStarter(
            lowMemoryHooks = config.lowMemoryHooks(),
            analysisConfig = config.configFor(AnalyzerContainerType.ANALYSIS),
            writableConfig = config.configFor(AnalyzerContainerType.WRITE),
            debuggerConfig = config.configForDebugger(),
            isUnitTestMode = false,
        ) {
            val body: suspend CoroutineScope.(LspConnection) -> Unit = { connection ->
                handleRequests(connection, runConfig, config)
            }
            when (mode) {
                KotlinLspServerMode.Stdio -> {
                    stdioConnection(System.`in`, stdout, body)
                }

                is KotlinLspServerMode.Socket -> {
                    LOG.info(getSystemInfo())

                    when (mode.config) {
                        is TcpConnectionConfig.Client -> tcpClient(mode.config, body)
                        is TcpConnectionConfig.Server -> tcpServer(mode.config, body)
                    }
                }
            }
        }
    }
}

context(serverStarter: LSServerStarter)
private suspend fun handleRequests(
    connection: LspConnection,
    runConfig: KotlinLspServerRunConfig,
    config: LSConfiguration,
) {
    val exitSignal = CompletableDeferred<Unit>()
    withBaseProtocolFraming(connection, exitSignal) { incoming, outgoing ->
        serverStarter.withServer {
            val handlers = createLspHandlers(config, exitSignal)

            withLsp(
                incoming = incoming,
                outgoing = outgoing,
                handlers = handlers,
                createCoroutineContext = { lspClient ->
                    Client.contextElement(lspClient, runConfig)
                },
                body = { _ ->
                    exitSignal.await()
                },
            )
        }
    }
}

private fun initIdeaPaths(systemPath: Path?) {
    val serverClass = Class.forName("com.jetbrains.ls.kotlinLsp.KotlinLspServerKt")
    val jarPath = PathManager.getJarForClass(serverClass)?.toAbsolutePath()
    val jarPathStr = jarPath?.let { FileUtilRt.toSystemIndependentName(jarPath.toString()) } ?: ""

    when {
        jarPathStr.contains("/out/dev-run/") -> {
            val nativeFileWatcherPath = downloadFileWatcherBinaries(Path.of(PathManager.getCommunityHomePath()))
            FileWatcher.initLibrary(nativeFileWatcherPath)
        }

        jarPathStr.contains("/bazel-out/jvm-fastbuild/") ||
                jarPathStr.contains("/out/classes/production/") -> {
            val homeDir = PathManager.getHomeDir()
            val code = PlatformUtils.getPlatformPrefix().lowercase()
                .removeSuffix("lsp") + "-lsp"
            systemProperty("idea.config.path", "${homeDir / "config" / code}", ifAbsent = true)
            systemProperty("idea.system.path", "${homeDir / "system" / code}", ifAbsent = true)
            val nativeFileWatcherPath = downloadFileWatcherBinaries(homeDir / "community")
            FileWatcher.initLibrary(nativeFileWatcherPath)
        }

        else -> {
            val path = systemPath
                ?.createDirectories()
                ?.takeIf {
                    @Suppress("IO_FILE_USAGE")
                    val channel = RandomAccessFile((it / ".app.lock").toFile(), "rw").channel
                    val isLockAcquired = channel.tryLock() != null
                    if (!isLockAcquired) {
                        LOG.info("The specified workspace data path is already in use: $it")
                        channel.close()
                    }
                    isLockAcquired
                }
                ?: createTempDirectory("idea-system").also {
                    @OptIn(ExperimentalPathApi::class)
                    Runtime.getRuntime().addShutdownHook(Thread { it.deleteRecursively() })
                }

            jarPath?.parent?.parent?.let { home ->
                systemProperty("idea.home.path", "$home")
            }
            systemProperty("idea.config.path", "${path / "config"}", ifAbsent = true)
            systemProperty("idea.system.path", "${path / "system"}", ifAbsent = true)
            FileWatcher.initLibrary(PathManager.getLibDir() / "filewatcher")
        }
    }
    LOG.info("idea.config.path=${System.getProperty("idea.config.path")}")
    LOG.info("idea.system.path=${System.getProperty("idea.system.path")}")
}

private fun systemProperty(name: String, value: String, ifAbsent: Boolean = false) {
    if (!ifAbsent || System.getProperty(name) == null) {
        System.setProperty(name, value)
    }
}

private fun initExtraProperties() {
    SystemProperties.setProperty("kotlin.plugin.layout", "LSP")
    // TrigramIndex.isEnabled() -> false:
    SystemProperties.setProperty("find.use.indexing.searcher.extensions", "false")
}

fun createConfiguration(
    extensions: List<LanguageServerExtension> =
        ServiceLoader.load(LanguageServerExtension::class.java).toList()
): LSConfiguration {
    LOG.info(
        if (extensions.isEmpty()) "Server extensions not found"
        else "Server extensions loaded:\n  ${extensions.joinToString("\n  ") { it.javaClass.run { "$simpleName ($name)" } }}"
    )
    return LSConfiguration(
        configurations = listOf(
            LSCommonConfiguration,
            DapCommonConfiguration,
            *(extensions.map {
                it.configuration
            }.toTypedArray()),
        ),
    )
}

fun LSConfiguration.configFor(type: AnalyzerContainerType): ProjectEnvConfig =
    ProjectEnvConfig(
        pluginSet = defaultPluginSet(plugins),
        projectInits = analyzerContainerBuilderEntries<ProjectInitEntry, Project> { builder, project ->
            builder.initProject(project, type)
        },
        applicationInits = analyzerContainerBuilderEntries<ApplicationInitEntry, Application> { builder, application ->
            builder.initApplication(application, type)
        },
        entityTypes = entries.mapNotNull { (it as? RhizomeEntityTypeEntry)?.entityType() },
        workspaceInits = entries.mapNotNull {
            (it as? RhizomeWorkspaceInitEntry)?.let { hook -> { context -> hook.workspaceInit(context) } }
        },
        invalidationHooks = entries.mapNotNull {
            (it as? InvalidateHookEntry)?.let { hook -> { urls -> hook.invalidation(urls) } }
        },
    )

fun LSConfiguration.configForDebugger(): ProjectEnvConfig =
    configFor(AnalyzerContainerType.ANALYSIS).copy(pluginSet = defaultPluginSet(plugins + dapPlugins))

context(server: LSServer)
fun createLspHandlers(config: LSConfiguration, exitSignal: CompletableDeferred<Unit>?): LspHandlers {
    with(config) {
        return lspHandlers {
            initializeRequest(workspaceImporters)
            setTraceNotification()
            shutdownRequest(exitSignal)
            fileUpdateRequests()
            features()
        }
    }
}
