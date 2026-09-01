// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")

package com.jetbrains.ls.imports.maven

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.jps.entities.exModuleOptions
import com.intellij.platform.workspace.jps.entities.modifyExternalSystemModuleOptionsEntity
import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.impl.url.toVirtualFileUrl
import com.intellij.platform.workspace.storage.url.VirtualFileUrlManager
import com.intellij.util.io.delete
import com.intellij.util.system.OS
import com.jetbrains.ls.imports.api.WorkspaceEntitySource
import com.jetbrains.ls.imports.api.WorkspaceImportException
import com.jetbrains.ls.imports.api.WorkspaceImportOptions
import com.jetbrains.ls.imports.api.WorkspaceImportParameters
import com.jetbrains.ls.imports.api.WorkspaceImportProgressReporter
import com.jetbrains.ls.imports.api.WorkspaceImporter
import com.jetbrains.ls.imports.json.JsonWorkspaceImporter
import com.jetbrains.ls.imports.json.WorkspaceData
import com.jetbrains.ls.imports.json.importWorkspaceData
import com.jetbrains.ls.imports.utils.fixMissingProjectSdk
import com.jetbrains.ls.imports.utils.runWithErrorReporting
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.File
import java.io.InputStream
import java.nio.file.Path
import kotlin.io.path.createTempFile
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.writeText

private val LOG = logger<MavenWorkspaceImporter>()

object MavenWorkspaceImporter : WorkspaceImporter {
    /** The Maven distribution to import with, when the project has no wrapper. */
    const val JB_MAVEN_HOME_PROPERTY: String = "JB_MAVEN_HOME"

    /** The JDK to run Maven under. Named so that callers scoping these properties do not have to spell them. */
    const val JB_MAVEN_JAVA_HOME_PROPERTY: String = "JB_MAVEN_JAVA_HOME"

    const val LSP_MAVEN_PROJECT_OFFLINE_PROPERTY: String = "com.jetbrains.ls.imports.maven.offline"
    const val LSP_MAVEN_PROJECT_MAVEN_USER_HOME_PROPERTY: String = "com.jetbrains.ls.imports.maven.mavenUserHome"
    const val LSP_MAVEN_PROJECT_MAVEN_OPTS_PROPERTY: String = "com.jetbrains.ls.imports.maven.opts"
    const val LSP_MAVEN_PROJECT_PATH_PREPEND_PROPERTY: String = "com.jetbrains.ls.imports.maven.path.prepend"

    /**
     * Skips the `model-process-sources` goal, whose forked `generate-sources` lifecycle actually runs the project's
     * code generators. The import gets faster and nothing is written to `target/`, at the cost of the source roots
     * that only become visible after the generating plugins have run.
     *
     * The environment variable is for clients that launch the server but do not control its command line
     * (the property wins when both are set).
     */
    const val LSP_MAVEN_PROJECT_SKIP_GENERATE_SOURCES_PROPERTY: String = "com.jetbrains.ls.imports.maven.skipGenerateSources"
    const val LSP_MAVEN_PROJECT_SKIP_GENERATE_SOURCES_ENV: String = "INTELLIJ_MAVEN_SKIP_GENERATE_SOURCES"


    fun useMavenAndJava(mavenHome: Path, javaHome: Path) {
        System.setProperty(JB_MAVEN_HOME_PROPERTY, mavenHome.toString())
        System.setProperty(JB_MAVEN_JAVA_HOME_PROPERTY, javaHome.toString())
    }

    override fun canImportWorkspace(projectFileOrDirectory: Path): Boolean {
        // A file is importable when its name is a recognizable pom spelling (`mvn -f dev_pom.xml`-style
        // non-standard names included); a directory when it holds the conventional pom.
        return if (projectFileOrDirectory.isRegularFile()) isPomFileName(projectFileOrDirectory.name)
               else (projectFileOrDirectory / "pom.xml").exists()
    }

    private fun isPomFileName(name: String): Boolean =
        name.endsWith("pom.xml") || name.startsWith("pom.") || name.endsWith(".pom")

    override suspend fun importWorkspace(
        project: Project,
        parameters: WorkspaceImportParameters,
        virtualFileUrlManager: VirtualFileUrlManager,
        progress: WorkspaceImportProgressReporter,
    ): EntityStorage? {
        val projectDirectory = parameters.projectDirectory
        val defaultSdkPath = parameters.defaultSdkPath
        val options = parameters.options
        // A configured project may point directly at a non-standard build file (`mvn -f dev_pom.xml`);
        // auto-detected folders arrive as directories and use the conventional pom.xml.
        val pomFile = parameters.projectFileOrDirectory.let { if (it.isRegularFile()) it else it / "pom.xml" }
        if (!pomFile.exists()) return null

        LOG.info("Importing Maven project from: $projectDirectory (pom: $pomFile)")
        val wrapper = projectDirectory / (if (OS.CURRENT == OS.Windows) "mvnw.cmd" else "mvnw")
        val mavenHome = System.getProperty(JB_MAVEN_HOME_PROPERTY)?.let { Path.of(it) }
        // A `java-home` configured for this project wins over the JVM property and the ambient environment.
        val javaHome = options.javaHome?.toString()
            ?: System.getProperty(JB_MAVEN_JAVA_HOME_PROPERTY)
            ?: if (System.getenv()["JAVA_HOME"] == null) System.getProperty("java.home") else null
        val execPath = when {
            wrapper.exists() -> wrapper
            mavenHome != null -> mavenHome / "bin" / if (OS.CURRENT == OS.Windows) "mvn.cmd" else "mvn"
            else -> Path.of(if (OS.CURRENT == OS.Windows) "mvn.cmd" else "mvn")
        }
        LOG.info("Using Maven: $execPath (JAVA_HOME=${javaHome ?: "unspecified"})")


        val offlineOpts = if (System.getProperty(LSP_MAVEN_PROJECT_OFFLINE_PROPERTY).toBoolean()) listOf("-o") else emptyList()
        progress.progressStatus("Installing Maven plugin...")
        installMavenPlugin(execPath, javaHome, projectDirectory, pomFile, progress, offlineOpts, options)


        progress.progressStatus("Collecting Maven model...")
        val modelWithDeps = runMavenPluginGoal(execPath, javaHome, projectDirectory, pomFile, "model-with-deps", progress, offlineOpts, options)
        val modelWithGeneratedSources = if (skipGenerateSources()) {
            LOG.info("Skipping source generation: $LSP_MAVEN_PROJECT_SKIP_GENERATE_SOURCES_PROPERTY is set")
            null
        } else {
            progress.progressStatus("Generating sources...")
            runMavenPluginGoal(execPath, javaHome, projectDirectory, pomFile, "model-process-sources", progress, offlineOpts, options)
        }
        progress.progressStatus("Maven model collected, commiting...")
        val mergedModels = mergeResults(modelWithDeps, modelWithGeneratedSources)

        when (mergedModels) {
            is ErrorResult -> throw mergedModels.e
            is SuccessResult -> return MutableEntityStorage.create().apply {
                importWorkspaceData(
                    JsonWorkspaceImporter.postProcessWorkspaceData(
                        mergedModels.workspaceData,
                        projectDirectory,
                        progress
                    ),
                    projectDirectory,
                    WorkspaceEntitySource(projectDirectory.toVirtualFileUrl(virtualFileUrlManager)),
                    virtualFileUrlManager, false,
                    "MAVEN"
                )
                // The launch/build path re-runs Maven from the module's import root and lets Maven resolve
                // the pom from the working directory; record the build file the import actually used so a
                // non-standard pom name (`mvn -f dev_pom.xml`) reaches those invocations too.
                entities(ModuleEntity::class.java).mapNotNull { it.exModuleOptions }.toList().forEach {
                    modifyExternalSystemModuleOptionsEntity(it) { rootProjectPath = pomFile.toString() }
                }
                fixMissingProjectSdk(options.javaHome ?: defaultSdkPath, virtualFileUrlManager)
            }
        }
    }

    private fun skipGenerateSources(): Boolean =
        (System.getProperty(LSP_MAVEN_PROJECT_SKIP_GENERATE_SOURCES_PROPERTY)
         ?: System.getenv(LSP_MAVEN_PROJECT_SKIP_GENERATE_SOURCES_ENV)).toBoolean()

    private suspend fun runMavenPluginGoal(
        execPath: Path?,
        javaHome: String?,
        projectDirectory: Path,
        pomFile: Path,
        pluginGoal: String,
        progress: WorkspaceImportProgressReporter,
        additionalParams: List<String> = emptyList(),
        options: WorkspaceImportOptions = WorkspaceImportOptions.EMPTY,
    ): MavenRunResult {
        return runGoal(
            execPath, javaHome, projectDirectory, pomFile,
            "com.jetbrains.ls:imports-maven-plugin:$pluginGoal",
            progress, additionalParams, options
        )
    }

    private suspend fun runGoal(
        execPath: Path?,
        javaHome: String?,
        projectDirectory: Path,
        pomFile: Path,
        goal: String,
        progress: WorkspaceImportProgressReporter,
        additionalParams: List<String> = emptyList(),
        options: WorkspaceImportOptions = WorkspaceImportOptions.EMPTY,
    ): MavenRunResult {

        val mavenUserHomeProperty = System.getProperty(LSP_MAVEN_PROJECT_MAVEN_USER_HOME_PROPERTY)
        val mavenOpts = System.getProperty(LSP_MAVEN_PROJECT_MAVEN_OPTS_PROPERTY)
        val pathPrepend = System.getProperty(LSP_MAVEN_PROJECT_PATH_PREPEND_PROPERTY)
        // Per-project `system-properties` are forwarded to the build as `-Dkey=value`.
        val extraSystemProps = options.systemProperties.map { (key, value) -> "-D$key=$value" }
        val workspaceJsonFile = createTempFile("workspace", ".json")
        try {
            val command = listOf(
                execPath.toString(),
                goal,
                "-f",
                pomFile.toString(),
                "-DoutputFile=${workspaceJsonFile.toAbsolutePath()}",
                "-Denforcer.skip=true",
                "-DskipTests=true",
                "-Dmaven.enforcer.skip=true",
                "-Denforcer.skip=true",
                "-Dair.check.skip-enforcer=true"

            )
            ProcessBuilder(command + extraSystemProps + additionalParams)
                .apply {
                    // ponytail: start from a clean env so the analyzer's own vars (e.g. JDK9+ JAVA_TOOL_OPTIONS=-Xlog) don't leak into a possibly-JDK8 Maven JVM.
                    environment().clear()
                    javaHome?.let {
                        environment()["JAVA_HOME"] = it
                    }
                    if (System.getProperty("maven.importer.debug").toBoolean()) {
                        val agentLibOpt = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005"
                        val currentMavenOpts = environment()["MAVEN_OPTS"]
                        environment()["MAVEN_OPTS"] = if (currentMavenOpts.isNullOrEmpty()) {
                            agentLibOpt
                        } else {
                            "$currentMavenOpts $agentLibOpt"
                        }
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
                    environment().putAll(options.environment)
                }
                .directory(projectDirectory.toFile())
                .runWithErrorReporting("Maven", progress)

            return SuccessResult(workspaceJsonFile.inputStream().use<InputStream, WorkspaceData> { stream ->
                @OptIn(ExperimentalSerializationApi::class)
                Json.decodeFromStream<WorkspaceData>(stream)

            })
        } catch (e: SerializationException) {
            return ErrorResult(
                WorkspaceImportException(
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
        progress: WorkspaceImportProgressReporter,
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
                    environment().putAll(options.environment)
                }
                .directory(projectDirectory.toFile())
                .runWithErrorReporting("Maven", progress)

            try {
                install(pomFile)
            }
            catch (e: WorkspaceImportException) {
                LOG.info("Installing the Maven plugin failed with the project's pom, retrying in a stub project", e)
                val stubProjectPomFile = createTempFile(projectDirectory, "mavenPluginInstall-pom", ".xml")
                try {
                    stubProjectPomFile.writeText(STUB_PROJECT_POM)
                    install(stubProjectPomFile)
                } finally {
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
