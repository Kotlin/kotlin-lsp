// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.buildtool.api.run

import com.jetbrains.ls.buildtool.api.BuildTool
import fleet.util.async.Resource
import fleet.util.async.resource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

/** One step of a launch: [request] run by [tool]. */
data class RunStep(val tool: BuildTool, val request: RunRequest)

/**
 * The steps of one launch, one after the other, as one [RunHandle]. The last step is the launch; the steps before
 * it are the pre-run steps, e.g. the build of the unit by its own tool. Every step starts with
 * [RunTaskEvent.StepStarted]. A step that ends with [RunTaskEvent.Finished] of a non-zero code or with
 * [RunTaskEvent.Failed] ends the launch with that event; the `Finished(0)` of a pre-run step is not reported.
 * The input of the handle goes to the last step. The end of the `use` body stops the running step; a cancellation
 * kills it.
 */
fun List<RunStep>.run(): Resource<RunHandle> {
    require(isNotEmpty()) { "A launch needs at least one step" }
    val steps = this
    val input = Channel<String>(Channel.UNLIMITED)
    return resource { cc ->
        val events = Channel<RunTaskEvent>(Channel.UNLIMITED)
        // Completed when the `use` body ends: the running step is stopped, and no further step starts.
        val softStop = Job()
        val runner = launch {
            val runnerScope = this
            try {
                for ((index, step) in steps.withIndex()) {
                    if (!softStop.isActive) break
                    val last = index == steps.lastIndex
                    events.send(RunTaskEvent.StepStarted(index, step.request))
                    var stop = false
                    lateinit var reader: Job
                    step.tool.run(step.request).use { stepHandle ->
                        // Undispatched: the input that arrived before the step started is forwarded before the events are read.
                        val pump = if (!last) null else launch(start = CoroutineStart.UNDISPATCHED) {
                            for (line in input) stepHandle.input.send(line)
                            stepHandle.input.close()
                        }
                        // The reader lives in the runner scope, not in the step's `use` body, so the `use` body can
                        // return on a soft stop while the reader still reads the output that stop produces.
                        reader = runnerScope.launch {
                            stepHandle.events.collect { event ->
                                when (event) {
                                    is RunTaskEvent.Failed -> { events.send(event); stop = true }
                                    is RunTaskEvent.Finished -> {
                                        stop = event.exitCode != 0
                                        if (last || stop) events.send(event)
                                    }
                                    else -> events.send(event)
                                }
                            }
                        }
                        // End the step's `use` when the step ends by itself or a soft stop arrives; leaving `use`
                        // asks the step to stop.
                        select { reader.onJoin {}; softStop.onJoin {} }
                        pump?.cancel()
                    }
                    // The step's channel is closed now; drain the output its soft stop produced.
                    withContext(NonCancellable) { reader.join() }
                    if (stop) break
                }
            } finally {
                withContext(NonCancellable) { events.close() }
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
