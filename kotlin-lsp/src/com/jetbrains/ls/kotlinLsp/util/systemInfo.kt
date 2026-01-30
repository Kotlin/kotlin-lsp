// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.kotlinLsp.util

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.system.CpuArch
import com.intellij.util.system.OS
import com.jetbrains.lsp.implementation.LspClient
import com.jetbrains.lsp.protocol.LogMessageNotificationType
import com.jetbrains.lsp.protocol.LogMessageParams
import com.jetbrains.lsp.protocol.MessageType

internal fun getSystemInfo(): String = buildString {
    val runtime = Runtime.getRuntime()
    appendLine("System Info")
    appendLine("  os.name = ${OS.CURRENT.name}")
    appendLine("  os.version = ${OS.CURRENT.version()}")
    appendLine("  cpu.arch = ${if (CpuArch.isEmulated()) "${CpuArch.CURRENT}(emulated)" else "${CpuArch.CURRENT}"}")
    appendLine("  cpu.number: " + runtime.availableProcessors())
    appendLine("  java.version = ${SystemInfo.JAVA_RUNTIME_VERSION}")
    appendLine("  java.vm.vendor = ${SystemInfo.JAVA_VENDOR}")
    appendLine("  ram.xmx: ${runtime.maxMemory().bytesToMegabytes()}MB")
}

private fun Long.bytesToMegabytes(): Long = this / 1024L / 1024L

internal fun logSystemInfo() {
    LOG.info(getSystemInfo())
}

internal suspend fun LspClient.sendSystemInfoToClient() {
    notify(
        notificationType = LogMessageNotificationType,
        params = LogMessageParams(MessageType.Info, getSystemInfo()),
    )
}

private val LOG = Logger.getInstance("SystemInfo")
