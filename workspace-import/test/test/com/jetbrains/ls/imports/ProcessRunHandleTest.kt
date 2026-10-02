// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

import com.jetbrains.ls.api.run.RunTaskEvent
import com.jetbrains.ls.imports.utils.toRunHandle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import kotlin.time.Duration.Companion.seconds

@DisabledOnOs(OS.WINDOWS)
class ProcessRunHandleTest {
    @Test
    fun `output lines and the exit code arrive as events`() = runBlocking<Unit> {
        val events = ProcessBuilder("sh", "-c", "echo out; echo err 1>&2; exit 3").toRunHandle().events.toList()
        assertTrue(RunTaskEvent.StdOutput("out") in events, events.toString())
        assertTrue(RunTaskEvent.StdErrOutput("err") in events, events.toString())
        assertEquals(RunTaskEvent.Finished(3), events.last())
    }

    @Test
    fun `input reaches the stdin of the process`() = runBlocking<Unit> {
        val handle = ProcessBuilder("cat").toRunHandle()
        launch {
            handle.input.send("hello\n")
            handle.input.close()
        }
        val events = handle.events.toList()
        assertEquals(listOf(RunTaskEvent.StdOutput("hello"), RunTaskEvent.Finished(0)), events)
    }

    @Test
    fun `a command that does not start fails`() = runBlocking<Unit> {
        val events = ProcessBuilder("/no/such/binary").toRunHandle().events.toList()
        assertEquals(1, events.size)
        assertInstanceOf(RunTaskEvent.Failed::class.java, events.single())
    }

    @Test
    fun `the second command runs after the first exits with zero, and not after a failure`() = runBlocking<Unit> {
        val ok = listOf(ProcessBuilder("sh", "-c", "echo one"), ProcessBuilder("sh", "-c", "echo two"))
            .toRunHandle(between = { listOf(RunTaskEvent.StdOutput("between")) }).events.toList()
        assertEquals(
            listOf(RunTaskEvent.StdOutput("one"), RunTaskEvent.StdOutput("between"), RunTaskEvent.StdOutput("two"), RunTaskEvent.Finished(0)),
            ok,
        )
        val failed = listOf(ProcessBuilder("sh", "-c", "exit 2"), ProcessBuilder("sh", "-c", "echo two")).toRunHandle().events.toList()
        assertEquals(listOf(RunTaskEvent.Finished(2)), failed)
    }

    @Test
    fun `stopping the collection kills the process`() = runBlocking<Unit> {
        val first = withTimeout(10.seconds) {
            // `exec`: the kill reaches `sleep` itself, not a shell whose child keeps the output pipe open.
            ProcessBuilder("sh", "-c", "echo started; exec sleep 60").toRunHandle().events.first()
        }
        assertEquals(RunTaskEvent.StdOutput("started"), first)
    }
}
