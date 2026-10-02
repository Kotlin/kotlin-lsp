// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.utils

import com.intellij.openapi.diagnostic.fileLogger
import com.intellij.util.io.awaitExit
import com.jetbrains.ls.api.run.RunHandle
import com.jetbrains.ls.api.run.RunTaskEvent
import fleet.util.async.Resource
import fleet.util.async.resource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.ApiStatus
import java.net.ServerSocket

private val LOG = fileLogger()

/**
 * The events of one [ProcessBuilder] run: stdout and stderr by line, then [RunTaskEvent.Finished] with the exit
 * code. A process that does not start gives one [RunTaskEvent.Failed]. [input] feeds the stdin of the process;
 * a closed channel closes the stdin. [events] starts the process on collection and kills it when the collection
 * stops.
 */
@ApiStatus.Internal
fun ProcessBuilder.toRunHandle(input: Channel<String> = Channel(Channel.UNLIMITED)): RunHandle = object : RunHandle {
    override val input: SendChannel<String> = input
    override val events: Flow<RunTaskEvent> = processEvents(this@toRunHandle, input)
}

/**
 * The commands of one run, one after the other, as one [RunHandle]. A command that exits with a non-zero code
 * ends the run with its [RunTaskEvent.Finished]. [between] runs after a command has exited with 0, before the
 * next starts: it maps the index of the finished command to the events to emit. Only the last command reads
 * the input of the handle.
 */
@ApiStatus.Internal
fun List<ProcessBuilder>.toRunHandle(
    between: suspend (finished: Int) -> List<RunTaskEvent> = { emptyList() },
): RunHandle {
    val input = Channel<String>(Channel.UNLIMITED)
    return object : RunHandle {
        override val input: SendChannel<String> = input
        override val events: Flow<RunTaskEvent> = flow {
            for ((index, builder) in this@toRunHandle.withIndex()) {
                val last = index == lastIndex
                var exitCode = 0
                processEvents(builder, if (last) input else null).collect { event ->
                    if (event is RunTaskEvent.Finished) exitCode = event.exitCode
                    if (last || event !is RunTaskEvent.Finished || event.exitCode != 0) emit(event)
                }
                if (exitCode != 0) return@flow
                if (!last) between(index).forEach { emit(it) }
            }
        }
    }
}

/** A [RunHandle] as a [Resource]: the program lives while the resource is in use. */
internal fun RunHandle.asResource(): Resource<RunHandle> = resource { consumer -> consumer(this@asResource) }

private fun processEvents(builder: ProcessBuilder, input: Channel<String>?): Flow<RunTaskEvent> = channelFlow {
    val process = try {
        withContext(Dispatchers.IO) { builder.start() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        send(RunTaskEvent.Failed(e))
        return@channelFlow
    }
    try {
        val stdout = launch(Dispatchers.IO) {
            process.inputStream.bufferedReader().forEachLine { trySend(RunTaskEvent.StdOutput(it)) }
        }
        val stderr = launch(Dispatchers.IO) {
            process.errorStream.bufferedReader().forEachLine { trySend(RunTaskEvent.StdErrOutput(it)) }
        }
        val pump = if (input != null) pumpInput(process, input) else null
        if (pump == null) withContext(Dispatchers.IO) { process.outputStream.close() }
        process.awaitExit()
        // The program is gone, so nobody reads the input any more; an open channel must not keep the flow alive.
        pump?.cancel()
        stdout.join()
        stderr.join()
        send(RunTaskEvent.Finished(process.exitValue()))
    } finally {
        if (process.isAlive) {
            LOG.info("Killing ${builder.command().firstOrNull()} (pid ${process.pid()}): the run was cancelled")
            process.destroyForcibly()
        }
    }
}.buffer(Channel.UNLIMITED)

private fun CoroutineScope.pumpInput(process: Process, input: Channel<String>) = launch(Dispatchers.IO) {
    process.outputStream.bufferedWriter().use { writer ->
        for (line in input) {
            writer.write(line)
            writer.flush()
        }
    }
}

/** A TCP port nothing listens on right now. Nothing holds it until the program binds it. */
internal fun freePort(): Int = ServerSocket(0).use { it.localPort }

/** The JDWP agent argument: the program listens on [port] and waits for a debugger. */
internal fun jdwpAgent(port: Int): String = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:$port"
