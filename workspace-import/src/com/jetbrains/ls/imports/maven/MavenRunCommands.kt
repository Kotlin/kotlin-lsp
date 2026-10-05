// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")

package com.jetbrains.ls.imports.maven

import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.jps.entities.exModuleOptions
import com.jetbrains.ls.api.core.launch.WorkspaceModuleMapper
import com.jetbrains.ls.api.run.BuildUnit
import com.jetbrains.ls.api.run.RunOptions
import com.jetbrains.ls.api.run.RunTask
import com.jetbrains.ls.imports.api.importRoot
import org.jetbrains.annotations.VisibleForTesting
import java.io.File
import java.nio.file.Path
import kotlin.io.path.div

/** Maps a Maven module to its unit: the module directory relative to the reactor root, as `-pl` takes it; the root module is the whole reactor. */
object MavenModuleMapper : WorkspaceModuleMapper {
    override val externalSystemId: String = "MAVEN"

    override fun unitOf(module: ModuleEntity): BuildUnit {
        val root = module.importRoot ?: return BuildUnit()
        val dir = module.exModuleOptions?.linkedProjectPath?.takeUnless { it.isBlank() }?.let(Path::of) ?: return BuildUnit()
        return BuildUnit(projectPath = root.relativize(dir).toString().ifEmpty { null })
    }
}

/**
 * The arguments after `mvn` for [task] in its [RunTask.unit]. [BuildUnit.projectPath] is the module directory relative to
 * the reactor root, as `-pl` takes it; `null` or empty means the whole reactor. `-am` keeps the modules the unit
 * depends on in the build, so a sibling resolves from `target/classes` without an `install`.
 *
 * [RunTask.Build] compiles: `compile`, or `test-compile` for the test scope. The whole reactor builds with
 * `--fail-at-end`, so one module's compile error does not hide another's.
 *
 * [RunTask.Run] compiles and writes the runtime classpath of the unit into [classpathFile]: Maven cannot run the
 * main class of one module in the same invocation (`exec:java` binds to every module `-am` builds), so `java` runs
 * it afterwards, see [mavenJavaArgs]. Every module writes the file; the unit is last in the reactor order, so its
 * classpath stays. A run of the `test` source set compiles the tests and takes the `test` scope dependencies.
 *
 * [RunTask.Test] runs [RunTask.Test.tests] through Surefire. `surefire.failIfNoSpecifiedTests=false` keeps a
 * dependency module without the named test from failing the build. [argLine] holds the JVM arguments of the
 * Surefire fork; `null` adds none.
 *
 * [RunOptions.toolArgs] go last, so a configuration wins where Maven takes the later occurrence.
 */
@VisibleForTesting
fun mavenArgs(task: RunTask, options: RunOptions, classpathFile: Path? = null, argLine: String? = null): List<String> = buildList {
    val projectPath = task.unit.projectPath?.takeIf { it.isNotBlank() }
    if (projectPath != null) {
        add("-pl"); add(projectPath); add("-am")
    }
    when (task) {
        is RunTask.Build -> {
            if (projectPath == null) add("--fail-at-end")
            add(if (task.testScope || projectPath == null) "test-compile" else "compile")
        }
        is RunTask.Run -> {
            val test = task.unit.sourceSet == "test"
            add(if (test) "test-compile" else "compile")
            add("dependency:build-classpath")
            add("-Dmdep.includeScope=${if (test) "test" else "runtime"}")
            add("-Dmdep.outputFile=${requireNotNull(classpathFile) { "A Run needs a classpath file" }}")
        }
        is RunTask.Test -> {
            add("test")
            add("-Dtest=${task.tests.joinToString(",")}")
            add("-Dsurefire.failIfNoSpecifiedTests=false")
            if (argLine != null) add("-DargLine=$argLine")
        }
    }
    addAll(options.toolArgs)
}

/**
 * The `java` command that runs [RunTask.Run.entry] of its unit under [root]: [vmArgs], the classpath of
 * `<unit>/target/classes` (for the `test` source set, `target/test-classes` before it) plus the entries of
 * [classpath], the entry, then [RunOptions.programArgs]. [classpath] is the content of the file [mavenArgs]
 * wrote: one line, `File.pathSeparator` between entries. An entry of the form `module/Class` runs with `-m`
 * on the same entries as the module path.
 */
@VisibleForTesting
fun mavenJavaArgs(java: Path, root: Path, task: RunTask.Run, options: RunOptions, vmArgs: List<String>, classpath: String): List<String> = buildList {
    add(java.toString())
    addAll(vmArgs)
    // ponytail: the default layout only; a configured build.outputDirectory needs the Maven model,
    // which this command never reads. Read it from the model if a real project hits this.
    val moduleDir = task.unit.projectPath?.takeIf { it.isNotBlank() }?.let { root / it } ?: root
    val outputs = buildList {
        if (task.unit.sourceSet == "test") add(moduleDir / "target" / "test-classes")
        add(moduleDir / "target" / "classes")
    }
    val entries = (outputs.map(Path::toString) + classpath.trim()).filter { it.isNotEmpty() }.joinToString(File.pathSeparator)
    if ('/' in task.entry) {
        add("--module-path"); add(entries)
        add("-m"); add(task.entry)
    } else {
        add("-cp"); add(entries)
        add(task.entry)
    }
    addAll(options.programArgs)
}

/** The Surefire `argLine`: [vmArgs] in order, space-separated, or `null` when there are none. */
@VisibleForTesting
fun surefireArgLine(vmArgs: List<String>): String? = vmArgs.filter { it.isNotBlank() }.joinToString(" ").ifEmpty { null }
