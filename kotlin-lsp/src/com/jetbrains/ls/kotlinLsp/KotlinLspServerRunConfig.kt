package com.jetbrains.ls.kotlinLsp

import com.jetbrains.lsp.implementation.TcpConnectionConfig

class KotlinLspServerRunConfig(
    val mode: KotlinLspServerMode
)

sealed interface KotlinLspServerMode {
    object Stdio : KotlinLspServerMode

    data class Socket(val config: TcpConnectionConfig) : KotlinLspServerMode
}
