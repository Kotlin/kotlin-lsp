// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle

import com.jetbrains.ls.api.core.launch.WorkspaceModuleMapper
import com.jetbrains.ls.api.run.BuildUnit
import com.jetbrains.ls.api.run.RunOptions
import com.jetbrains.ls.api.run.RunTask
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.platform.workspace.jps.entities.LibraryDependency
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.jps.entities.exModuleOptions
import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.util.system.LowLevelLocalMachineAccess
import com.intellij.util.system.OS
import com.jetbrains.ls.imports.api.importRoot
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import org.jetbrains.annotations.VisibleForTesting

// ponytail: a copy of the pure half of `com.jetbrains.dap.jvm.launch.GradleLaunchContributor`. That copy serves the
// DAP adapter until it moves to `BuildTool.run`; then it goes.

/** Maps a Gradle module to its unit: the project identity path the importer recorded, and the source set of a source-set module. */
object GradleModuleMapper : WorkspaceModuleMapper {
    override val externalSystemId: String = "GRADLE"

    override fun unitOf(module: ModuleEntity): BuildUnit =
        BuildUnit(projectPath = module.exModuleOptions?.linkedProjectId, sourceSet = gradleSourceSetName(module))
}

/** The task the init script registers to run a main class. */
@VisibleForTesting
const val GRADLE_RUN_TASK: String = "ideRun"

/** The init script's name in the launch directory. Gradle picks the script's DSL from the `.gradle` suffix. */
@VisibleForTesting
const val GRADLE_INIT_SCRIPT_NAME: String = "launch.init.gradle"

/**
 * The system property the debug agent travels in, as `-D<name>=…`. A system property and not a Gradle property
 * (`-P`): Gradle fingerprints every Gradle property into the configuration cache, a system property only where
 * the build reads it, and the script reads this one inside the task action, which is no read at all.
 */
@VisibleForTesting
const val GRADLE_DEBUG_AGENT_PROPERTY: String = "ideRunDebugAgent"

/** Separates the debug VM arguments inside [GRADLE_DEBUG_AGENT_PROPERTY]. No JVM argument holds a newline. */
private const val GRADLE_DEBUG_AGENT_SEPARATOR: String = "\n"

private const val SOURCE_SET_MAIN = "main"
private const val SOURCE_SET_TEST = "test"

/**
 * The arguments after `gradle` for [task] in its [RunTask.unit]. [BuildUnit.projectPath] is the project path
 * (`:app`, `:` or empty for the root); `null` means the root for a [RunTask.Run] and the whole build for a
 * [RunTask.Build].
 *
 * [RunTask.Build] runs the `classes` task of the source set: `classes` for `main`, `<name>Classes` for the rest.
 * The whole build runs `classes testClasses --continue`.
 *
 * [RunTask.Run] runs [GRADLE_RUN_TASK], which the init script at [initScript] registers; see [gradleInitScript].
 *
 * [RunTask.Test] runs the `test` task with `--tests` per id. With [debugAgent] the init script adds the agent to
 * every `Test` task of the project.
 *
 * `--console=plain`: the output goes to a pipe, and the rich console redraws it with cursor escapes. Dropped when
 * [RunOptions.toolArgs] names `--console` itself, because Gradle refuses the option twice. The tool arguments go
 * last, so a configuration wins where Gradle takes the later occurrence.
 */
@VisibleForTesting
fun gradleArgs(task: RunTask, options: RunOptions, initScript: String? = null, debugAgent: List<String> = emptyList()): List<String> = buildList {
    val projectPath = gradleProjectPath(task.unit.projectPath)
    when (task) {
        is RunTask.Build -> {
            if (projectPath == null) {
                add("classes"); add("testClasses"); add("--continue")
            } else {
                add(gradleTaskPath(projectPath, gradleClassesTask(task.unit.sourceSet ?: if (task.testScope) SOURCE_SET_TEST else SOURCE_SET_MAIN)))
            }
        }
        is RunTask.Run -> add(gradleTaskPath(projectPath ?: "", GRADLE_RUN_TASK))
        is RunTask.Test -> {
            add(gradleTaskPath(projectPath ?: "", "test"))
            task.tests.forEach { add("--tests"); add(it) }
        }
    }
    if (initScript != null) add("--init-script=$initScript")
    if (debugAgent.isNotEmpty()) add("-D$GRADLE_DEBUG_AGENT_PROPERTY=${debugAgent.joinToString(GRADLE_DEBUG_AGENT_SEPARATOR)}")
    if (options.toolArgs.none { it == "--console" || it.startsWith("--console=") || it.startsWith("-Dorg.gradle.console=") }) {
        add("--console=plain")
    }
    addAll(options.toolArgs)
}

/** The lifecycle task that compiles [sourceSet]: `classes` for `main`, `<sourceSet>Classes` for the rest. */
@VisibleForTesting
fun gradleClassesTask(sourceSet: String): String = if (sourceSet == SOURCE_SET_MAIN) "classes" else "${sourceSet}Classes"

/** The absolute task path for [task] in the project at [projectPath]: `:task` for the root, `:app:task` for a subproject. */
@VisibleForTesting
fun gradleTaskPath(projectPath: String, task: String): String = "$projectPath:$task"

/**
 * The project path in [path], normalized for [gradleTaskPath]: `""` for the root, `:app` for a subproject.
 * `null` for an absent path or one without the leading colon: a guess would run the program in another project.
 */
@VisibleForTesting
fun gradleProjectPath(path: String?): String? {
    if (path.isNullOrBlank()) return null
    if (!path.startsWith(":")) return null
    return if (path == ":") "" else path
}

/**
 * The init script for [task] in the project of its [RunTask.unit] (`:` for the root).
 *
 * For a [RunTask.Run] it registers [GRADLE_RUN_TASK], a `JavaExec` on the runtime classpath of the source set
 * ([BuildUnit.sourceSet], default `main`): [RunOptions.programArgs], [RunOptions.vmArgs], [RunOptions.env] and
 * [RunOptions.workingDirectory] go to the forked JVM. The daemon forks it, so the environment of the `gradle`
 * process does not reach it; the script sets it on the task.
 *
 * For a [RunTask.Test] it adds [RunOptions.vmArgs] to every `Test` task of the project.
 *
 * In both the debug agent is read from [GRADLE_DEBUG_AGENT_PROPERTY] inside `doFirst`, so a per-launch port
 * does not change the script and the configuration cache stays valid.
 *
 * Every callback is a Groovy closure: Gradle 6 compiles the script with Groovy 2, which has no lambda.
 */
@VisibleForTesting
fun gradleInitScript(task: RunTask, options: RunOptions): String {
    val projectPath = gradleProjectPath(task.unit.projectPath)?.ifEmpty { ":" } ?: ":"
    val body = when (task) {
        is RunTask.Run -> gradleRunTaskRegistration(task, options)
        is RunTask.Test -> gradleTestTaskConfiguration(options)
        is RunTask.Build -> ""
    }
    return """
        def ideRunOlderThanGradle64 = org.gradle.util.GradleVersion.current().baseVersion <
            org.gradle.util.GradleVersion.version('6.4')
        def ideRunIdentityPath = { proj ->
            try { return proj.identityPath.path } catch (Throwable ignored) { return proj.path }
        }

        void afterProject(Gradle gradle, java.util.function.Consumer<Project> action) {
            if (org.gradle.util.GradleVersion.current().baseVersion >= org.gradle.util.GradleVersion.version('8.8')) {
                gradle.getLifecycle().afterProject { action.accept(it) }
                return
            }
            gradle.allprojects { proj -> proj.afterEvaluate { action.accept(it) } }
        }

        afterProject(gradle) { proj ->
            if (ideRunIdentityPath(proj) == ${groovyString(projectPath)}) {
                $GRADLE_DEBUG_AGENT_LINES
        $body
            }
        }
    """.trimIndent()
}

private fun gradleRunTaskRegistration(task: RunTask.Run, options: RunOptions): String {
    val sourceSet = task.unit.sourceSet ?: SOURCE_SET_MAIN
    return """
                def ssContainer = proj.extensions.findByName('sourceSets')
                if (ssContainer == null) {
                    throw new org.gradle.api.GradleException("Cannot run a main class in Gradle project '" +
                        proj.path + "': it has no source sets (no JVM plugin is applied).")
                }
                def ss = ssContainer.findByName(${groovyString(sourceSet)})
                if (ss == null) {
                    throw new org.gradle.api.GradleException("Cannot run a main class from source set '" +
                        ${groovyString(sourceSet)} + "' in Gradle project '" + proj.path +
                        "': it has no such source set (it has: " + ssContainer.names.join(', ') + ").")
                }
                if (proj.tasks.names.contains(${groovyString(GRADLE_RUN_TASK)})) {
                    throw new org.gradle.api.GradleException("Cannot launch through Gradle in project '" +
                        proj.path + "': it already has a task named " + ${groovyString(GRADLE_RUN_TASK)} +
                        ". Rename that task, or launch this class as a JVM program instead.")
                }
                proj.tasks.register(${groovyString(GRADLE_RUN_TASK)}, org.gradle.api.tasks.JavaExec) { t ->
                    t.classpath = ss.runtimeClasspath
                    if (ideRunOlderThanGradle64) t.main = ${groovyString(task.entry)} else t.getMainClass().set(${groovyString(task.entry)})
                    t.args = ${groovyList(options.programArgs)}
                    def jvmArgs = new ArrayList()
                    jvmArgs.addAll(${groovyList(options.vmArgs)}.findAll { it })
                    jvmArgs.addAll(t.jvmArgs)
                    t.jvmArgs = jvmArgs
                    t.standardInput = System.in
                    t.doFirst { tsk ->
                        def ideRunAgent = ideRunDebugAgent()
                        if (ideRunAgent) tsk.jvmArgs = tsk.jvmArgs + ideRunAgent
                    }
    ${gradleEnvironmentLines(options.env)}${gradleWorkingDirLine(options.workingDirectory)}            }
    """.trimEnd()
}

private fun gradleTestTaskConfiguration(options: RunOptions): String = """
                proj.tasks.withType(org.gradle.api.tasks.testing.Test).configureEach { t ->
                    def jvmArgs = new ArrayList()
                    jvmArgs.addAll(${groovyList(options.vmArgs)}.findAll { it })
                    jvmArgs.addAll(t.jvmArgs)
                    t.jvmArgs = jvmArgs
                    t.doFirst { tsk ->
                        def ideRunAgent = ideRunDebugAgent()
                        if (ideRunAgent) tsk.jvmArgs = tsk.jvmArgs + ideRunAgent
                    }
    ${gradleEnvironmentLines(options.env)}            }
""".trimEnd()

/**
 * Reads [GRADLE_DEBUG_AGENT_PROPERTY] without making it a configuration input: `providers.systemProperty` when
 * the daemon has it (Gradle 6.1), an eager `System.getProperty` before. The closure answers the list of agent
 * arguments, empty for a run without debug.
 */
private val GRADLE_DEBUG_AGENT_LINES: String = listOf(
    "def ideRunAgentProvider = null",
    "try { ideRunAgentProvider = proj.providers.systemProperty('$GRADLE_DEBUG_AGENT_PROPERTY') }",
    "catch (Throwable ignored) { /* Gradle < 6.1 has no ProviderFactory.systemProperty */ }",
    "def ideRunAgentEager = ideRunAgentProvider == null ? System.getProperty('$GRADLE_DEBUG_AGENT_PROPERTY') : null",
    "def ideRunDebugAgent = {",
    "    def ideRunAgentValue = ideRunAgentProvider == null ? ideRunAgentEager",
    "        : (ideRunAgentProvider.isPresent() ? ideRunAgentProvider.get() : null)",
    "    if (!ideRunAgentValue) return []",
    "    return ideRunAgentValue.toString().split('\\n').findAll { it }",
    "}",
).joinToString("\n" + "        ")

private const val TASK_BODY_INDENT = "                    "

private fun gradleEnvironmentLines(env: Map<String, String>): String =
    env.entries.joinToString("") { (key, value) -> TASK_BODY_INDENT + "t.environment(${groovyString(key)}, ${groovyString(value)})\n" }

private fun gradleWorkingDirLine(workingDir: String?): String =
    if (workingDir == null) "" else TASK_BODY_INDENT + "t.workingDir = ${groovyString(workingDir)}\n"

/** A Groovy single-quoted literal: no interpolation, so only the backslash, the quote and the line ends need escaping. */
private fun groovyString(value: String): String {
    val escaped = value
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
    return "'$escaped'"
}

private fun groovyList(items: List<String>): String = items.joinToString(prefix = "[", postfix = "]") { groovyString(it) }

// ----- the whole root: the compile task of every source set the model records -----

/**
 * The compile task of every source-set module of the Gradle root at [root] in [storage], as absolute task paths:
 * `:app:classes`, `:app:testClasses`, `:lib:integrationTestClasses`, `:android:compileDebugSources` for an
 * Android variant. Distinct and sorted, so one workspace always gives one command.
 *
 * Only a source-set module contributes: a project's holder module has no source set and nothing of its own to
 * compile. A source-set module whose project the importer could not name (an included build on a Gradle older
 * than 9.2.0) has no task path from the main build and is left out.
 */
@VisibleForTesting
fun gradleWorkspaceTasks(storage: EntityStorage, root: Path): List<String> {
    val tasks = sortedSetOf<String>()
    storage.entities(ModuleEntity::class.java)
        .filter { it.exModuleOptions?.externalSystem == "GRADLE" && it.importRoot == root }
        .forEach { module ->
            val sourceSet = gradleSourceSetName(module) ?: return@forEach
            val projectPath = gradleProjectPath(module.exModuleOptions?.linkedProjectId) ?: return@forEach
            tasks += gradleTaskPath(projectPath, gradleCompileTask(sourceSet, module.isAndroidSourceSet))
        }
    return tasks.toList()
}

/**
 * The arguments after `gradle` that compile [tasks] in one invocation, with `--continue` so one project's error
 * does not hide another's. An empty list falls back to the bare `classes testClasses`: a build whose modules the
 * importer split no source sets off. A list too long for the command line goes into an init script that sets
 * `startParameter.taskNames`; see [gradleTaskListInitScript].
 */
@VisibleForTesting
fun gradleWorkspaceArgs(tasks: List<String>, options: RunOptions, initScriptOf: (String) -> Path = ::writeTaskListInitScript, windows: Boolean = isWindows): List<String> {
    fun args(taskArgs: List<String>) = buildList {
        addAll(taskArgs)
        add("--continue")
        if (options.toolArgs.none { it == "--console" || it.startsWith("--console=") || it.startsWith("-Dorg.gradle.console=") }) add("--console=plain")
        addAll(options.toolArgs)
    }
    if (tasks.isEmpty()) return args(listOf("classes", "testClasses"))
    val inline = args(tasks)
    if (inline.sumOf { it.length + 1 } < if (windows) 8000 else 100_000) return inline
    return args(listOf("--init-script=${initScriptOf(gradleTaskListInitScript(tasks))}"))
}

/**
 * The init script that makes Gradle run exactly [tasks], as if they were on the command line. Only the root
 * build's start parameter selects the tasks: init scripts run for included builds and `buildSrc` too, and there
 * the assignment re-targets that nested build.
 */
@VisibleForTesting
fun gradleTaskListInitScript(tasks: List<String>): String =
    "if (gradle.parent == null) {\n    gradle.startParameter.taskNames = ${groovyList(tasks)}\n}\n"

private fun writeTaskListInitScript(script: String): Path {
    val file = Files.createTempFile("lsp-gradle-build-", ".init.gradle")
    file.writeText(script)
    return file
}

@OptIn(LowLevelLocalMachineAccess::class)
private val isWindows: Boolean get() = OS.CURRENT == OS.Windows

/** The task that compiles [sourceSet]: [gradleClassesTask], or AGP's `compile<Component>Sources` when [android]. */
@VisibleForTesting
fun gradleCompileTask(sourceSet: String, android: Boolean): String =
    if (android) "compile${sourceSet.replaceFirstChar { it.uppercaseChar() }}Sources" else gradleClassesTask(sourceSet)

/**
 * The library the importer attaches to every source-set module of an Android variant: the SDK's boot classpath.
 * The importer stamps no other Android marker, so this dependency is the test.
 */
private const val ANDROID_SDK_LIBRARY: String = "Gradle: android:android-sdk:null"

private val ModuleEntity.isAndroidSourceSet: Boolean
    get() = dependencies.any { it is LibraryDependency && it.library.name == ANDROID_SDK_LIBRARY }

/**
 * The Gradle source-set name of [module] (`main`, `test`, `integrationTest`), or `null` when it is not a
 * source-set module. The importer names a source-set module `<project module>.<source set>`, so the last
 * dot-segment is the name. A project may itself be named with dots (`:1.21.1`), so the content roots settle it:
 * a project module's content root is its project directory, a source set's roots are directories under it.
 */
@VisibleForTesting
fun gradleSourceSetName(module: ModuleEntity): String? {
    val projectDir = module.exModuleOptions?.linkedProjectPath?.takeUnless { it.isBlank() }?.let { Path.of(it) } ?: return null
    val sourceSetName = module.name.substringAfterLast('.', missingDelimiterValue = "")
    if (sourceSetName.isEmpty()) return null
    val normalizedProjectDir = projectDir.toAbsolutePath().normalize()
    val contentRoots = module.contentRoots.map { Path.of(VfsUtilCore.urlToPath(it.url.url)) }
    if (contentRoots.any { it.toAbsolutePath().normalize() == normalizedProjectDir }) return null
    return sourceSetName
}
