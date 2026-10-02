// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

import com.jetbrains.ls.api.run.BuildUnit
import com.jetbrains.ls.api.run.RunOptions
import com.jetbrains.ls.api.run.RunTask
import com.jetbrains.ls.imports.gradle.GRADLE_DEBUG_AGENT_PROPERTY
import com.jetbrains.ls.imports.gradle.gradleArgs
import com.jetbrains.ls.imports.gradle.gradleInitScript
import com.jetbrains.ls.imports.gradle.gradleProjectPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GradleRunCommandsTest {
    private val app = BuildUnit(projectPath = ":app")

    @Test
    fun `project path is normalized and a path without a colon is refused`() {
        assertEquals("", gradleProjectPath(":"))
        assertEquals(":app", gradleProjectPath(":app"))
        assertNull(gradleProjectPath("app"))
        assertNull(gradleProjectPath(null))
    }

    @Test
    fun `build compiles the source set of the unit`() {
        assertEquals(listOf(":app:classes", "--console=plain"), gradleArgs(RunTask.Build(app), RunOptions()))
        assertEquals(listOf(":app:testClasses", "--console=plain"), gradleArgs(RunTask.Build(app, testScope = true), RunOptions()))
        assertEquals(
            listOf(":app:integrationTestClasses", "--console=plain"),
            gradleArgs(RunTask.Build(BuildUnit(":app", sourceSet = "integrationTest")), RunOptions()),
        )
        assertEquals(listOf(":classes", "--console=plain"), gradleArgs(RunTask.Build(BuildUnit(":")), RunOptions()))
    }

    @Test
    fun `build of the whole root continues past a failed project`() {
        assertEquals(listOf("classes", "testClasses", "--continue", "--console=plain"), gradleArgs(RunTask.Build(), RunOptions()))
    }

    @Test
    fun `run names the launch task, the init script, the debug agent, then the tool arguments`() {
        val args = gradleArgs(
            RunTask.Run(app, "com.acme.Main"),
            RunOptions(toolArgs = listOf("--offline")),
            initScript = "/tmp/launch.init.gradle",
            debugAgent = listOf("-agentlib:jdwp=x"),
        )
        assertEquals(
            listOf(":app:ideRun", "--init-script=/tmp/launch.init.gradle", "-D$GRADLE_DEBUG_AGENT_PROPERTY=-agentlib:jdwp=x", "--console=plain", "--offline"),
            args,
        )
    }

    @Test
    fun `a console option of the configuration suppresses the default`() {
        val args = gradleArgs(RunTask.Run(app, "com.acme.Main"), RunOptions(toolArgs = listOf("--console=rich")), initScript = "s")
        assertFalse("--console=plain" in args)
        assertTrue("--console=rich" in args)
    }

    @Test
    fun `test names every test`() {
        val args = gradleArgs(RunTask.Test(app, listOf("com.acme.ATest", "com.acme.BTest.m")), RunOptions(), initScript = "s")
        assertEquals(listOf(":app:test", "--tests", "com.acme.ATest", "--tests", "com.acme.BTest.m", "--init-script=s", "--console=plain"), args)
    }

    @Test
    fun `run script carries the entry, the arguments, the environment and the working directory`() {
        val script = gradleInitScript(
            RunTask.Run(app, "com.acme.Main"),
            RunOptions(
                programArgs = listOf("--port", "8080"),
                vmArgs = listOf("-Xmx1g", "-Dit's=\\here"),
                env = mapOf("FOO" to "bar"),
                workingDirectory = "/work/app",
            ),
        )
        assertTrue("ideRunIdentityPath(proj) == ':app'" in script, script)
        assertTrue("ssContainer.findByName('main')" in script, script)
        assertTrue("t.getMainClass().set('com.acme.Main')" in script, script)
        assertTrue("t.args = ['--port', '8080']" in script, script)
        assertTrue("['-Xmx1g', '-Dit\\'s=\\\\here']" in script, script)
        assertTrue("t.environment('FOO', 'bar')" in script, script)
        assertTrue("t.workingDir = '/work/app'" in script, script)
        assertTrue("systemProperty('$GRADLE_DEBUG_AGENT_PROPERTY')" in script, script)
        assertFalse("\${" in script, "no leftover template: $script")
    }

    @Test
    fun `test script configures every Test task of the project`() {
        val script = gradleInitScript(RunTask.Test(BuildUnit(":"), listOf("A")), RunOptions(vmArgs = listOf("-Xmx1g")))
        assertTrue("ideRunIdentityPath(proj) == ':'" in script, script)
        assertTrue("withType(org.gradle.api.tasks.testing.Test)" in script, script)
        assertTrue("['-Xmx1g']" in script, script)
        assertFalse("JavaExec" in script, "no launch task in a test script: $script")
    }
}
