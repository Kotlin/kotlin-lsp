// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.kotlinLsp.logging

import com.intellij.openapi.diagnostic.DefaultLogger.attachmentsToString
import com.intellij.openapi.diagnostic.IdeaLogRecordFormatter
import com.intellij.openapi.diagnostic.LogLevel
import com.intellij.openapi.diagnostic.Logger
import com.jetbrains.ls.kotlinLsp.connection.Client
import com.jetbrains.lsp.protocol.LogMessageNotificationType
import com.jetbrains.lsp.protocol.LogMessageParams
import com.jetbrains.lsp.protocol.LogTraceNotificationType
import com.jetbrains.lsp.protocol.LogTraceParams
import com.jetbrains.lsp.protocol.MessageType
import com.jetbrains.lsp.protocol.TraceValue
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * From Javadoc of [java.util.logging.Logger.getLogger]:
 *
 * > The LogManager may only retain a weak reference to the newly created Logger.
 * > It is important to understand that a previously created Logger with the given name may be garbage collected at any time if there is no strong reference to the Logger.
 *
 * The only purpose of this list is to keep strong references to the loggers created, so they aren't garbage collected at random times.
 * This seems ugly. If you know a better approach, please refactor and document accordingly.
 */
private val loggers = mutableListOf<java.util.logging.Logger>()

/**
 * Configures java.util.logging (JUL) loggers with hierarchical level inheritance.
 *
 * JUL provides automatic hierarchy: `com.foo.bar` inherits from `com.foo` when log level is not explicitly set on the former.
 */
fun initKotlinLspLogger(writeToStdout: Boolean, defaultLogLevel: LogLevel, logCategories: Map<String, LogLevel> = emptyMap()) {
    // Set root logger as default for all unspecified categories
    java.util.logging.Logger.getLogger("").level = defaultLogLevel.level

    for ((category, level) in logCategories) {
        java.util.logging.Logger.getLogger(category).level = level.level
        loggers.add(java.util.logging.Logger.getLogger(category))
        // Also set for "#category" pattern used by Logger.getInstance(Class)
        java.util.logging.Logger.getLogger("#$category").level = level.level
        loggers.add(java.util.logging.Logger.getLogger("#$category"))
    }

    Logger.setFactory { category -> LspLogger(category, writeToStdout) }
    com.intellij.serviceContainer.checkServiceFromWriteAccess = false
    com.intellij.codeInsight.multiverse.logMultiverseState = false
}

/**
 * Logger for Kotlin LSP works on three levels:
 * - Logs level:Sends errors/warnings/infos to be logged on the user side via `window/logMessage`
 * - Trace/Debug level: (usually, not enabled by the end user), needed only for debugging. It is sent by `$/setTrace`
 * - Common level: logs to the console, affected by [LogLevel]
 */
// TODO LSP-229 should store logs on disk
private class LspLogger(
    private val category: String,
    private val writeToStdOut: Boolean,
) : Logger() {
    private val logCreation: Long = System.currentTimeMillis()
    private val withDateTime: Boolean = true

    /**
     * Uses JUL's hierarchical level inheritance.
     *
     * When the log level is not set on this logger, JUL walks up the parent chain.
     */
    private fun isLoggable(level: LogLevel): Boolean {
        val logger: java.util.logging.Logger = java.util.logging.Logger.getLogger(category)
        return logger.isLoggable(level.level)
    }

    override fun setLevel(level: LogLevel) {
        java.util.logging.Logger.getLogger(category).level = level.level
    }

    override fun isDebugEnabled(): Boolean {
        return isLoggable(LogLevel.DEBUG) || currentTraceValue() != TraceValue.Off
    }

    override fun isTraceEnabled(): Boolean {
        return isLoggable(LogLevel.TRACE) || currentTraceValue() != TraceValue.Off
    }

    override fun trace(message: String?) {
        log(LogLevel.TRACE, message, null)
    }

    override fun trace(t: Throwable?) {
        log(LogLevel.TRACE, null, t)
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

    private fun log(level: LogLevel, message: String?, throwable: Throwable?, details: Array<out String?> = emptyArray()) {
        if (!isLoggable(level)) return
        val renderedMessage by lazy(LazyThreadSafetyMode.NONE) {
            buildString {
                val currentMillis = System.currentTimeMillis()
                if (withDateTime) {
                    val date = LocalDateTime.ofInstant(Instant.ofEpochMilli(currentMillis), ZoneId.systemDefault())
                    append(date.year)
                    append('-')
                    append(date.monthValue.toString().padStart(2, '0'))
                    append('-')
                    append(date.dayOfMonth.toString().padStart(2, '0'))
                    append(' ')
                    append(date.hour.toString().padStart(2, '0'))
                    append(':')
                    append(date.minute.toString().padStart(2, '0'))
                    append(':')
                    append(date.second.toString().padStart(2, '0'))
                    append(',')
                    append((currentMillis % 1000).toString().padStart(3, '0'))
                    append(' ')
                }
                append('[')
                append((currentMillis - logCreation).toString().padStart(7))
                append("] ")
                append(level.levelName.toString().padStart(6))
                append(" - ")
                append(IdeaLogRecordFormatter.smartAbbreviate(category))
                append(" - ")
                append(message)

                if (throwable != null) {
                    appendLine()
                    appendLine(throwable.message)
                    append(throwable.stackTraceToString())
                    try {
                        attachmentsToString(throwable).takeIf { it.isNotEmpty() }?.let { appendLine(); append(it) }
                    } catch (_: Throwable) {
                    }
                }

                if (details.isNotEmpty()) {
                    append(" ")
                    append(details.joinToString(", "))
                }
            }
        }

        // Possible log destination #1: stdout
        if (writeToStdOut) {
            println(renderedMessage)
        }

        // Possible log destination #2: the connected client
        Client.current?.let { client ->
            sendLogToClient(client, level, renderedMessage)
        }

        if (throwable != null && shouldRethrow(throwable)) {
            throw throwable
        }
    }

    /**
     * Sends an LSP notification containing [renderedMessage] to the connected [client].
     */
    private fun sendLogToClient(client: Client, level: LogLevel, renderedMessage: String) {
        fun logMessage(messageType: MessageType) {
            client.lspClient.notifyAsync(
                notificationType = LogMessageNotificationType,
                params = LogMessageParams(messageType, renderedMessage),
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
                    client.lspClient.notifyAsync(
                        notificationType = LogTraceNotificationType,
                        params = LogTraceParams(
                            message = renderedMessage,
                            verbose = null, // TODO: LSP-229 provide more details here?
                        ),
                    )
                }
            }

            LogLevel.ALL -> {}
        }
    }

    private fun currentTraceValue(): TraceValue = Client.current?.trace ?: TraceValue.Off
}
