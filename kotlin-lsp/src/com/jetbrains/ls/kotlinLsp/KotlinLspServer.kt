package com.jetbrains.ls.kotlinLsp

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.*
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
    val stdio: Boolean by option().flag().help("Whether the Kotlin LSP server is used in stdio mode. If not set, server mode will be used with a port specified by `${::socket.name}`")
    val client: Boolean by option().flag()
        .help("Whether the Kotlin LSP server is used in client mode. If not set, server mode will be used with a port specified by `${::socket.name}`")
        .validate { if (it && stdio) fail("Can't use stdio mode with client mode") }

    override fun run() {
        initKotlinLspLogger(writeToStdOut = !stdio)
        initIdeaHomePath()
        setLspKotlinPluginModeIfRunningFromProductionLsp()

        val config = createConfiguration()

        val starter = createServerStarterAnalyzerImpl(config.plugins, isUnitTestMode = false)
        @Suppress("RAW_RUN_BLOCKING")
        runBlocking(Dispatchers.Default) {
            starter.start {
                preloadKotlinStdlibWhenRunningFromSources()
                if (stdio) {
                    val stdout = System.out
                    System.setOut(System.err)
                    handleRequests(System.`in`, stdout, config, true)
                } else {
                    logSystemInfo()
                    tcpConnection(socket, client) { input, output ->
                        handleRequests(input, output, config, client)
                    }
                }
            }
        }
    }
}

context(LSServerContext)
private suspend fun handleRequests(input: InputStream, output: OutputStream, config: LSConfiguration, client: Boolean) {
    withBaseProtocolFraming(input, output) { incoming, outgoing ->
        withServer {
            val exitSignal = CompletableDeferred<Unit>()
            val handler = createLspHandlers(config, exitSignal, client)

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

private fun initIdeaHomePath() {
    val ideaHomePath =
        getIJPathIfRunningFromSources()
            ?: createTempDirectory("idea-home").absolutePathString()
    System.setProperty("idea.home.path", ideaHomePath)
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

private suspend fun tcpConnection(port: Int, isClientMode: Boolean, body: suspend CoroutineScope.(InputStream, OutputStream) -> Unit) =
    if (isClientMode) {
        tcpClient(port, body)
    } else {
        tcpServer(port, body)
    }

/**
 * VSC opens a **server** socket for LSP to connect to it.
 */
private suspend fun tcpClient(port: Int, body: suspend CoroutineScope.(InputStream, OutputStream) -> Unit) {
    val socket = runInterruptible(Dispatchers.IO) {
        Socket("localhost", port)
    }
    socket.use {
        coroutineScope {
            body(socket.getInputStream(), socket.getOutputStream())
        }
    }
}
