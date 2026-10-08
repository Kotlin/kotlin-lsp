// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")

package com.jetbrains.ls.imports.maven

import com.intellij.openapi.application.PathManager
import com.intellij.diagnostic.rethrowControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.jps.entities.exModuleOptions
import com.intellij.platform.workspace.jps.entities.modifyExternalSystemModuleOptionsEntity
import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.impl.url.toVirtualFileUrl
import com.intellij.platform.workspace.storage.url.VirtualFileUrlManager
import com.intellij.util.io.delete
import com.intellij.util.system.LowLevelLocalMachineAccess
import com.intellij.util.system.OS
import com.jetbrains.ls.imports.api.BuildTool
import com.jetbrains.ls.imports.api.BuildToolContext
import com.jetbrains.ls.imports.api.BuildToolDriverContext
import com.jetbrains.ls.imports.api.ImportRequest
import com.jetbrains.ls.imports.api.SyncRequest
import com.jetbrains.ls.imports.api.ToolFileListener
import com.jetbrains.ls.imports.api.modifiedSince
import com.jetbrains.ls.imports.api.WorkspaceEntitySource
import com.jetbrains.ls.imports.api.WorkspaceException
import com.jetbrains.ls.imports.api.WorkspaceImportException
import com.jetbrains.ls.imports.api.WorkspaceImportOptions
import com.jetbrains.ls.imports.api.WorkspaceImportParameters
import com.jetbrains.ls.imports.api.WorkspaceImporter.ImportEvent
import com.jetbrains.ls.imports.api.environmentVariable
import com.jetbrains.ls.imports.api.putEnvironment
import com.jetbrains.ls.api.run.RunHandle
import com.jetbrains.ls.api.run.RunRequest
import com.jetbrains.ls.api.run.RunTask
import com.jetbrains.ls.api.run.RunTaskEvent
import com.jetbrains.ls.api.run.UnsupportedRunException
import com.jetbrains.ls.api.run.failedRunHandle
import com.jetbrains.ls.api.run.freePort
import com.jetbrains.ls.api.run.jdwpAgent
import com.jetbrains.ls.api.run.toRunHandle
import com.jetbrains.ls.imports.json.WorkspaceData
import com.jetbrains.ls.imports.json.importWorkspaceData
import com.jetbrains.ls.imports.json.postProcessWorkspaceData
import com.jetbrains.ls.imports.utils.fixMissingProjectSdk
import com.jetbrains.ls.imports.utils.runWithErrorReporting
import com.jetbrains.ls.imports.utils.stampBuildToolJavaHome
import fleet.util.async.Resource
import fleet.util.async.map
import fleet.util.async.resourceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import org.jetbrains.annotations.ApiStatus
import java.io.File
import java.io.InputStream
import java.nio.file.Path
import kotlin.io.path.createTempFile
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val LOG = logger<MavenTool>()

/** One folder's live Maven build tool; [MavenDriver] starts it. */
class MavenTool(
    private val toolContext: BuildToolDriverContext,
    private val parameters: WorkspaceImportParameters,
) : BuildTool {
    companion object {
        /** The Maven distribution to import with, when the project has no wrapper. */
        const val JB_MAVEN_HOME_PROPERTY: String = "JB_MAVEN_HOME"

        /** The JDK to run Maven under. Named so that callers scoping these properties do not have to spell them. */
        const val JB_MAVEN_JAVA_HOME_PROPERTY: String = "JB_MAVEN_JAVA_HOME"

        const val LSP_MAVEN_PROJECT_OFFLINE_PROPERTY: String = "com.jetbrains.ls.imports.maven.offline"
        const val LSP_MAVEN_PROJECT_MAVEN_USER_HOME_PROPERTY: String = "com.jetbrains.ls.imports.maven.mavenUserHome"
        const val LSP_MAVEN_PROJECT_MAVEN_OPTS_PROPERTY: String = "com.jetbrains.ls.imports.maven.opts"
        const val LSP_MAVEN_PROJECT_PATH_PREPEND_PROPERTY: String = "com.jetbrains.ls.imports.maven.path.prepend"

        fun useMavenAndJava(mavenHome: Path, javaHome: Path) {
            System.setProperty(JB_MAVEN_HOME_PROPERTY, mavenHome.toString())
            System.setProperty(JB_MAVEN_JAVA_HOME_PROPERTY, javaHome.toString())
        }
    }

    override fun sync(context: BuildToolContext, request: SyncRequest): Flow<ImportEvent> =
        channelFlow {
            val startedAt = System.currentTimeMillis()
            if (!request.force && request.toolRequest == null && !inputsChangedSince(lastSyncStartedAt, toolContext.entityStorage())) {
                send(ImportEvent.WorkspaceModelNotChanged(request.targetWatermark))
                return@channelFlow
            }
            // The baseline is taken before the import reads its inputs, so a change landing while it runs
            // reads as changed at the next judgment, and the next cycle serves it.
            lastSyncStartedAt = startedAt
            context.withProject {
                importWorkspace(context.virtualFileUrlManager, request.targetWatermark).collect { send(it) }
            }
        }
            // A sync may list poms the last model did not; their directories must be watched from now on.
            .onEach { event -> if (event is ImportEvent.UpdateWorkspaceModel) registerWatchedDirectories(event.storage) }
            .catch { e ->
                rethrowControlFlowException(e)
                emit(ImportEvent.Failed(e))
            }

    /** The wall-clock start of the last sync that imported; the baseline [inputsChangedSince] verifies against. */
    @Volatile
    private var lastSyncStartedAt: Long? = null

    private val reimportRequestsFlow = MutableSharedFlow<ImportRequest>(replay = 1, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** This tool's own asks; the platform debounces them into reload cycles. */
    override val reimportRequests: Flow<ImportRequest> get() = reimportRequestsFlow

    /** The tool's own watching: a change to a pom the model lists, or to a settings file, asks for a re-import. */
    private val watchListener = object : ToolFileListener {
        override fun changed(path: Path) {
            if (path !in settingsFiles + importedPomFiles(toolContext.entityStorage())) return
            LOG.info("Maven settings files changed: $path")
            reimportRequestsFlow.tryEmit(MavenReimport)
        }

        override fun lost() {
            LOG.info("The file watcher lost changes, so a Maven settings file may have changed too")
            reimportRequestsFlow.tryEmit(MavenReimport)
        }
    }

    /**
     * The build file of this target: the configured file, or the conventional pom in the project directory.
     * A configured project may point directly at a non-standard build file (`mvn -f dev_pom.xml`);
     * auto-detected folders arrive as directories.
     */
    private val rootPomFile: Path =
        parameters.projectFileOrDirectory.let { if (it.isRegularFile()) it else it / "pom.xml" }

    /**
     * The fixed-location configuration of this target: Maven reads `.mvn/maven.config` and
     * `.mvn/settings.xml` from the base directory of the build file, and the wrapper lives next to it.
     * The user-level `~/.m2/settings.xml` lies outside the workspace, so the watcher cannot report it.
     */
    private val settingsFiles: Set<Path> = with(parameters.projectDirectory) {
        setOf(
            this / ".mvn" / "maven.config",
            this / ".mvn" / "settings.xml",
            this / "mvnw",
            this / "mvnw.cmd",
            this / ".mvn" / "wrapper" / "maven-wrapper.properties",
        )
    }

    init {
        registerWatchedDirectories(toolContext.entityStorage())
    }

    /**
     * Registers the directories of this target's inputs with the workspace file watcher: the settings files'
     * fixed locations and the directory of every pom [storage] lists. Indexing watches the source roots only,
     * and the poms sit beside them. Called at start with the committed model and after every sync result.
     */
    private fun registerWatchedDirectories(storage: EntityStorage) {
        (settingsFiles + importedPomFiles(storage)).mapNotNullTo(linkedSetOf()) { it.parent }
            .forEach { directory -> toolContext.watcher.watch(directory, watchListener) }
    }

    /**
     * Whether an input of the last import changed on disk after [since]: a pom the committed model lists is
     * missing or modified, or a settings file of this target is modified. A null [since] means no sync of this
     * tool has imported yet, so nothing vouches for the model. Disk is the source, not the tool's own event
     * queue: the sync runs after the client's barrier, so the files already hold every change the answer must
     * cover, while a queued event may still be in flight.
     */
    internal fun inputsChangedSince(since: Long?, storage: EntityStorage): Boolean {
        if (since == null) return true
        if (importedPomFiles(storage).any { !it.isRegularFile() || it.modifiedSince(since) }) return true
        return settingsFiles.any { it.modifiedSince(since) }
    }

    /**
     * The poms the last import read, as recorded in the committed model: every module of this target
     * carries its Maven project directory, and the import stamps the build file it ran with as the root
     * project path, which also tells this target's modules from another target's. Read from the model
     * rather than remembered, so it stays correct across a restart that restores the model from cache
     * without running an import.
     *
     * ponytail: a submodule's pom is taken as `<module directory>/pom.xml`; a reactor that names a module
     * pom differently (a file path in `<module>`) re-imports only on a root pom change.
     */
    private fun importedPomFiles(storage: EntityStorage): Set<Path> {
        val poms = mutableSetOf(rootPomFile)
        storage.entities(ModuleEntity::class.java)
            .mapNotNull { it.exModuleOptions }
            .filter { it.externalSystem == MAVEN_EXTERNAL_SYSTEM_ID && it.rootProjectPath == rootPomFile.toString() }
            .mapNotNullTo(poms) { options -> options.linkedProjectPath?.let { Path.of(it) / "pom.xml" } }
        return poms
    }

    /**
     * Publishes the dependency model (`model-with-deps`) as soon as it is built, then republishes it with the source
     * roots that only exist once the code generators have run (`model-process-sources`), so the analyzer does not wait
     * for the generating plugins before it can resolve the project's dependencies.
     */
    private fun importWorkspace(virtualFileUrlManager: VirtualFileUrlManager, watermark: Long): Flow<ImportEvent> = channelFlow {
        val projectDirectory = parameters.projectDirectory
        val options = parameters.options
        val pomFile = rootPomFile
        if (!pomFile.exists()) return@channelFlow

        LOG.info("Importing Maven project from: $projectDirectory (pom: $pomFile)")
        val javaHome = javaHome()
        val mavenJavaHome = mavenJavaHome()
        val execPath = execPath()
        LOG.info("Using Maven: $execPath (JAVA_HOME=$mavenJavaHome)")


        // `-o` keeps the build in the local repository, so the import never reaches the network.
        val offlineOpts =
            if (options.offline || System.getProperty(LSP_MAVEN_PROJECT_OFFLINE_PROPERTY).toBoolean()) listOf("-o")
            else emptyList()
        send(ImportEvent.ProgressStatus("Installing Maven plugin..."))
        installMavenPlugin(execPath, javaHome, projectDirectory, pomFile, channel, offlineOpts, options)


        send(ImportEvent.ProgressStatus("Collecting Maven model..."))
        val modelWithDeps = when (val result =
            runMavenPluginGoal(javaHome, pomFile, "model-with-deps", channel, offlineOpts, options)) {
            is ErrorResult -> throw result.e
            is SuccessResult -> result
        }
        send(ImportEvent.ProgressStatus("Maven model collected, commiting..."))
        send(ImportEvent.UpdateWorkspaceModel(toStorage(modelWithDeps, null, pomFile, virtualFileUrlManager, channel, mavenJavaHome), watermark))

        if (skipGenerateSources(options)) {
            LOG.info("Skipping source generation: skipGenerateSources is set for the project or the session")
            return@channelFlow
        }
        send(ImportEvent.ProgressStatus("Generating sources..."))
        val modelWithGeneratedSources = when (val result =
            runMavenPluginGoal(javaHome, pomFile, "model-process-sources", channel, offlineOpts, options)) {
            // As before: source generation is best-effort, the dependency model already published stands on its own.
            // Reported as output rather than as `Failed`, which would show the client an error for an import that
            // succeeded, only without generated sources.
            is ErrorResult -> {
                LOG.warn("Source generation failed, keeping the model without generated sources", result.e)
                send(ImportEvent.ErrorOutput("Source generation failed, generated sources may be missing: ${result.e.message}"))
                return@channelFlow
            }
            is SuccessResult -> result
        }
        send(ImportEvent.ProgressStatus("Maven model collected, commiting..."))
        send(
            ImportEvent.UpdateWorkspaceModel(
                toStorage(modelWithDeps, modelWithGeneratedSources, pomFile, virtualFileUrlManager, channel, mavenJavaHome),
                watermark,
            )
        )
    }.buffer(Channel.UNLIMITED)

    /**
     * Merges the two goal results into one workspace model; [resultGenSources] is `null` before it has been built.
     * [javaHome] is the JDK the goals ran with; it is stamped on every module for the build command.
     */
    private fun toStorage(
        resultDeps: SuccessResult,
        resultGenSources: SuccessResult?,
        pomFile: Path,
        virtualFileUrlManager: VirtualFileUrlManager,
        events: SendChannel<ImportEvent>,
        javaHome: String?,
    ): EntityStorage {
        val projectDirectory = parameters.projectDirectory
        val merged = mergeResults(resultDeps, resultGenSources) as SuccessResult
        return MutableEntityStorage.create().apply {
            importWorkspaceData(
                postProcessWorkspaceData(
                    merged.workspaceData,
                    projectDirectory,
                    onUnresolvedDependency = { events.trySend(ImportEvent.UnresolvedDependency(it)) },
                ),
                projectDirectory,
                WorkspaceEntitySource(projectDirectory.toVirtualFileUrl(virtualFileUrlManager)),
                virtualFileUrlManager, false,
                MAVEN_EXTERNAL_SYSTEM_ID
            )
            // The launch/build path re-runs Maven from the module's import root and lets Maven resolve
            // the pom from the working directory; record the build file the import actually used so a
            // non-standard pom name (`mvn -f dev_pom.xml`) reaches those invocations too.
            entities(ModuleEntity::class.java).mapNotNull { it.exModuleOptions }.toList().forEach {
                modifyExternalSystemModuleOptionsEntity(it) { rootProjectPath = pomFile.toString() }
            }
            fixMissingProjectSdk(parameters.options.javaHome ?: parameters.defaultSdkPath, virtualFileUrlManager)
            stampMavenJavaHome(javaHome)
        }
    }

    /**
     * Whether the code generators stay unrun: the option of the project, else of the session
     * ([WorkspaceImportOptions.skipGenerateSources], which the import cycle fills in). There is no switch of the
     * server process: one server serves every client of a workspace, and a setting of its process would be the
     * choice of whichever client started it.
     */
    private fun skipGenerateSources(options: WorkspaceImportOptions): Boolean = options.skipGenerateSources == true

    /** The Maven to run: the wrapper of the project, the [JB_MAVEN_HOME_PROPERTY] distribution, or `mvn` on the `PATH`. */
    @OptIn(LowLevelLocalMachineAccess::class)
    private fun execPath(): Path {
        val projectDirectory = parameters.projectDirectory
        val wrapper = projectDirectory / (if (OS.CURRENT == OS.Windows) "mvnw.cmd" else "mvnw")
        val mavenHome = System.getProperty(JB_MAVEN_HOME_PROPERTY)?.let { Path.of(it) }
        return when {
            wrapper.exists() -> wrapper
            mavenHome != null -> mavenHome / "bin" / if (OS.CURRENT == OS.Windows) "mvn.cmd" else "mvn"
            else -> Path.of(if (OS.CURRENT == OS.Windows) "mvn.cmd" else "mvn")
        }
    }

    /**
     * The JDK Maven runs under. A `java-home` configured for this project wins over the JVM property, then the
     * ambient `JAVA_HOME`, then the server's own JVM. The ambient value is named here because [mavenProcess]
     * starts Maven from an empty environment, so nothing is inherited.
     */
    private fun javaHome(): String? =
        parameters.options.javaHome?.toString()
        ?: System.getProperty(JB_MAVEN_JAVA_HOME_PROPERTY)
        ?: System.getenv("JAVA_HOME")
        ?: System.getProperty("java.home")

    /** The `JAVA_HOME` the Maven processes see: [mavenProcess] applies the per-project `env` last, so it wins. */
    private fun mavenJavaHome(): String? = parameters.options.environment.environmentVariable("JAVA_HOME") ?: javaHome()

    /**
     * The Maven process for [args], in the project directory. The environment starts empty, so the analyzer's own
     * variables (a JDK9+ `JAVA_TOOL_OPTIONS=-Xlog`) do not reach a possibly-JDK8 Maven JVM. [extraEnvironment] is
     * applied last, after the per-project `env`.
     */
    private fun mavenProcess(args: List<String>, javaHome: String? = javaHome(), extraEnvironment: Map<String, String> = emptyMap()): ProcessBuilder =
        ProcessBuilder(listOf(execPath().toString()) + args).apply {
            environment().clear()
            javaHome?.let { environment()["JAVA_HOME"] = it }
            System.getProperty(LSP_MAVEN_PROJECT_MAVEN_USER_HOME_PROPERTY)?.let { environment()["MAVEN_USER_HOME"] = it }
            System.getProperty(LSP_MAVEN_PROJECT_MAVEN_OPTS_PROPERTY)?.let { environment()["MAVEN_OPTS"] = it }
            System.getProperty(LSP_MAVEN_PROJECT_PATH_PREPEND_PROPERTY)?.let { prependToPath(environment(), it) }
            // Per-project `env` is applied after the defaults above so it wins over them.
            environment().putEnvironment(parameters.options.environment)
            environment().putEnvironment(extraEnvironment)
        }.directory(parameters.projectDirectory.toFile())

    /**
     * [RunTask.Build] and [RunTask.Test] are one `mvn` process each. [RunTask.Run] is two: `mvn` compiles the unit and
     * the modules it depends on and writes the runtime classpath of the unit, then `java` runs the entry on it. Maven
     * cannot run the main class of one module in the invocation that builds the reactor (`exec:java` binds to every
     * module `-am` builds), and a separate `mvn exec:java` without `-am` needs the siblings installed. The `java` is
     * the JDK Maven runs under.
     *
     * [RunRequest.options] `env` goes to the process: the Surefire fork and `java` inherit it. `workingDirectory`
     * reaches `java` only; Surefire runs in the module directory and takes no other from the command line.
     */
    @OptIn(LowLevelLocalMachineAccess::class)
    override fun run(request: RunRequest): Resource<RunHandle> {
        request.extensions.firstOrNull()?.let {
            return resourceOf(failedRunHandle(UnsupportedRunException("Maven does not support ${it::class.simpleName}")))
        }
        val options = request.options
        val debugPort = if (request.debug) freePort() else null
        val vmArgs = options.vmArgs + listOfNotNull(debugPort?.let(::jdwpAgent))
        val debuggerReady = listOfNotNull(debugPort?.let { RunTaskEvent.DebuggerReady("127.0.0.1", it) })
        return when (val task = request.task) {
            is RunTask.Build -> mavenProcess(mavenArgs(task, options)).toRunHandle()
            is RunTask.Test -> {
                val warning = options.workingDirectory?.let {
                    RunTaskEvent.SystemOutput("Maven ignores the working directory '$it': the tests run in the module directory")
                }
                listOf(mavenProcess(mavenArgs(task, options, argLine = surefireArgLine(vmArgs)), extraEnvironment = options.env))
                    .toRunHandle()
                    .map { handle ->
                        object : RunHandle by handle {
                            override val events: Flow<RunTaskEvent> = flow {
                                warning?.let { emit(it) }
                                debuggerReady.forEach { emit(it) }
                                emitAll(handle.events)
                            }
                        }
                    }
            }
            is RunTask.Run -> {
                val classpathFile = createTempFile("maven-classpath", ".txt")
                val java = Path.of(requireNotNull(javaHome())) / "bin" / if (OS.CURRENT == OS.Windows) "java.exe" else "java"
                // The `java` command is assembled after `mvn` has written the classpath file; `between` runs then.
                val javaProcess = ProcessBuilder().apply {
                    environment().putEnvironment(options.env)
                    directory((options.workingDirectory?.let(Path::of) ?: parameters.projectDirectory).toFile())
                }
                listOf(mavenProcess(mavenArgs(task, options, classpathFile = classpathFile)), javaProcess)
                    .toRunHandle(between = { _ ->
                        javaProcess.command(mavenJavaArgs(java, parameters.projectDirectory, task, options, vmArgs, classpathFile.readText()))
                        debuggerReady
                    })
                    .map { handle ->
                        object : RunHandle by handle {
                            override val events: Flow<RunTaskEvent> = handle.events.onCompletion { withContext(Dispatchers.IO) { classpathFile.delete() } }
                        }
                    }
            }
        }
    }


    private suspend fun runMavenPluginGoal(
        javaHome: String?,
        pomFile: Path,
        pluginGoal: String,
        events: SendChannel<ImportEvent>,
        additionalParams: List<String> = emptyList(),
        options: WorkspaceImportOptions = WorkspaceImportOptions.EMPTY,
    ): MavenRunResult {
        return runGoal(
            javaHome, pomFile,
            "com.jetbrains.ls:imports-maven-plugin:$pluginGoal",
            events, additionalParams, options
        )
    }

    private suspend fun runGoal(
        javaHome: String?,
        pomFile: Path,
        goal: String,
        events: SendChannel<ImportEvent>,
        additionalParams: List<String> = emptyList(),
        options: WorkspaceImportOptions = WorkspaceImportOptions.EMPTY,
    ): MavenRunResult {
        // Per-project `system-properties` are forwarded to the build as `-Dkey=value`.
        val extraSystemProps = options.systemProperties.map { (key, value) -> "-D$key=$value" }
        // `-P` activates the configured profiles, so the goal sees the same effective model as a manual build.
        val profileParams = if (options.profiles.isEmpty()) emptyList() else listOf("-P", options.profiles.joinToString(","))
        val workspaceJsonFile = createTempFile("workspace", ".json")
        try {
            val command = listOf(
                goal,
                "-f",
                pomFile.toString(),
                "-DoutputFile=${workspaceJsonFile.toAbsolutePath()}",
                // Read by the `model-with-deps` goal: false skips the `sources` and `javadoc` classifiers.
                "-DdownloadAdditionalArtifacts=${options.downloadAdditionalArtifacts}",
                "-Denforcer.skip=true",
                "-DskipTests=true",
                "-Dmaven.enforcer.skip=true",
                "-Denforcer.skip=true",
                "-Dair.check.skip-enforcer=true"

            )
            mavenProcess(command + extraSystemProps + profileParams + additionalParams, javaHome)
                .apply {
                    if (System.getProperty("maven.importer.debug").toBoolean()) {
                        val agentLibOpt = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005"
                        val currentMavenOpts = environment()["MAVEN_OPTS"]
                        environment()["MAVEN_OPTS"] = if (currentMavenOpts.isNullOrEmpty()) agentLibOpt else "$currentMavenOpts $agentLibOpt"
                    }
                }
                .runWithErrorReporting("Maven", events)

            return SuccessResult(workspaceJsonFile.inputStream().use<InputStream, WorkspaceData> { stream ->
                @OptIn(ExperimentalSerializationApi::class)
                Json.decodeFromStream<WorkspaceData>(stream)

            })
        } catch (e: SerializationException) {
            return ErrorResult(
                WorkspaceException(
                    "Error parsing workspace.json",
                    "Error parsing workspace.json:\n ${e.message ?: e.stackTraceToString()}",
                    e
                )
            )
        } catch (e: WorkspaceImportException) {
            return ErrorResult(e)
        } finally {
            workspaceJsonFile.delete()
        }

    }

    /**
     * Installs the import plugin into the local repository, so the model goals can run it.
     *
     * The goal runs inside the project, because Maven reads `.mvn/maven.config` and `.mvn/settings.xml`
     * from the base directory of the `-f` pom, and those carry the mirror and the local repository. The
     * project's own pom is used first, so the step resolves the same maven-install-plugin version the
     * project resolves anyway, and an air-gapped build needs nothing beyond the artifacts the project needs.
     *
     * A project may also configure maven-install-plugin itself, and that configuration then applies to this
     * goal: ThingsBoard sets `<file>` to a `.deb` path that no build produces, which fails the step (CLI-128).
     * The retry runs in an empty stub project in the same directory: no project configuration applies, the
     * base directory is still the project's, and it costs a second Maven start only where the first fails.
     */
    private suspend fun installMavenPlugin(
        execPath: Path?,
        javaHome: String?,
        projectDirectory: Path,
        pomFile: Path,
        events: SendChannel<ImportEvent>,
        additionalParams: List<String> = emptyList(),
        options: WorkspaceImportOptions = WorkspaceImportOptions.EMPTY,
    ) {
        val pomResourcePath = "/META-INF/maven/com.jetbrains.ls/imports.maven.plugin/pom.xml"
        val pluginJar = PathManager.getResourceRoot(this::class.java, pomResourcePath)
            ?: error("Corrupted installation: maven plugin jar not found")

        val pluginPom = javaClass.getResource(pomResourcePath)?.readText()?.takeIf { it.isNotEmpty() }
            ?: error("Corrupted installation: maven plugin pom.xml not found")

        val mavenPluginPomFile = createTempFile("mavenPlugin-pom", ".xml")
        val mavenUserHomeProperty = System.getProperty(LSP_MAVEN_PROJECT_MAVEN_USER_HOME_PROPERTY)
        val mavenOpts = System.getProperty(LSP_MAVEN_PROJECT_MAVEN_OPTS_PROPERTY)
        val pathPrepend = System.getProperty(LSP_MAVEN_PROJECT_PATH_PREPEND_PROPERTY)
        try {
            mavenPluginPomFile.writeText(pluginPom)
            suspend fun install(projectPom: Path) = ProcessBuilder(
                listOf(
                    execPath.toString(),
                    "install:install-file",
                    "-f",
                    projectPom.toString(),
                    "-Dfile=$pluginJar",
                    "-DpomFile=$mavenPluginPomFile",
                    "-DgroupId=com.jetbrains.ls",
                    "-DartifactId=imports-maven-plugin",
                    "-Dversion=0.99",
                    "-Dpackaging=maven-plugin"
                ) + additionalParams
            )
                .apply {
                    // ponytail: start from a clean env so the analyzer's own vars (e.g. JDK9+ JAVA_TOOL_OPTIONS=-Xlog) don't leak into a possibly-JDK8 Maven JVM.
                    environment().clear()
                    javaHome?.let {
                        environment()["JAVA_HOME"] = it
                    }
                    mavenUserHomeProperty?.let {
                        environment()["MAVEN_USER_HOME"] = it
                    }
                    mavenOpts?.let {
                        environment()["MAVEN_OPTS"] = it
                    }
                    pathPrepend?.let {
                        prependToPath(environment(), it)
                    }
                    // Per-project `env` is applied last so it wins over the defaults above.
                    environment().putEnvironment(options.environment)
                }
                .directory(projectDirectory.toFile())
                .runWithErrorReporting("Maven", events)

            try {
                install(pomFile)
            }
            catch (e: WorkspaceImportException) {
                LOG.info("Installing the Maven plugin failed with the project's pom, retrying in a stub project", e)
                val stubProjectPomFile = createTempFile(projectDirectory, "mavenPluginInstall-pom", ".xml")
                try {
                    stubProjectPomFile.writeText(STUB_PROJECT_POM)
                    install(stubProjectPomFile)
                }
                catch (stubFailure: WorkspaceImportException) {
                    // The stub project is ours and holds no user configuration. A failure to install the plugin
                    // into it is our problem, not the user's.
                    throw WorkspaceException(
                        "Failed to install the Maven import plugin",
                        "Failed to install the Maven import plugin: ${stubFailure.logMessage ?: stubFailure.message}",
                        stubFailure,
                    )
                }
                finally {
                    stubProjectPomFile.delete()
                }
            }
        } finally {
            mavenPluginPomFile.delete()
        }
    }

    /** The empty project the install step retries in. `pom` packaging binds the fewest default plugins. */
    private val STUB_PROJECT_POM: String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.jetbrains.ls</groupId>
          <artifactId>imports-maven-plugin-install</artifactId>
          <version>0.99</version>
          <packaging>pom</packaging>
        </project>
    """.trimIndent()

    private fun prependToPath(environment: MutableMap<String, String>, path: String) {
        val pathKey = environment.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
        val currentPath = environment[pathKey]
        environment[pathKey] = if (currentPath.isNullOrEmpty()) path else "$path${File.pathSeparator}$currentPath"
    }
}

/** The tool's own ask; it carries no data, a Maven re-import is always full. */
private object MavenReimport : ImportRequest

private const val MAVEN_EXTERNAL_SYSTEM_ID = "MAVEN"

/**
 * Records [javaHome] as [com.jetbrains.ls.imports.api.importJavaHome] on every module the Maven importer produced.
 * A `null` or blank [javaHome] records nothing, see [stampBuildToolJavaHome].
 */
@ApiStatus.Internal
fun MutableEntityStorage.stampMavenJavaHome(javaHome: String?) {
    stampBuildToolJavaHome(MAVEN_EXTERNAL_SYSTEM_ID, javaHome)
}
