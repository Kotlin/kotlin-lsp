// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.buildtool.api.run

import com.intellij.openapi.diagnostic.fileLogger
import com.intellij.util.io.awaitExit
import fleet.util.async.Resource
import fleet.util.async.resource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

private val LOG = fileLogger()

/**
 * The run of one [ProcessBuilder] as a [Resource]: the process starts when the resource is used, its stdout and
 * stderr arrive by line, then [RunTaskEvent.Finished] with the exit code. A process that does not start gives one
 * [RunTaskEvent.Failed]. [input] feeds the stdin; a closed channel closes the stdin. The end of the `use` body is
 * a soft stop (SIGTERM); a cancellation of the scope kills the process and its descendants.
 */
fun ProcessBuilder.toRunHandle(input: Channel<String> = Channel(Channel.UNLIMITED)): Resource<RunHandle> =
    listOf(this).toRunHandle(input = input)

/**
 * The commands of one run, one after the other, as one [Resource]. A command that exits with a non-zero code ends
 * the run with its [RunTaskEvent.Finished]. [between] runs after a command has exited with 0, before the next
 * starts: it maps the index of the finished command to the events to emit. Only the last command reads the input
 * of the handle. The end of the `use` body is a soft stop of the running command; a cancellation kills it.
 */
fun List<ProcessBuilder>.toRunHandle(
    input: Channel<String> = Channel(Channel.UNLIMITED),
    between: suspend (finished: Int) -> List<RunTaskEvent> = { emptyList() },
): Resource<RunHandle> {
    val builders = this
    return resource { cc ->
        val events = Channel<RunTaskEvent>(Channel.UNLIMITED)
        // Completed when the `use` body ends: the running command is asked to end, and no further command starts.
        val softStop = Job()
        val runner = launch {
            var finished: RunTaskEvent = RunTaskEvent.Finished(0)
            try {
                for ((index, builder) in builders.withIndex()) {
                    if (!softStop.isActive) break
                    val last = index == builders.lastIndex
                    val process = try {
                        // NonCancellable: a cancellation at the resume of a plain withContext throws after
                        // `start()` made the process, and the reference is lost with nobody to kill it. This
                        // way the started process always reaches `runProcess`, whose cleanup kills it when
                        // the run is already cancelled. `ensureActive` keeps a cancelled run from starting one.
                        currentCoroutineContext().ensureActive()
                        withContext(NonCancellable + Dispatchers.IO) { builder.start() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        finished = RunTaskEvent.Failed(e)
                        break
                    }
                    val exit = runProcess(process, if (last) input else null, events, softStop)
                    finished = RunTaskEvent.Finished(exit)
                    if (exit != 0 || last || !softStop.isActive) break
                    between(index).forEach { events.send(it) }
                }
            } finally {
                withContext(NonCancellable) {
                    events.send(finished)
                    events.close()
                }
            }
        }
        val handle = object : RunHandle {
            override val events: Flow<RunTaskEvent> = events.receiveAsFlow()
            override val input: SendChannel<String> = input
        }
        try {
            cc(handle)
        } finally {
            softStop.complete()
            withContext(NonCancellable) { runner.join() }
        }
    }
}

/**
 * Runs [process], sends its output lines to [events] and returns its exit code. [softStop] asks the process to end
 * (SIGTERM) and keeps reading, so a JVM prints from its shutdown hooks; a cancellation kills it at once.
 */
private suspend fun runProcess(
    process: Process,
    input: Channel<String>?,
    events: SendChannel<RunTaskEvent>,
    softStop: Job,
): Int = coroutineScope {
    val stdout = launch(Dispatchers.IO) {
        process.inputStream.bufferedReader().forEachLine { events.trySend(RunTaskEvent.StdOutput(it)) }
    }
    val stderr = launch(Dispatchers.IO) {
        process.errorStream.bufferedReader().forEachLine { events.trySend(RunTaskEvent.StdErrOutput(it)) }
    }
    val pump = if (input != null) pumpInput(process, input) else {
        withContext(Dispatchers.IO) { process.outputStream.close() }
        null
    }
    // The descendants the soft stop saw, for the drain below: after the root's death they are reparented
    // and unreachable from its handle.
    val stopped = AtomicReference<List<ProcessHandle>>(null)
    val stopper = launch {
        softStop.join()
        if (process.isAlive) {
            LOG.info("Stopping ${process.pid()}: the use of the run ended")
            withContext(NonCancellable + Dispatchers.IO) { stopped.set(process.stopGracefully()) }
        }
    }
    try {
        process.awaitExit()
    } finally {
        stopper.cancel()
        if (process.isAlive) withContext(NonCancellable + Dispatchers.IO) {
            LOG.info("Killing ${process.pid()} and its descendants: the run was cancelled")
            process.killTree()
            process.awaitExit()
        }
        pump?.cancel()
        withContext(NonCancellable + Dispatchers.IO) {
            // A stopped program may leave a child that inherited the pipes; the readers then see no EOF.
            // Give the output a moment to drain, then kill the stop's survivors: they are the program too.
            // ponytail: a program that exits on its own and leaves such a child keeps the handle open until
            // the child exits; the root is dead by then, so no tree is reachable to find the child in.
            if (withTimeoutOrNull(GRACEFUL_STOP_TIMEOUT) { stdout.join(); stderr.join() } == null) {
                stopped.get()?.forEach { descendant ->
                    if (descendant.isAlive) {
                        LOG.info("Killing pid ${descendant.pid()}: it survived the stop and holds the output open")
                        descendant.destroyForcibly()
                    }
                }
            }
            stdout.join(); stderr.join()
        }
    }
    process.exitValue()
}

/**
 * Asks the process to end (SIGTERM off Windows), so a JVM runs its shutdown hooks, and kills its whole tree
 * when it is still alive after [GRACEFUL_STOP_TIMEOUT]: a launcher that ignores the ask must not leave the
 * program it forked running. Returns the descendants it saw before the ask; the caller kills the ones that
 * survive a stop and hold the output pipes open.
 */
private fun Process.stopGracefully(): List<ProcessHandle> {
    val descendants = toHandle().descendants().toList()
    askToEnd()
    if (!waitFor(GRACEFUL_STOP_TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
        LOG.info("Killing pid ${pid()} and its descendants: it did not end within $GRACEFUL_STOP_TIMEOUT")
        destroyForcibly()
        descendants.forEach { it.destroyForcibly() }
    }
    return descendants
}

/** Kills the process and every descendant it has now. The snapshot comes first: a dead process names no descendants. */
private fun Process.killTree() {
    val descendants = toHandle().descendants().toList()
    toHandle().destroyForcibly()
    descendants.forEach { it.destroyForcibly() }
}

private val GRACEFUL_STOP_TIMEOUT = 5.seconds

/**
 * SIGTERM off Windows, `TerminateProcess` on it, through the [ProcessHandle]: [Process.destroy] closes the streams
 * of the process too, and the lines a program prints on its way out (a JVM from its shutdown hooks) were lost.
 */
private fun Process.askToEnd() {
    toHandle().destroy()
}

private fun CoroutineScope.pumpInput(process: Process, input: Channel<String>) = launch(Dispatchers.IO) {
    process.outputStream.bufferedWriter().use { writer ->
        for (line in input) {
            writer.write(line)
            writer.flush()
        }
    }
}

/** A TCP port nothing listens on right now. Nothing holds it until the program binds it. */
fun freePort(): Int = ServerSocket(0).use { it.localPort }

/**
 * The JDWP agent argument: the program listens on [port] of the loopback interface and waits for a
 * debugger. The adapter attaches to `127.0.0.1` on the same host, so the agent must not accept an
 * external connection.
 */
fun jdwpAgent(port: Int): String = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:$port"
