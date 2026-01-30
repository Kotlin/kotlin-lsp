// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.kotlinLsp.logging

import com.intellij.openapi.diagnostic.DefaultLogger.attachmentsToString
import com.intellij.openapi.diagnostic.IdeaLogRecordFormatter
import com.intellij.openapi.diagnostic.LogLevel
import com.intellij.openapi.diagnostic.Logger
import com.jetbrains.ls.kotlinLsp.connection.Client
import com.jetbrains.lsp.protocol.*
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap


fun initKotlinLspLogger(writeToStdOut: Boolean, defaultLogLevel: LogLevel) {
    Logger.setFactory(KotlinLspLoggerFactory(writeToStdOut, defaultLogLevel))
    com.intellij.serviceContainer.checkServiceFromWriteAccess = false
    com.intellij.codeInsight.multiverse.logMultiverseState = false
}

private class KotlinLspLoggerFactory(private val writeToStdOut: Boolean, defaultLogLevel: LogLevel) : Logger.Factory {
    private val levels = LoggingLevelsByCategory(defaultLogLevel)

    override fun getLoggerInstance(category: String): Logger = LspLogger(category, writeToStdOut, levels)
}

private class LoggingLevelsByCategory(private val defaultLogLevel: LogLevel) {
    // TODO PersistentHashMap may be faster as we have low write rate here and high read-rate
    private val levels = ConcurrentHashMap<String, LogLevel>()

    fun getLevel(category: String): LogLevel = levels[category] ?: defaultLogLevel

    fun setLevel(category: String, level: LogLevel) {
        levels[category] = level
    }
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
    private val levels: LoggingLevelsByCategory,
) : Logger() {
    private val logCreation: Long = System.currentTimeMillis()
    private val withDateTime: Boolean = true

    /**
     * [level] does not affect `$/logTrace` notifications.
     */
    private val level: LogLevel
        get() = levels.getLevel(category)

    override fun setLevel(level: LogLevel) {
        levels.setLevel(category, level)
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
        if (!shouldLog(level)) return
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

                if (t != null) {
                    appendLine()
                    appendLine(t.message)
                    append(t.stackTraceToString())
                    try {
                        attachmentsToString(t).takeIf { it.isNotEmpty() }?.let { appendLine(); append(it) }
                    } catch (_: Throwable) {
                    }
                }

                if (details.isNotEmpty()) {
                    append(" ")
                    append(details.joinToString(", "))
                }
            }
        }

        // Send log to stdout
        if (writeToStdOut && shouldLog(level)) {
            println(renderedMessage)
        }

        // Send log to the connected client, using
        Client.current?.let { client ->
            sendLogToClient(client, renderedMessage)
        }

        if (t != null && shouldRethrow(t)) {
            throw t
        }
    }

    /**
     * Sends an LSP notification containing [renderedMessage] to the connected [client].
     */
    private fun sendLogToClient(client: Client, renderedMessage: String) {
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

    private fun shouldLog(level: LogLevel): Boolean = level <= this.level
}
