package com.jetbrains.ls.kotlinLsp

import com.jetbrains.lsp.implementation.TcpConnectionConfig

class KotlinLspServerRunConfig(
    val mode: KotlinLspServerMode
)

sealed interface KotlinLspServerMode {
    object Stdio : KotlinLspServerMode

    data class Socket(val config: TcpConnectionConfig) : KotlinLspServerMode
}


fun KotlinLspServerRunConfig.toArguments(): List<String> =
    when (mode) {
        is KotlinLspServerMode.Stdio -> listOf("--stdio")
        is KotlinLspServerMode.Socket -> when (val tcpConfig = mode.config) {
            is TcpConnectionConfig.Client -> listOf("--client", "--socket=${tcpConfig.port}")
            is TcpConnectionConfig.Server -> listOf("--socket=${tcpConfig.port}")
        }
    }