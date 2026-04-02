// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.SimpleJavaSdkType
import com.intellij.openapi.projectRoots.impl.JavaHomeFinder
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.util.PathUtil
import com.intellij.util.lang.JavaVersion
import com.jetbrains.ls.imports.gradle.GradleWorkspaceImporter.LSP_GRADLE_JAVA_HOME_PROPERTY
import com.jetbrains.ls.imports.gradle.compatibility.GradleJvmCompatibilityChecker
import org.gradle.util.GradleVersion
import org.jetbrains.kotlin.gradle.idea.proto.generated.tcs.IdeaKotlinDependencyProtoKt
import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinDependency
import org.jetbrains.kotlin.tooling.core.Extras
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.createTempFile
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.writeText
import kotlin.use

object GradleToolingApiHelper {

    private val LOG = logger<GradleToolingApiHelper>()

    fun findTheMostCompatibleJdk(project: Project, projectDirectory: Path): String? {
        val explicitJavaHomeValue = System.getProperty(LSP_GRADLE_JAVA_HOME_PROPERTY)
        if (explicitJavaHomeValue != null) {
            return explicitJavaHomeValue
        }
        val gradleVersion = guessGradleVersion(projectDirectory) ?: return null
        val javaSdkType = SimpleJavaSdkType.getInstance()
        val suggestedJavaPath = JavaHomeFinder.suggestHomePaths(project)
            .map { Pair(JavaVersion.tryParse(javaSdkType.getVersionString(it)), it) }
            .filter { it.first != null }
            .sortedByDescending { it.first }
            .first { GradleJvmCompatibilityChecker.isSupported(gradleVersion, it.first!!) }
            .second
        LOG.info("Gradle Tooling API will use Java located in $suggestedJavaPath")
        return suggestedJavaPath
    }

    fun getInitScriptPath(): Path {
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
}