// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

import com.jetbrains.ls.api.run.BuildUnit
import com.jetbrains.ls.api.run.RunHandle
import com.jetbrains.ls.api.run.RunRequest
import com.jetbrains.ls.api.run.RunStep
import com.jetbrains.ls.api.run.RunTask
import com.jetbrains.ls.api.run.RunTaskEvent
import com.jetbrains.ls.api.run.failedRunHandle
import com.jetbrains.ls.api.run.run
import com.jetbrains.ls.imports.api.BuildTool
import com.jetbrains.ls.imports.api.BuildToolContext
import com.jetbrains.ls.imports.api.SyncRequest
import com.jetbrains.ls.imports.api.ImportEvent
import fleet.util.async.Resource
import fleet.util.async.resourceOf
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.toList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class RunStepsTest {
    private val build = RunRequest(RunTask.Build(BuildUnit(":app")))
    private val launch = RunRequest(RunTask.Run(BuildUnit(":app"), "Main"))

    /** A tool whose run emits [events] and records what it reads from the input. */
    private class FakeTool(private val events: List<RunTaskEvent>) : BuildTool {
        val input = Channel<String>(Channel.UNLIMITED)
        override fun sync(context: BuildToolContext, request: SyncRequest): Flow<ImportEvent> = emptyFlow()
        override fun run(request: RunRequest): Resource<RunHandle> = resourceOf(object : RunHandle {
            override val input: SendChannel<String> = this@FakeTool.input
            override val events: Flow<RunTaskEvent> = flow { this@FakeTool.events.forEach { emit(it) } }
        })
    }

    @Test
    fun `the steps run in order and the Finished of a pre-run step is not reported`() = runBlocking<Unit> {
        val events = listOf(
            RunStep(FakeTool(listOf(RunTaskEvent.StdOutput("built"), RunTaskEvent.Finished(0))), build),
            RunStep(FakeTool(listOf(RunTaskEvent.StdOutput("ran"), RunTaskEvent.Finished(0))), launch),
        ).run().use { it.events.toList() }
        assertEquals(
            listOf(
                RunTaskEvent.StepStarted(0, build), RunTaskEvent.StdOutput("built"),
                RunTaskEvent.StepStarted(1, launch), RunTaskEvent.StdOutput("ran"), RunTaskEvent.Finished(0),
            ),
            events,
        )
    }

    @Test
    fun `a failed pre-run step ends the launch`() = runBlocking<Unit> {
        val events = listOf(
            RunStep(FakeTool(listOf(RunTaskEvent.Finished(1))), build),
            RunStep(FakeTool(listOf(RunTaskEvent.StdOutput("ran"))), launch),
        ).run().use { it.events.toList() }
        assertEquals(listOf(RunTaskEvent.StepStarted(0, build), RunTaskEvent.Finished(1)), events)

        val failed = listOf(
            RunStep(object : BuildTool by FakeTool(emptyList()) {
                override fun run(request: RunRequest) = resourceOf(failedRunHandle(IllegalStateException("boom")))
            }, build),
            RunStep(FakeTool(listOf(RunTaskEvent.StdOutput("ran"))), launch),
        ).run().use { it.events.toList() }
        assertEquals(2, failed.size)
        assertInstanceOf(RunTaskEvent.Failed::class.java, failed[1])
    }

    @Test
    fun `the input goes to the last step`() = runBlocking<Unit> {
        val first = FakeTool(listOf(RunTaskEvent.Finished(0)))
        val last = FakeTool(listOf(RunTaskEvent.Finished(0)))
        listOf(RunStep(first, build), RunStep(last, launch)).run().use { handle ->
            handle.input.send("hi")
            handle.input.close()
            handle.events.toList()
        }
        assertEquals(listOf("hi"), last.input.toList())
        assertEquals(emptyList<String>(), first.input.apply { close() }.toList())
    }
}
