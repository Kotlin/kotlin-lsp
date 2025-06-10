// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.kotlinLsp

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.int
import com.intellij.openapi.application.PathManager
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.LSServerContext
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.impl.common.configuration.LSCommonConfiguration
import com.jetbrains.ls.api.features.impl.common.kotlin.configuration.LSKotlinLanguageConfiguration
import com.jetbrains.ls.api.features.language.LSLanguageConfiguration
import com.jetbrains.ls.kotlinLsp.connection.Client
import com.jetbrains.ls.kotlinLsp.logging.initKotlinLspLogger
import com.jetbrains.ls.kotlinLsp.requests.core.fileUpdateRequests
import com.jetbrains.ls.kotlinLsp.requests.core.initializeRequest
import com.jetbrains.ls.kotlinLsp.requests.core.setTraceNotification
import com.jetbrains.ls.kotlinLsp.requests.core.shutdownRequest
import com.jetbrains.ls.kotlinLsp.requests.features
import com.jetbrains.ls.kotlinLsp.util.addKotlinStdlib
import com.jetbrains.ls.kotlinLsp.util.logSystemInfo
import com.jetbrains.ls.snapshot.api.impl.core.createServerStarterAnalyzerImpl
import com.jetbrains.lsp.implementation.*
import kotlinx.coroutines.*
import org.jetbrains.kotlin.idea.base.plugin.artifacts.KotlinArtifacts
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinPluginLayoutMode
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinPluginLayoutModeProvider
import org.jetbrains.kotlin.idea.compiler.configuration.isRunningFromSources
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempDirectory

fun main(args: Array<String>) {
    RunKotlinLspCommand().main(args)
}

private class RunKotlinLspCommand : CliktCommand(name = "kotlin-lsp") {
    val socket: Int by option().int().default(9999).help("A port which will be used for a Kotlin LSP connection. Default is 9999")
    val stdio: Boolean by option().flag()
        .help("Whether the Kotlin LSP server is used in stdio mode. If not set, server mode will be used with a port specified by `${::socket.name}`")
    val client: Boolean by option().flag()
        .help("Whether the Kotlin LSP server is used in client mode. If not set, server mode will be used with a port specified by `${::socket.name}`")
        .validate { if (it && stdio) fail("Can't use stdio mode with client mode") }

    private fun createRunConfig(): KotlinLspServerRunConfig {
        val mode = when {
            stdio -> KotlinLspServerMode.Stdio
            client -> KotlinLspServerMode.Socket.Client(socket)
            else -> KotlinLspServerMode.Socket.Server(socket)
        }
        return KotlinLspServerRunConfig(mode)
    }

    override fun run() {
        val runConfig = createRunConfig()
        run(runConfig)
    }
}

private fun run(runConfig: KotlinLspServerRunConfig) {
    val mode = runConfig.mode
    initKotlinLspLogger(writeToStdOut = mode != KotlinLspServerMode.Stdio)
    initIdeaPaths()
    setLspKotlinPluginModeIfRunningFromProductionLsp()
    val config = createConfiguration()

    val starter = createServerStarterAnalyzerImpl(config.plugins, isUnitTestMode = false)
    @Suppress("RAW_RUN_BLOCKING")
    runBlocking(Dispatchers.Default) {
        starter.start {
            preloadKotlinStdlibWhenRunningFromSources()
            when (mode) {
                KotlinLspServerMode.Stdio -> {
                    val stdout = System.out
                    System.setOut(System.err)
                    handleRequests(System.`in`, stdout, config, mode)
                }

                is KotlinLspServerMode.Socket -> {
                    logSystemInfo()
                    tcpConnection(
                        clientMode = mode is KotlinLspServerMode.Socket.Client,
                        port = mode.port,
                    ) { input, output ->
                        handleRequests(input, output, config, mode)
                    }
                }
            }
        }
    }
}

context(LSServerContext)
private suspend fun handleRequests(input: InputStream, output: OutputStream, config: LSConfiguration, mode: KotlinLspServerMode) {
    withBaseProtocolFraming(input, output) { incoming, outgoing ->
        withServer {
            val exitSignal = CompletableDeferred<Unit>()
            val handler = createLspHandlers(config, exitSignal, clientMode = mode is KotlinLspServerMode.Socket.Client)

            withLsp(
                incoming,
                outgoing,
                handler,
                createCoroutineContext = { lspClient ->
                    Client.contextElement(lspClient)
                },
            ) { lsp ->
                exitSignal.await()
            }
        }
    }
}

private fun initIdeaPaths() {
    val fromSources = getIJPathIfRunningFromSources()
    if (fromSources != null) {
        System.setProperty("idea.home.path", fromSources)
        System.setProperty("idea.config.path", "$fromSources/config/idea")
        System.setProperty("idea.system.path", "$fromSources/system/idea")
    }
    else {
        val tmp = createTempDirectory("idea-home").absolutePathString()
        System.setProperty("idea.home.path", tmp)
    }
}

private fun setLspKotlinPluginModeIfRunningFromProductionLsp() {
    if (isRunningFromProductionLsp()) {
        KotlinPluginLayoutModeProvider.setForcedKotlinPluginLayoutMode(KotlinPluginLayoutMode.LSP)
    }
}

private fun isRunningFromSources(): Boolean {
    return getIJPathIfRunningFromSources() != null
}

private fun isRunningFromProductionLsp(): Boolean {
    return !isRunningFromSources()
}


private fun getIJPathIfRunningFromSources(): String? {
    val serverClass = Class.forName("com.jetbrains.ls.kotlinLsp.KotlinLspServerKt")
    val jar = PathManager.getJarForClass(serverClass)?.absolutePathString() ?: return null
    val expectedOutDir = "/out/classes/production/language-server.kotlin-lsp"
    if (!jar.endsWith(expectedOutDir)) return null
    return jar.removeSuffix(expectedOutDir)
}


fun createConfiguration(
    additionalConfigurations: List<LSLanguageConfiguration> = emptyList(),
): LSConfiguration {
    return LSConfiguration(
        buildList {
            add(LSCommonConfiguration)
            add(LSKotlinLanguageConfiguration)
            addAll(additionalConfigurations)
        }
    )
}

context(LSServer)
fun createLspHandlers(config: LSConfiguration, exitSignal: CompletableDeferred<Unit>, clientMode: Boolean = false): LspHandlers {
    with(config) {
        return lspHandlers {
            initializeRequest()
            setTraceNotification()
            shutdownRequest(clientMode, exitSignal)
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
