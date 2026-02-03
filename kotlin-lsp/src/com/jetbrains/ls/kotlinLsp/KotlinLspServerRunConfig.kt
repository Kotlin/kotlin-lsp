// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.kotlinLsp

import com.github.ajalt.clikt.core.BadParameterValue
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.validate
import com.github.ajalt.clikt.parameters.types.path
import com.intellij.openapi.diagnostic.LogLevel
import java.nio.file.Path

data class KotlinLspServerRunConfig(
    val mode: KotlinLspServerMode,
    val systemPath: Path?,
    val defaultLogLevel: LogLevel = LogLevel.INFO,
    val logCategories: Map<String, LogLevel> = emptyMap(),
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
    // the name "socket" is hardcoded in VSCode
    val socket: SocketConfig by option()
        .convert { it.toSocketConfig() }
        .default(SocketConfig("127.0.0.1", 9999))
        .help("A socket which will be used for a Kotlin LSP connection. Default is 127.0.0.1:9999")
    val stdio: Boolean by option().flag()
        .help("Whether the Kotlin LSP server is used in stdio mode. If not set, server mode will be used with a port specified by `${::socket.name}`")
    val client: Boolean by option().flag()
        .help("Whether the Kotlin LSP server is used in client mode. If not set, server mode will be used with a port specified by `${::socket.name}`")
        .validate { if (it && stdio) fail("Can't use stdio mode with client mode") }
    val systemPath: Path? by option().path()
        .help("Path for Kotlin LSP caches and indexes")
    val logLevel: LogLevel by option(
        help = "Global log level. Supported values: TRACE, DEBUG, INFO, WARNING, ERROR, OFF, ALL.",
        envvar = "KOTLIN_LSP_LOG_LEVEL",
    ).convert { parseLogLevel(it) }
        .default(LogLevel.INFO)
    val logCategory: List<List<Pair<String, LogLevel>>> by option(
        help = "Per-category log level (category:LEVEL). Can be repeated. Unspecified categories use the global log level.",
        envvar = "KOTLIN_LSP_LOG_CATEGORIES",
    ).convert { value -> value.split(",").map { parseCategoryLevel(it.trim()) } }.multiple(default = emptyList())
    val multiClient: Boolean by option().flag()
        .help("Whether the Kotlin LSP server is used in multiclient mode. If not set, server will be shut down after the first client disconnects.")
        .validate {
            if (it && stdio) fail("Stdio mode doesn't support multiclient mode")
            if (it && client) fail("Client mode doesn't support multiclient mode")
        }

    // TODO also parse --version flag, see LSP-225

    fun createRunConfig(): KotlinLspServerRunConfig {
        val mode = when {
            stdio -> KotlinLspServerMode.Stdio
            client -> KotlinLspServerMode.Socket(
                TcpConnectionConfig.Client(
                    host = socket.host,
                    port = socket.port,
                ),
            )

            else -> KotlinLspServerMode.Socket(
                TcpConnectionConfig.Server(
                    host = socket.host,
                    port = socket.port,
                    isMultiClient = multiClient,
                ),
            )
        }
        return KotlinLspServerRunConfig(mode, systemPath, logLevel, logCategory.flatten().toMap())
    }

    override fun run() {}
}

private data class SocketConfig(val host: String, val port: Int)

private fun String.toSocketConfig(): SocketConfig {
    val idx = indexOf(':')
    val host = if (idx == -1) "127.0.0.1" else substring(0, idx)
    val port = (if (idx == -1) this else substring(idx + 1)).toIntOrNull()
        ?: throw BadParameterValue("'$this' is not a valid socket. Expected [<host>:]<port>")
    return SocketConfig(host, port)
}

fun KotlinLspServerRunConfig.toArguments(): List<String> = buildList {
    when (mode) {
        is KotlinLspServerMode.Stdio -> add("--stdio")
        is KotlinLspServerMode.Socket -> when (val tcpConfig = mode.config) {
            is TcpConnectionConfig.Client -> {
                add("--client")
                add("--socket=${tcpConfig.host}:${tcpConfig.port}")
            }

            is TcpConnectionConfig.Server -> {
                add("--socket=${tcpConfig.host}:${tcpConfig.port}")
                if (tcpConfig.isMultiClient) add("--multi-client")
            }
        }
    }
    if (systemPath != null) add("--system-path=$systemPath")
    if (defaultLogLevel != LogLevel.INFO) add("--log-level=${defaultLogLevel.name}")
    for ((category, level) in logCategories) {
        add("--log-category=$category:${level.name}")
    }
}

private fun parseLogLevel(value: String): LogLevel {
    return LogLevel.entries.firstOrNull { logLevel -> logLevel.name == value } ?: throw BadParameterValue(
        text = "'$value' is not a valid log level. Supported values: " + LogLevel.entries.joinToString { it.name } + " (case-sensitive)",
    )
}

private fun parseCategoryLevel(value: String): Pair<String, LogLevel> {
    val colonIdx = value.indexOf(':')
    if (colonIdx <= 0 || colonIdx == value.lastIndex) {
        throw BadParameterValue("'$value' is not a valid category:level. Expected format: <category>:<LEVEL>, e.g. com.example:DEBUG")
    }
    val category = value.substring(0, colonIdx).trim()
    if (category.isEmpty()) {
        throw BadParameterValue("Category must be non-empty in '$value'")
    }
    val levelStr = value.substring(colonIdx + 1).trim()
    val level = parseLogLevel(levelStr)
    return category to level
}
