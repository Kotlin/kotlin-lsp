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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.idea.base.plugin.artifacts.KotlinArtifacts
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinPluginLayoutMode
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinPluginLayoutModeProvider
import org.jetbrains.kotlin.idea.compiler.configuration.isRunningFromSources
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempDirectory
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    RunKotlinLspCommand().main(args)
    exitProcess(0)
}

private class RunKotlinLspCommand : CliktCommand(name = "kotlin-lsp") {
    val socket: Int by option().int().default(9999).help("A port which will be used for a Kotlin LSP connection. Default is 9999")
    val stdio: Boolean by option().flag()
        .help("Whether the Kotlin LSP server is used in stdio mode. If not set, server mode will be used with a port specified by `${::socket.name}`")
    val client: Boolean by option().flag()
        .help("Whether the Kotlin LSP server is used in client mode. If not set, server mode will be used with a port specified by `${::socket.name}`")
        .validate { if (it && stdio) fail("Can't use stdio mode with client mode") }

    val multiclient: Boolean by option().flag()
        .help("Whether the Kotlin LSP server is used in multiclient mode. If not set, server will be shut down after the first client disconnects.`")
        .validate {
            if (it && stdio) fail("Stdio mode doesn't support multiclient mode")
            if (it && client) fail("Client mode doesn't support multiclient mode")
        }


    private fun createRunConfig(): KotlinLspServerRunConfig {
        val mode = when {
            stdio -> KotlinLspServerMode.Stdio
            client -> KotlinLspServerMode.Socket(TcpConnectionConfig.Client(port = socket))
            else -> KotlinLspServerMode.Socket(TcpConnectionConfig.Server(port = socket, isMulticlient = multiclient))
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
                    stdioConnection(System.`in`, stdout) { connection ->
                        handleRequests(connection, config, mode)
                    }
                }

                is KotlinLspServerMode.Socket -> {
                    logSystemInfo()
                    tcpConnection(
                        mode.config,
                    ) { connection ->
                        handleRequests(connection, config, mode)
                    }
                }
            }
        }
    }
}

context(LSServerContext)
private suspend fun handleRequests(connection: LspConnection, config: LSConfiguration, mode: KotlinLspServerMode) {
    val shutdownOnExitSignal = when (mode) {
        is KotlinLspServerMode.Socket -> when (val tcpConfig = mode.config) {
            is TcpConnectionConfig.Client -> true
            is TcpConnectionConfig.Server -> !tcpConfig.isMulticlient
        }
        KotlinLspServerMode.Stdio -> true
    }
    val exitSignal = if (shutdownOnExitSignal) CompletableDeferred<Unit>() else null

    withBaseProtocolFraming(connection, exitSignal) { incoming, outgoing ->
        withServer {
            val handler = createLspHandlers(config, exitSignal)

            withLsp(
                incoming,
                outgoing,
                handler,
                createCoroutineContext = { lspClient ->
                    Client.contextElement(lspClient)
                },
            ) { lsp ->
                if (exitSignal != null) {
                    exitSignal.await()
                } else {
                    awaitCancellation()
                }
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
