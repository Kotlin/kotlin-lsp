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


fun initKotlinLspLogger(writeToStdOut: Boolean) {
    Logger.setFactory(KotlinLspLoggerFactory(writeToStdOut))
    com.intellij.serviceContainer.checkServiceFromWriteAccess = false
    com.intellij.codeInsight.multiverse.logMultiverseState = false
}

private class KotlinLspLoggerFactory(private val writeToStdOut: Boolean) : Logger.Factory {
    private val levels = LoggingLevelsByCategory()

    override fun getLoggerInstance(category: String): Logger = LSPLogger(category, writeToStdOut, levels)
}

private class LoggingLevelsByCategory {
    // TODO PersistentHashMap may be faster as we have low write rate here and high read-rate
    private val levels = ConcurrentHashMap<String, LogLevel>()

    fun getLevel(category: String): LogLevel = levels[category] ?: DEFAULT

    fun setLevel(category: String, level: LogLevel) {
        levels[category] = level
    }

    companion object {
        val DEFAULT = LogLevel.INFO
    }
}

/**
 * Logger for Kotlin LSP works on three levels:
 * - Logs level:Sends errors/warnings/infos to be logged on the user side via `window/logMessage`
 * - Trace/Debug level: (usually, not enabled by the end user), needed only for debugging. It is sent by `$/setTrace`
 * - Common level: logs to the console, affected by [LogLevel]
 */
// TODO LSP-229 should store logs on disk
private class LSPLogger(
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
        val messageRendered by lazy(LazyThreadSafetyMode.NONE) {
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

        if (writeToStdOut && shouldLog(level)) {
            println(messageRendered)
        }

        Client.current?.let { client ->
            fun logMessage(messageType: MessageType) {
                client.lspClient.notifyAsync(
                    notificationType = LogMessageNotification,
                    params = LogMessageParams(messageType, messageRendered),
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
                            params = LogTraceParams(messageRendered, verbose = null/*TODO LSP-229 provide more details here?*/)
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
