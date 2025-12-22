// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.kotlinLsp

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.nio.file.Paths


class KotlinLspServerRunConfigKtTest {
    @Test
    fun `stdio`() {
        doConsistencyTest(KotlinLspServerRunConfig(KotlinLspServerMode.Stdio, systemPath = null))
    }

    @Test
    fun `tcp client`() {
        doConsistencyTest(
            KotlinLspServerRunConfig(
                KotlinLspServerMode.Socket(
                    TcpConnectionConfig.Client(host = "127.0.0.1", port = 9999)
                ),
                systemPath = Paths.get("/path/to/system"),
            )
        )
    }

    @Test
    fun `tcp server`() {
        doConsistencyTest(
            KotlinLspServerRunConfig(
                KotlinLspServerMode.Socket(
                    TcpConnectionConfig.Server(
                        host = "127.0.0.1",
                        port = 9999,
                        isMultiClient = false
                    )
                ),
                systemPath = Paths.get("/path/to/system"),
            )
        )
    }

    @Test
    fun `tcp server multiclient`() {
        doConsistencyTest(
            KotlinLspServerRunConfig(
                KotlinLspServerMode.Socket(
                    TcpConnectionConfig.Server(
                        host = "127.0.0.1",
                        port = 9999,
                        isMultiClient = true
                    )
                ),
                systemPath = null,
            )
        )
    }

    private fun doConsistencyTest(config: KotlinLspServerRunConfig) {
        val arguments = config.toArguments()
        val parsed = parseArguments(arguments.toTypedArray()) as KotlinLspCommand.RunLsp
        Assertions.assertEquals(config, parsed.config)
    }
}