// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.kotlinLsp

import com.intellij.openapi.application.ClassPathUtil.addKotlinStdlib
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.fileLogger
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.util.SystemProperties
import com.jetbrains.analyzer.filewatcher.FileWatcher
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.LSServerContext
import com.jetbrains.ls.api.core.withServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.impl.common.configuration.LSCommonConfiguration
import com.jetbrains.ls.api.features.impl.common.kotlin.configuration.LSKotlinLanguageConfiguration
import com.jetbrains.ls.api.features.impl.javaBase.configuration.LSJavaBaseLanguageConfiguration
import com.jetbrains.ls.api.features.language.LSConfigurationPiece
import com.jetbrains.ls.kotlinLsp.connection.Client
import com.jetbrains.ls.kotlinLsp.logging.initKotlinLspLogger
import com.jetbrains.ls.kotlinLsp.requests.core.fileUpdateRequests
import com.jetbrains.ls.kotlinLsp.requests.core.initializeRequest
import com.jetbrains.ls.kotlinLsp.requests.core.setTraceNotification
import com.jetbrains.ls.kotlinLsp.requests.core.shutdownRequest
import com.jetbrains.ls.kotlinLsp.requests.features
import com.jetbrains.ls.kotlinLsp.util.addKotlinStdlib
import com.jetbrains.ls.kotlinLsp.util.configuration.IsolatedDocumentsPlugin
import com.jetbrains.ls.kotlinLsp.util.logSystemInfo
import com.jetbrains.ls.snapshot.api.impl.core.createServerStarterAnalyzerImpl
import com.jetbrains.lsp.implementation.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.idea.base.plugin.artifacts.KotlinArtifacts
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinPluginLayoutMode
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinPluginLayoutModeProvider
import org.jetbrains.kotlin.idea.compiler.configuration.isRunningFromSources
import java.awt.Toolkit
import java.io.RandomAccessFile
import java.lang.invoke.MethodHandles
import java.net.URLDecoder
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import kotlin.io.path.*
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    when (val command = parseArguments(args)) {
        is KotlinLspCommand.Help -> {
            println(command.message)
            exitProcess(0)
        }

        is KotlinLspCommand.RunLsp -> {
            val runConfig = command.config
            run(runConfig)
            exitProcess(0)
        }
    }
}

// use after `initKotlinLspLogger` call
private val LOG by lazy { fileLogger() }

private fun run(runConfig: KotlinLspServerRunConfig) {
    val mode = runConfig.mode
    initKotlinLspLogger(writeToStdOut = mode != KotlinLspServerMode.Stdio)
    initIdeaPaths(runConfig.systemPath)
    initHeadlessToolkit()
    initExtraProperties()

    val config = createConfiguration(runConfig.isolatedDocumentsMode)

    val starter =
        createServerStarterAnalyzerImpl(config.plugins, isUnitTestMode = false, isolatedDocumentsMode = runConfig.isolatedDocumentsMode)

    @Suppress("RAW_RUN_BLOCKING")
    runBlocking(Dispatchers.Default) {
        starter.start {
            preloadKotlinStdlibWhenRunningFromSources()
            when (mode) {
                KotlinLspServerMode.Stdio -> {
                    val stdout = System.out
                    System.setOut(System.err)
                    stdioConnection(System.`in`, stdout) { connection ->
                        handleRequests(connection, runConfig, config)
                    }
                }

                is KotlinLspServerMode.Socket -> {
                    logSystemInfo()
                    tcpConnection(
                        mode.config,
                    ) { connection ->
                        handleRequests(connection, runConfig, config)
                    }
                }
            }
        }
    }
}

context(_: LSServerContext)
private suspend fun handleRequests(
    connection: LspConnection,
    runConfig: KotlinLspServerRunConfig,
    config: LSConfiguration,
) {
    val exitSignal = CompletableDeferred<Unit>()
    withBaseProtocolFraming(connection, exitSignal) { incoming, outgoing ->
        withServer {
            val handler = createLspHandlers(config, exitSignal)

            withLsp(
                incoming,
                outgoing,
                handler,
                createCoroutineContext = { lspClient ->
                    Client.contextElement(lspClient, runConfig)
                },
            ) { _ ->
                exitSignal.await()
            }
        }
    }
}

private fun initIdeaPaths(systemPath: Path?) {
    val fromSources = getIJPathIfRunningFromSources()
    if (fromSources != null) {
        systemProperty("idea.home.path", fromSources)
        systemProperty("idea.config.path", "$fromSources/config/idea", ifAbsent = true)
        systemProperty("idea.system.path", "$fromSources/system/idea", ifAbsent = true)
        FileWatcher.initLibraryFromSources(
            PathManager.getHomeDir() /
                    "fleet" / "native" / "target" / "download" / "filewatcher")
    }
    else {
        val path = systemPath
            ?.createDirectories()
            ?.takeIf {
                val lockFile = (it / ".app.lock").toFile()
                val channel = RandomAccessFile(lockFile, "rw").channel
                val isLockAcquired = channel.tryLock() != null
                if (!isLockAcquired) {
                    LOG.info("The specified workspace data path is already in use: $it")
                    channel.close()
                }
                isLockAcquired
            }
            ?: createTempDirectory("idea-system").also {
                @Suppress("SSBasedInspection")
                it.toFile().deleteOnExit()
            }

        systemProperty("idea.home.path", "$path")
        systemProperty("idea.config.path", "$path/config", ifAbsent = true)
        systemProperty("idea.system.path", "$path/system", ifAbsent = true)
        FileWatcher.initLibrary(getInstallationPath() / "native")
    }
    LOG.info("idea.config.path=${System.getProperty("idea.config.path")}")
    LOG.info("idea.system.path=${System.getProperty("idea.system.path")}")
}

private fun getInstallationPath(): Path {
    val path = MethodHandles.lookup().lookupClass().getProtectionDomain().codeSource.location.path
    val jarPath = Paths.get(FileUtilRt.toSystemDependentName(URLDecoder.decode(path, "UTF-8")).removePrefix("\\"))
    check(jarPath.extension == "jar") { "Path to jar is expected to end with .jar: $jarPath" }
    val libsDir = jarPath.parent
    check(libsDir.name == "lib") { "lib dir is expected to be named `lib`: $libsDir" }
    return libsDir.parent
}

private fun systemProperty(name: String, value: String, ifAbsent: Boolean = false) {
    if (!ifAbsent || System.getProperty(name) == null) {
        System.setProperty(name, value)
    }
}

private fun initExtraProperties() {
    KotlinPluginLayoutModeProvider.setForcedKotlinPluginLayoutMode(KotlinPluginLayoutMode.LSP)
    // TrigramIndex.isEnabled() -> false:
    SystemProperties.setProperty("find.use.indexing.searcher.extensions", "false")
}

private fun initHeadlessToolkit() {
    //System.setProperty("apple.awt.UIElement", "true")
    val field = ClassLoader.getPlatformClassLoader().loadClass("java.awt.GraphicsEnvironment")
        .declaredFields.find { it.name == "headless" }!!
    field.setAccessible(true)
    val prev = field.get(null) as Boolean?
    field.set(null, true)
    val current: Toolkit?
    try {
        current = Toolkit.getDefaultToolkit()
        if (!current::class.java.name.endsWith("HeadlessToolkit")) {
            throw AssertionError("Unexpected awt.Toolkit: ${current::class.java.name}")
        }
    } finally {
        field.set(null, prev)
    }
}

private fun getIJPathIfRunningFromSources(): String? {
    val serverClass = Class.forName("com.jetbrains.ls.kotlinLsp.KotlinLspServerKt")
    val jar = PathManager.getJarForClass(serverClass)?.absolutePathString() ?: return null
    val outSuffixes = arrayOf(
        "bazel-out/jvm-fastbuild/bin/language-server/community/kotlin-lsp/kotlin-lsp.jar",
        "/out/classes/production/language-server.kotlin-lsp")
    if (outSuffixes.any { jar.endsWith(FileUtilRt.toSystemDependentName(it)) }) {
        return Path("").absolutePathString()
    }
    return null
}


fun createConfiguration(isolatedDocumentsMode: Boolean = false): LSConfiguration {
    return LSConfiguration(
        buildList {
            add(LSCommonConfiguration)
            add(LSKotlinLanguageConfiguration)
            add(LSJavaBaseLanguageConfiguration)
            addAll(getAdditionalLanguageConfigurations())
            if (isolatedDocumentsMode) add(IsolatedDocumentsPlugin)
        }
    )
}

context(_: LSServer)
fun createLspHandlers(config: LSConfiguration, exitSignal: CompletableDeferred<Unit>?): LspHandlers {
    with(config) {
        return lspHandlers {
            initializeRequest()
            setTraceNotification()
            shutdownRequest(exitSignal)
            fileUpdateRequests()
            features()
        }
    }
}

/**
 * for some reason without this line [addKotlinStdlib] will fail with [ClassNotFoundException]
 */
private fun preloadKotlinStdlibWhenRunningFromSources() {
    if (isRunningFromSources) {
        KotlinArtifacts.kotlinStdlib
    }
}

interface LanguageConfigurationProvider {
    val languageConfiguration: LSConfigurationPiece
}

private fun getAdditionalLanguageConfigurations(): List<LSConfigurationPiece> {
    return ServiceLoader.load(LanguageConfigurationProvider::class.java).map {
        it.languageConfiguration
    }
}