// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.kotlinLsp.logging

import com.intellij.openapi.diagnostic.DefaultLogger.attachmentsToString
import com.intellij.openapi.diagnostic.IdeaLogRecordFormatter
import com.intellij.openapi.diagnostic.LogLevel
import com.intellij.openapi.diagnostic.Logger
import com.jetbrains.ls.kotlinLsp.connection.Client
import com.jetbrains.lsp.protocol.*


fun initKotlinLspLogger(writeToStdOut: Boolean) {
    Logger.setFactory(KotlinLspLoggerFactory(writeToStdOut))
    com.intellij.serviceContainer.checkServiceFromWriteAccess = false
}

private class KotlinLspLoggerFactory(private val writeToStdOut: Boolean) : Logger.Factory {
    override fun getLoggerInstance(category: String): Logger =
        LSPLogger(category, writeToStdOut)
}

/**
 * Logger for Kotlin LSP works on three levels:
 * - Logs level:Sends errors/warnings/infos to be logged on the user side via `window/logMessage`
 * - Trace/Debug level: (usually, not enabled by the end user), needed only for debugging. It is sent by `$/setTrace`
 * - Common level: logs to the console, affected by [LogLevel]
 */
private class LSPLogger(private val category: String, private val writeToStdOut: Boolean) : Logger() {
    /**
     * [level] does not affect `$/logTrace` notifications,
     */
    private var level: LogLevel = LogLevel.INFO

    override fun setLevel(level: LogLevel) {
        this.level = level
    }

    override fun isDebugEnabled(): Boolean {
        return shouldLog(LogLevel.DEBUG) || currentTraceValue() != TraceValue.Off
    }

    override fun isTraceEnabled(): Boolean {
        return shouldLog(LogLevel.TRACE) || currentTraceValue() != TraceValue.Off
    }

    override fun debug(message: String?, t: Throwable?) {
        log(LogLevel.DEBUG, message, t)
    }

    override fun info(message: String?, t: Throwable?) {
        log(LogLevel.INFO, message, t)
    }

    override fun warn(message: String?, t: Throwable?) {
        log(LogLevel.WARNING, message, t)
    }

    override fun error(message: String?, t: Throwable?, vararg details: String?) {
        log(LogLevel.ERROR, message, t, details)
    }

    private fun log(level: LogLevel, message: String?, t: Throwable?, details: Array<out String?> = emptyArray()) {
        val messageRendered by lazy(LazyThreadSafetyMode.NONE) {
            buildString {
                append("[${level.levelName}] ")
                append(IdeaLogRecordFormatter.smartAbbreviate(category))
                append(": ")
                append(message)

                if (t != null) {
                    appendLine()
                    appendLine(t.message)
                    append(t.stackTraceToString())
                    attachmentsToString(t).takeIf { it.isNotEmpty() }?.let { appendLine(); append(it) }
                }

                if (details.isNotEmpty()) {
                    append(" ")
                    append(details.joinToString(", "))
                }
            }
        }

        if (writeToStdOut && shouldLog(level)) {
            println(messageRendered)
        }

        Client.current?.let { client ->
            fun logMessage(messageType: MessageType) {
                client.lspClient.notify(
                    LogMessageNotification,
                    LogMessageParams(messageType, messageRendered),
                )
            }
            when (level) {
                LogLevel.OFF -> {}
                LogLevel.ERROR -> logMessage(MessageType.Error)
                LogLevel.WARNING -> logMessage(MessageType.Warning)
                LogLevel.INFO -> logMessage(MessageType.Info)
                LogLevel.DEBUG, LogLevel.TRACE -> {
                    // send debug trace to the client
                    val shouldNotifyForDebug = when (client.trace) {
                        TraceValue.Off, null -> false
                        TraceValue.Messages, TraceValue.Verbose -> true
                    }
                    if (shouldNotifyForDebug) {
                        client.lspClient.notify(
                            LogTraceNotificationType,
                            LogTraceParams(messageRendered, verbose = null/*todo provide more details here?*/)
                        )
                    }
                }

                LogLevel.ALL -> {}
            }
        }
        if (t != null && shouldRethrow(t)) {
            throw t
        }
    }

    private fun currentTraceValue(): TraceValue = Client.current?.trace ?: TraceValue.Off

    private fun shouldLog(level: LogLevel): Boolean = level <= this.level
}
