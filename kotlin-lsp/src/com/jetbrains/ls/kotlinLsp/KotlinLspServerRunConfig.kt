package com.jetbrains.ls.kotlinLsp

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.validate
import com.github.ajalt.clikt.parameters.types.int
import com.jetbrains.lsp.implementation.TcpConnectionConfig

data class KotlinLspServerRunConfig(
    val mode: KotlinLspServerMode
)

sealed interface KotlinLspServerMode {
    data object Stdio : KotlinLspServerMode

    data class Socket(val config: TcpConnectionConfig) : KotlinLspServerMode
}


fun parseArguments(args: Array<String>): KotlinLspCommand {
    val parser = Parser()
    try {
        parser.parse(args)
        return KotlinLspCommand.RunLsp(parser.createRunConfig())
    } catch (e: PrintHelpMessage) {
        return KotlinLspCommand.Help(e.command.getFormattedHelp())
    }
}

sealed class KotlinLspCommand {
    data class RunLsp(val config: KotlinLspServerRunConfig) : KotlinLspCommand()
    data class Help(val message: String) : KotlinLspCommand()
}

private class Parser : CliktCommand(name = "kotlin-lsp") {
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


    fun createRunConfig(): KotlinLspServerRunConfig {
        val mode = when {
            stdio -> KotlinLspServerMode.Stdio
            client -> KotlinLspServerMode.Socket(TcpConnectionConfig.Client(port = socket))
            else -> KotlinLspServerMode.Socket(TcpConnectionConfig.Server(port = socket, isMulticlient = multiclient))
        }
        return KotlinLspServerRunConfig(mode)
    }

    override fun run() {}
}

fun KotlinLspServerRunConfig.toArguments(): List<String> =
    when (mode) {
        is KotlinLspServerMode.Stdio -> listOf("--stdio")
        is KotlinLspServerMode.Socket -> when (val tcpConfig = mode.config) {
            is TcpConnectionConfig.Client -> listOf("--client", "--socket=${tcpConfig.port}")
            is TcpConnectionConfig.Server -> buildList {
                add("--socket=${tcpConfig.port}")
                if (tcpConfig.isMulticlient) add("--multiclient")
            }
        }
    }