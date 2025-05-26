package com.jetbrains.ls.kotlinLsp

class KotlinLspServerRunConfig(
    val mode: KotlinLspServerMode
)

sealed interface KotlinLspServerMode {
    object Stdio : KotlinLspServerMode

    sealed interface Socket : KotlinLspServerMode {
        val port: Int

        class Server(override val port: Int) : Socket
        class Client(override val port: Int) : Socket
    }
}