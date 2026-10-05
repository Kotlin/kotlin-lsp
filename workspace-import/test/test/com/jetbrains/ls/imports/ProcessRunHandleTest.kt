// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

import com.jetbrains.ls.api.run.RunHandle
import com.jetbrains.ls.api.run.RunTaskEvent
import com.jetbrains.ls.api.run.toRunHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@DisabledOnOs(OS.WINDOWS)
class ProcessRunHandleTest {
    @Test
    fun `output lines and the exit code arrive as events`() = runBlocking<Unit> {
        val events = ProcessBuilder("sh", "-c", "echo out; echo err 1>&2; exit 3").toRunHandle().use { it.events.toList() }
        assertTrue(RunTaskEvent.StdOutput("out") in events, events.toString())
        assertTrue(RunTaskEvent.StdErrOutput("err") in events, events.toString())
        assertEquals(RunTaskEvent.Finished(3), events.last())
    }

    @Test
    fun `input reaches the stdin of the process`() = runBlocking<Unit> {
        val events = ProcessBuilder("cat").toRunHandle().use { handle ->
            launch {
                handle.input.send("hello\n")
                handle.input.close()
            }
            handle.events.toList()
        }
        assertEquals(listOf(RunTaskEvent.StdOutput("hello"), RunTaskEvent.Finished(0)), events)
    }

    @Test
    fun `a command that does not start fails`() = runBlocking<Unit> {
        val events = ProcessBuilder("/no/such/binary").toRunHandle().use { it.events.toList() }
        assertEquals(1, events.size)
        assertInstanceOf(RunTaskEvent.Failed::class.java, events.single())
    }

    @Test
    fun `the second command runs after the first exits with zero, and not after a failure`() = runBlocking<Unit> {
        val ok = listOf(ProcessBuilder("sh", "-c", "echo one"), ProcessBuilder("sh", "-c", "echo two"))
            .toRunHandle(between = { listOf(RunTaskEvent.StdOutput("between")) }).use { it.events.toList() }
        assertEquals(
            listOf(RunTaskEvent.StdOutput("one"), RunTaskEvent.StdOutput("between"), RunTaskEvent.StdOutput("two"), RunTaskEvent.Finished(0)),
            ok,
        )
        val failed = listOf(ProcessBuilder("sh", "-c", "exit 2"), ProcessBuilder("sh", "-c", "echo two")).toRunHandle().use { it.events.toList() }
        assertEquals(listOf(RunTaskEvent.Finished(2)), failed)
    }

    @Test
    fun `cancelling the scope kills the process`() = runBlocking<Unit> {
        withTimeout(10.seconds) {
            val started = CompletableDeferred<Unit>()
            val job = launch {
                // `exec`: the kill reaches `sleep` itself, not a shell whose child keeps the output pipe open.
                ProcessBuilder("sh", "-c", "echo started; exec sleep 60").toRunHandle().use { handle ->
                    handle.events.collect { if (it == RunTaskEvent.StdOutput("started")) started.complete(Unit) }
                }
            }
            started.await()
            // The cancellation kills the program, so the scope ends promptly instead of waiting for `sleep 60`.
            job.cancelAndJoin()
        }
    }

    @Test
    fun `cancelling the scope kills the descendants of the process too`() = runBlocking<Unit> {
        withTimeout(20.seconds) {
            val childPid = CompletableDeferred<Long>()
            val job = launch {
                // The shell prints the pid of its child and waits on it; the cancel must kill both.
                ProcessBuilder("sh", "-c", "sleep 60 & echo PID $!; wait").toRunHandle().use { handle ->
                    handle.events.collect { event ->
                        if (event is RunTaskEvent.StdOutput && event.line.startsWith("PID ")) {
                            childPid.complete(event.line.removePrefix("PID ").trim().toLong())
                        }
                    }
                }
            }
            val pid = childPid.await()
            job.cancelAndJoin()
            awaitDeath(pid)
        }
    }

    /**
     * The child inherits the stdout pipe and ignores the stop of its parent. The readers see no EOF from it,
     * so the drain kills it, and the handle completes instead of waiting out `sleep 60`.
     */
    @Test
    fun `a soft stop kills a child that survives it and holds the output open`() = runBlocking<Unit> {
        withTimeout(30.seconds) {
            val childPid = CompletableDeferred<Long>()
            val ready = CompletableDeferred<RunHandle>()
            val reader = launch {
                ready.await().events.collect { event ->
                    if (event is RunTaskEvent.StdOutput && event.line.startsWith("PID ")) {
                        childPid.complete(event.line.removePrefix("PID ").trim().toLong())
                    }
                }
            }
            ProcessBuilder("sh", "-c", "sleep 60 & echo PID $!; exec sleep 60").toRunHandle().use { handle ->
                ready.complete(handle)
                childPid.await()
                // Leaving the `use` body is the soft stop; SIGTERM ends the root `sleep`, the child stays.
            }
            reader.join()
            awaitDeath(childPid.await())
        }
    }

    /** Waits for [pid] to die: a kill is asynchronous. The caller bounds the wait with its own timeout. */
    private suspend fun awaitDeath(pid: Long) {
        while (ProcessHandle.of(pid).filter { it.isAlive }.isPresent) delay(50.milliseconds)
    }

    /** The end of the `use` body asks the program to end and keeps its output: a JVM prints from its shutdown hooks. */
    @Test
    fun `a soft stop ends the program gracefully and keeps the output it prints on the way out`() = runBlocking<Unit> {
        val events = mutableListOf<RunTaskEvent>()
        val ready = CompletableDeferred<RunHandle>()
        // The reader lives outside the `use` body, so it reads the output the soft stop produces.
        val reader = launch { ready.await().events.collect { events += it } }
        withTimeout(30.seconds) {
            ProcessBuilder("sh", "-c", "trap 'echo HOOK; exit 3' TERM; echo READY; while true; do sleep 1; done").toRunHandle().use { handle ->
                ready.complete(handle)
                while (RunTaskEvent.StdOutput("READY") !in events) delay(10.milliseconds)
                // Leaving the `use` body is the soft stop.
            }
            reader.join()
        }
        assertEquals(listOf(RunTaskEvent.StdOutput("READY"), RunTaskEvent.StdOutput("HOOK"), RunTaskEvent.Finished(3)), events)
    }
}
