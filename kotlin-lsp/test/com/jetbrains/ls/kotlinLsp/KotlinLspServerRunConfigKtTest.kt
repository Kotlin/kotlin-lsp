// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.kotlinLsp

import com.jetbrains.lsp.implementation.TcpConnectionConfig
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test


class KotlinLspServerRunConfigKtTest {
    @Test
    fun `stdio`() {
        doConsistencyTest(KotlinLspServerRunConfig(KotlinLspServerMode.Stdio))
    }

    @Test
    fun `tcp client`() {
        doConsistencyTest(KotlinLspServerRunConfig(KotlinLspServerMode.Socket(TcpConnectionConfig.Client(port = 9999))))
    }

    @Test
    fun `tcp server`() {
        doConsistencyTest(
            KotlinLspServerRunConfig(
                KotlinLspServerMode.Socket(
                    TcpConnectionConfig.Server(
                        port = 9999,
                        isMulticlient = false
                    )
                )
            )
        )
    }

    @Test
    fun `tcp server multiclient`() {
        doConsistencyTest(
            KotlinLspServerRunConfig(
                KotlinLspServerMode.Socket(
                    TcpConnectionConfig.Server(
                        port = 9999,
                        isMulticlient = true
                    )
                )
            )
        )
    }

    private fun doConsistencyTest(config: KotlinLspServerRunConfig, ) {
        val arguments = config.toArguments()
        val parsed = parseArguments(arguments.toTypedArray()) as KotlinLspCommand.RunLsp
        Assertions.assertEquals(config, parsed.config)
    }
}