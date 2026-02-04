// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.kotlinLsp.util

import com.intellij.openapi.util.SystemInfo
import com.intellij.util.system.CpuArch
import com.intellij.util.system.OS
import com.jetbrains.lsp.implementation.LspClient
import com.jetbrains.lsp.protocol.LogMessageNotificationType
import com.jetbrains.lsp.protocol.LogMessageParams
import com.jetbrains.lsp.protocol.MessageType

internal fun getSystemInfo(): String {
    val runtime = Runtime.getRuntime()
    return """
        System Info
          os.name: ${OS.CURRENT.name}
          os.version: ${OS.CURRENT.version()}
          cpu.arch: ${if (CpuArch.isEmulated()) "${CpuArch.CURRENT}(emulated)" else "${CpuArch.CURRENT}"}
          cpu.number: ${runtime.availableProcessors()}
          java.home: ${System.getProperty("java.home")}
          java.version: = ${SystemInfo.JAVA_RUNTIME_VERSION}
          java.vm.vendor: ${SystemInfo.JAVA_VENDOR}
          ram.xmx: ${runtime.maxMemory().bytesToMegabytes()}MB
    """.trimIndent() + "\n"
}

private fun Long.bytesToMegabytes(): Long = this / 1024L / 1024L

internal suspend fun LspClient.sendSystemInfoToClient() {
    notify(
        notificationType = LogMessageNotificationType,
        params = LogMessageParams(MessageType.Info, getSystemInfo()),
    )
}
