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
import org.gradle.util.GradleVersion
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import kotlin.io.path.createTempFile
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.writeText
import kotlin.use

object GradleToolingApiHelper {

    private const val JAVA_11: Int = 11
    private const val JAVA_17: Int = 17

    private val GRADLE_8_14: GradleVersion = GradleVersion.version("8.14")
    private val GRADLE_7_3: GradleVersion = GradleVersion.version("7.3")
    private val GRADLE_5_0: GradleVersion = GradleVersion.version("5.0")

    private val LOG = logger<GradleToolingApiHelper>()

    fun findTheMostCompatibleJdk(project: Project, projectDirectory: Path): String? {
        val gradleVersion = guessGradleVersion(projectDirectory) ?: return null
        val javaSdkType = SimpleJavaSdkType.getInstance()
        val suggestedJavaPath = JavaHomeFinder.suggestHomePaths(project)
            .map { Pair(JavaVersion.tryParse(javaSdkType.getVersionString(it)), it) }
            .filter { it.first != null }
            .sortedByDescending { it.first }
            .first { gradleVersion.isCompatibleWithJava(it.first) }
            .second
        LOG.info("Gradle Tooling API will use Java located in $suggestedJavaPath")
        return suggestedJavaPath
    }

    fun getInitScriptPathString(): String {
        val pluginResourcePath = "/META-INF/gradle-plugins/imports-gradle-plugin.properties"
        val pluginJar = PathManager.getResourceRoot(this::class.java, pluginResourcePath)
            ?: error("Corrupted installation: gradle plugin .properties not found")
        val initScriptFile = createTempFile("lsp-gradle-init", ".gradle")
        initScriptFile.writeText(
            """
            initscript {
                dependencies {
                    repositories {
                        mavenCentral()
                    }
                    classpath(files("${PathUtil.toSystemIndependentName(pluginJar)}"))
                }
            }
            
            apply plugin: com.jetbrains.ls.imports.gradle.IdeaGradleLspPlugin
            """.trimIndent()
        )
        return initScriptFile.toString()
    }

    private fun GradleVersion.isCompatibleWithJava(javaVersion: JavaVersion?): Boolean {
        if (javaVersion == null || !javaVersion.isAtLeast(8)) {
            return false
        }
        if (compareTo(GRADLE_5_0) <= 0 && javaVersion.feature <= JAVA_11) {
            return true
        }
        if (compareTo(GRADLE_8_14) < 0 && javaVersion.feature < JAVA_17) {
            return false
        }
        if (compareTo(GRADLE_7_3) >= 0 && javaVersion.feature == JAVA_17) {
            return true
        }
        return false
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