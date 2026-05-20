// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.SimpleJavaSdkType
import com.intellij.openapi.projectRoots.impl.JavaHomeFinder
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.openapi.util.io.toNioPathOrNull
import com.intellij.util.PathUtil
import com.intellij.util.SystemProperties
import com.intellij.util.containers.addIfNotNull
import com.intellij.util.io.delete
import com.intellij.util.lang.JavaVersion
import com.jetbrains.ls.imports.api.WorkspaceImportException
import com.jetbrains.ls.imports.api.WorkspaceImportProgressReporter
import com.jetbrains.ls.imports.gradle.compatibility.GradleJvmCompatibilityChecker
import com.jetbrains.ls.imports.gradle.util.GradleOutputStream
import org.gradle.tooling.BuildActionExecuter
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.events.OperationType
import org.gradle.tooling.events.ProgressEvent
import org.gradle.util.GradleVersion
import org.jetbrains.kotlin.gradle.idea.proto.generated.tcs.IdeaKotlinDependencyProtoKt
import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinDependency
import org.jetbrains.kotlin.tooling.core.Extras
import java.lang.System.getProperty
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.LinkedList
import java.util.Properties
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempFile
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.writeText
import kotlin.use

object GradleToolingApiHelper {

    private val LOG = logger<GradleToolingApiHelper>()

    const val LSP_GRADLE_JAVA_HOME_PROPERTY: String = "com.jetbrains.ls.imports.gradle.java.home"

    private const val LSP_GRADLE_PROJECT_OFFLINE_PROPERTY: String = "com.jetbrains.ls.imports.gradle.offline"
    private const val LSP_GRADLE_PROJECT_GRADLE_USER_HOME_PROPERTY: String = "com.jetbrains.ls.imports.gradle.gradleUserHome"
    private const val LSP_GRADLE_PROJECT_SELF_CONTAINED_INIT_SCRIPT: String = "com.jetbrains.ls.imports.gradle.selfContainedInitScript"
    private const val LSP_GRADLE_PROJECT_SELF_CONTAINED_PROXY_URL_PROPERTY: String = "com.jetbrains.ls.imports.gradle.selfContainedProxyUrl"

    private const val IDEA_ACTIVE_PROPERTY: String = "idea.active"
    private const val IDEA_SYNC_ACTIVE_PROPERTY: String = "idea.sync.active"
    private const val KOTLIN_LSP_IMPORT_PROPERTY: String = "com.jetbrains.ls.imports.gradle"
    private val IMPORTER_PROPERTIES: Map<String, String> = mapOf(
        // This imitates how IntelliJ invokes gradle during sync.
        // Some builds/plugins depend on this property to configure their build for sync.
        IDEA_SYNC_ACTIVE_PROPERTY to "true",
        IDEA_ACTIVE_PROPERTY to "true",
        // Since this is not actually IntelliJ, offer an alternative identification.
        KOTLIN_LSP_IMPORT_PROPERTY to "true",
    )

    fun findTheMostCompatibleJdk(project: Project, projectDirectory: Path): String? {
        val explicitJavaHomeValue = getProperty(LSP_GRADLE_JAVA_HOME_PROPERTY)
        if (explicitJavaHomeValue != null) {
            return explicitJavaHomeValue
        }
        val gradleVersion = guessGradleVersion(projectDirectory) ?: return null
        val suggestedJavaPath = suggestJavaPath(project, gradleVersion) ?: tryJavaFromJavaHome(gradleVersion)

        if (suggestedJavaPath == null) {
            throw WorkspaceImportException(
                "Unable to find compatible JDK for Gradle execution. You need to have a JDK compatible with $gradleVersion.",
                """
                    |There are no compatible JDKs found on the machine. Unable to run Gradle.
                    |Gradle version: $gradleVersion
                    |Checked Java paths: ${JavaHomeFinder.suggestHomePaths(project)}
                """.trimMargin()
            )
        }

        LOG.info("Gradle Tooling API will use Java located in $suggestedJavaPath")
        return suggestedJavaPath
    }

    fun <T> BuildActionExecuter<T>.addInitScripts(initScripts: Iterable<Path>): BuildActionExecuter<T> {
        initScripts.forEach { script ->
            LOG.debug("Appending $script to Gradle execution")
            addArguments("--init-script", script.absolutePathString())
        }
        return this
    }

    fun <T> BuildActionExecuter<T>.prepareForExecution(): BuildActionExecuter<T> {
        addArguments("--stacktrace")
        withSystemProperties(IMPORTER_PROPERTIES)
        if (getProperty(LSP_GRADLE_PROJECT_OFFLINE_PROPERTY)?.toBoolean() == true) {
            setEnvironmentVariables(
                mapOf(
                    "SELF_CONTAINED_PROXY_URL" to getProperty(LSP_GRADLE_PROJECT_SELF_CONTAINED_PROXY_URL_PROPERTY),
                    "GRADLE_USER_HOME" to getProperty(LSP_GRADLE_PROJECT_GRADLE_USER_HOME_PROPERTY)
                )
            )
        }
        withCancellationToken(GradleConnector.newCancellationTokenSource().token())
        return this
    }

    fun <T> BuildActionExecuter<T>.configureLogging(progress: WorkspaceImportProgressReporter): BuildActionExecuter<T> {
        setStandardOutput(GradleOutputStream { line -> progress.onStdOutput(line) })
        setStandardError(GradleOutputStream { line -> progress.onErrorOutput(line) })
        addProgressListener(
            GradleProgressListener { line -> progress.progressStatus(line) },
            setOf(OperationType.GENERIC, OperationType.FILE_DOWNLOAD, OperationType.PROJECT_CONFIGURATION)
        )
        return this
    }

    fun GradleConnector.withCustomGradleHome(): GradleConnector {
        val customGradleUserHomePropertyValue = getProperty(LSP_GRADLE_PROJECT_GRADLE_USER_HOME_PROPERTY) ?: return this
        if (customGradleUserHomePropertyValue.isNotBlank()) {
            val gradleUserHome = Path.of(customGradleUserHomePropertyValue)
            check(gradleUserHome.exists())
            useGradleUserHomeDir(gradleUserHome.toFile())
        }
        return this
    }

    fun <T> withDaemonInitScripts(action: (Iterable<Path>) -> T): T {
        val initScripts = LinkedList<Path>()
        initScripts.add(getLspGradlePluginInitScript())
        if (getProperty(LSP_GRADLE_PROJECT_OFFLINE_PROPERTY)?.toBoolean() == true) {
            val selfContainedInitScript = getProperty(LSP_GRADLE_PROJECT_SELF_CONTAINED_INIT_SCRIPT)?.toNioPathOrNull()
            initScripts.addIfNotNull(selfContainedInitScript)
        }
        try {
            return action(initScripts)
        } finally {
            initScripts.forEach {
                it.delete()
            }
        }
    }

    private fun getLspGradlePluginInitScript(): Path {
        val pluginResourcePath = "/META-INF/gradle-plugins/imports-gradle-plugin.properties"

        val pluginJar = PathManager.getResourceRoot(this::class.java, pluginResourcePath)
            ?: error("Corrupted installation: gradle plugin .properties not found")

        /**
         * Marker classes used to build an additional classpath for the plugin.
         * This shall contain classes of jars necessary by the init script -(and its injected IdeaGradleLspPlugin)
         */
        val additionalPluginClasspathMarkers = setOf(
            IdeaKotlinDependency::class.java,
            IdeaKotlinDependencyProtoKt::class.java,
            Extras::class.java
        )

        val additionalPluginClasspath = additionalPluginClasspathMarkers
            .map { clazz -> PathManager.getResourceRoot(this::class.java.classLoader, clazz.name.replace(".", "/").plus(".class")) }
            .map { path -> PathUtil.toSystemIndependentName(path) }
            .toSet()

        return createTempFile("lsp-gradle-init", ".gradle").also {
            it.writeText(
                buildString {
                    appendLine("|initscript {")
                    appendLine("|    dependencies {")
                    appendLine("|        classpath files('${PathUtil.toSystemIndependentName(pluginJar)}')")
                    additionalPluginClasspath.forEach { jarPath ->
                        appendLine("|        classpath files('${jarPath.escapeStringLiteral()}')")
                    }
                    appendLine("|    }")
                    appendLine("|}")
                    appendLine("|apply plugin: com.jetbrains.ls.imports.gradle.IdeaGradleLspPlugin")
                }.trimMargin()
            )
        }
    }

    private fun suggestJavaPath(project: Project, gradleVersion: GradleVersion): String? {
        val javaSdkType = SimpleJavaSdkType.getInstance()
        return JavaHomeFinder.suggestHomePaths(project)
            .map { Pair(JavaVersion.tryParse(javaSdkType.getVersionString(it)), it) }
            .filter { it.first != null }
            .sortedByDescending { it.first }
            .firstOrNull { GradleJvmCompatibilityChecker.isSupported(gradleVersion, it.first!!) }
            ?.second
    }

    private fun tryJavaFromJavaHome(gradleVersion: GradleVersion): String? {
        val javaHome = SystemProperties.getJavaHome()
        val javaHomeVersionString = SimpleJavaSdkType.getInstance()
            .getVersionString(javaHome)
        val javaHomeVersion = JavaVersion.tryParse(javaHomeVersionString) ?: return null
        if (GradleJvmCompatibilityChecker.isSupported(gradleVersion, javaHomeVersion)) {
            LOG.info("A Java distribution defined by the JAVA_HOME environment variable will be used for Gradle execution")
            return javaHome
        }
        return null
    }

    private fun String.escapeStringLiteral(): String {
        return replace("\\", "\\\\")
            .replace("'", "\\'")
    }

    private fun guessGradleVersion(projectDirectory: Path): GradleVersion? {
        val propertiesPath = findWrapperProperties(projectDirectory) ?: return null
        val properties = readGradleProperties(propertiesPath) ?: return null
        val url: URI = properties["distributionUrl"]
            .let {
                return@let try {
                    URI.create(it as String)
                } catch (_: Exception) {
                    null
                }
            } ?: return null
        val versionString = url.path.split("/")
            .last()
            .removeSuffix("-bin.zip")
            .removeSuffix("-all.zip")
            .removePrefix("gradle-")
        return try {
            GradleVersion.version(versionString)
        } catch (_: Exception) {
            return null
        }
    }

    private fun findWrapperProperties(root: Path): Path? {
        val gradleDir = if (root.isRegularFile()) root.resolveSibling("gradle") else root.resolve("gradle")
        if (!gradleDir.isDirectory()) {
            return null
        }
        val wrapperDir = gradleDir.resolve("wrapper")
        if (!wrapperDir.isDirectory()) {
            return null
        }
        try {
            Files.list(wrapperDir)
                .use { pathsStream ->
                    val candidates = pathsStream
                        .filter {
                            FileUtilRt.extensionEquals(it.fileName.toString(), "properties") && it.isRegularFile()
                        }
                        .toList()
                    if (candidates.size != 1) {
                        return null
                    }
                    return candidates.first()
                }
        } catch (_: Exception) {
            return null
        }
    }

    private fun readGradleProperties(propertiesFile: Path): Properties? {
        return try {
            Files.newBufferedReader(propertiesFile, StandardCharsets.ISO_8859_1)
                .use { reader -> Properties().apply { load(reader) } }
        } catch (_: Exception) {
            null
        }
    }

    private class GradleProgressListener(private val lineConsumer: (line: String) -> Unit) : org.gradle.tooling.events.ProgressListener {
        override fun statusChanged(event: ProgressEvent) {
            lineConsumer(event.displayName)
        }
    }
}