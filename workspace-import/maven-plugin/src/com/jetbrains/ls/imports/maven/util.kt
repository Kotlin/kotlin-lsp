// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.maven

import com.jetbrains.ls.imports.maven.model.LanguageLevels
import com.jetbrains.ls.imports.maven.model.StandardMavenModuleType
import org.apache.maven.artifact.Artifact
import org.apache.maven.model.Plugin
import org.apache.maven.project.MavenProject
import org.codehaus.plexus.util.xml.Xpp3Dom
import java.nio.file.Path
import kotlin.collections.find
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString


internal fun getAttachedJarsLibName(artifact: Artifact): String {
    val id = "${artifact.groupId}:${artifact.artifactId}:${artifact.version}"
    return "Maven: ATTACHED-JAR: $id"
}

internal fun getLanguageLevels(project: MavenProject): LanguageLevels {
    // Replicate the IDEA Maven importer behavior from MavenImportUtil and MavenProjectImportContextProvider
    val setUpSourceLevel = getSourceLanguageLevel(project)
    val setUpTestSourceLevel = getTestSourceLanguageLevel(project)
    val setUpTargetLevel = getTargetLanguageLevel(project)
    val setUpTestTargetLevel = getTestTargetLanguageLevel(project)

    val sourceLevel = getLanguageLevel(project) { setUpSourceLevel }
    val testSourceLevel = getLanguageLevel(project) { setUpTestSourceLevel }
    val targetLevel = getLanguageLevel(project) { setUpTargetLevel }
    val testTargetLevel = getLanguageLevel(project) { setUpTestTargetLevel }

    return LanguageLevels(sourceLevel, testSourceLevel, targetLevel, testTargetLevel)
}

internal fun getLanguageLevel(mavenProject: MavenProject, supplier: () -> String?): String {
    var level: String? = null

    val cfg = getPluginGoalConfiguration(mavenProject, "com.googlecode", "maven-idea-plugin", "idea")
    if (cfg != null) {
        level = cfg.getChild("jdkLevel")?.value?.trim()
        if (level != null) {
            level = when (level) {
                "JDK_1_3" -> "1.3"
                "JDK_1_4" -> "1.4"
                "JDK_1_5" -> "1.5"
                "JDK_1_6" -> "1.6"
                "JDK_1_7" -> "1.7"
                else -> level
            }
        }
    }

    if (level == null) {
        level = supplier()
    }

    if (level == null) {
        level = getDefaultLevel(mavenProject)
    }

    val feature = parseJavaFeatureNumber(level)
    if (feature != null && feature >= 11) {
        level = adjustPreviewLanguageLevel(mavenProject, level)
    }

    return level
}

internal fun getDefaultLevel(mavenProject: MavenProject): String {
    val plugin = findCompilerPlugin(mavenProject)
    if (plugin != null && plugin.version != null) {
        if (compareVersions("3.11.0", plugin.version) <= 0) {
            return "1.8"
        }
        if (compareVersions("3.9.0", plugin.version) <= 0) {
            return "1.7"
        }
        if (compareVersions("3.8.0", plugin.version) <= 0) {
            return "1.6"
        }
    }
    return "1.5"
}

internal fun compareVersions(v1: String, v2: String): Int {
    val components1 = v1.split('.').mapNotNull { it.toIntOrNull() }
    val components2 = v2.split('.').mapNotNull { it.toIntOrNull() }
    for (i in 0 until maxOf(components1.size, components2.size)) {
        val c1 = components1.getOrElse(i) { 0 }
        val c2 = components2.getOrElse(i) { 0 }
        if (c1 != c2) return c1.compareTo(c2)
    }
    return 0
}

internal fun adjustPreviewLanguageLevel(mavenProject: MavenProject, level: String): String {
    val enablePreviewProperty = mavenProject.properties.getProperty("maven.compiler.enablePreview")
    if (enablePreviewProperty.toBoolean()) {
        return "$level-preview"
    }

    val compilerPlugin =
        findCompilerPlugin(mavenProject)
    val compilerConfiguration = compilerPlugin?.configuration as? Xpp3Dom
    if (compilerConfiguration != null) {
        val enablePreviewParameter = compilerConfiguration.getChild("enablePreview")?.value?.trim()
        if (enablePreviewParameter.toBoolean()) {
            return "$level-preview"
        }

        val compilerArgs = compilerConfiguration.getChild("compilerArgs")
        if (compilerArgs != null) {
            if (isPreviewText(compilerArgs) ||
                compilerArgs.children.any { isPreviewText(it) }
            ) {
                return "$level-preview"
            }
        }
    }

    return level
}

internal fun isPreviewText(child: Xpp3Dom): Boolean {
    return "--enable-preview" == child.value?.trim()
}

internal fun parseJavaFeatureNumber(level: String?): Int? {
    if (level.isNullOrBlank()) return null
    val trimmed = level.trim()
    // Accept common forms:
    // - "1.8" / "1.7" etc
    // - "8", "11", "17"
    // - "17.0" / "17.0.1" (take the feature component)
    val parts = trimmed.split('.', '-', '_')
    if (parts.isEmpty()) return null
    return if (parts[0] == "1" && parts.size >= 2) {
        parts[1].toIntOrNull()
    } else {
        parts[0].toIntOrNull()
    }
}

internal fun getSourceLanguageLevel(project: MavenProject, executionId: String? = null): String? {
    return getCompilerProp(project, "release", executionId) ?: getCompilerProp(project, "source", executionId)
}

internal fun getTestSourceLanguageLevel(project: MavenProject): String? {
    return getCompilerProp(project, "testSource") ?: getSourceLanguageLevel(project)
}

internal fun getTargetLanguageLevel(project: MavenProject, executionId: String? = null): String? {
    return getCompilerProp(project, "release", executionId) ?: getCompilerProp(project, "target", executionId)
}

internal fun getTestTargetLanguageLevel(project: MavenProject): String? {
    return getCompilerProp(project, "testTarget") ?: getTargetLanguageLevel(project)
}

internal fun getCompilerProp(project: MavenProject, prop: String, executionId: String? = null): String? {
    if ("release" == prop || isReleaseCompilerProp(project)) {
        val release = doGetCompilerProp(project, "release", executionId)
        if (release != null) return release
    }
    return doGetCompilerProp(project, prop, executionId)
}

internal fun isReleaseCompilerProp(project: MavenProject): Boolean {
    val plugin = findCompilerPlugin(project)
    val version = plugin?.version ?: return false
    return compareVersions("3.6", version) >= 0
}

internal fun doGetCompilerProp(project: MavenProject, prop: String, executionId: String? = null): String? {
    val plugin = findCompilerPlugin(project)
        ?: return project.properties.getProperty("maven.compiler.$prop")

    if (executionId != null) {
        val execution = plugin.executions.find { it.id == executionId }
        val config = execution?.configuration as? Xpp3Dom
        if (config != null) {
            config.getChild(prop)?.value?.let { return it }
            if (prop == "source" || prop == "target") {
                config.getChild("compilerArgument")?.value?.let { arg ->
                    if (arg.startsWith("-source ") && prop == "source") return arg.substring("-source ".length).trim()
                    if (arg.startsWith("-target ") && prop == "target") return arg.substring("-target ".length).trim()
                }
            }
        }
    }

    val pluginConfig = plugin.configuration as? Xpp3Dom
    if (pluginConfig != null) {
        pluginConfig.getChild(prop)?.value?.let { return it }
        if (prop == "source" || prop == "target") {
            pluginConfig.getChild("compilerArgument")?.value?.let { arg ->
                if (arg.startsWith("-source ") && prop == "source") return arg.substring("-source ".length).trim()
                if (arg.startsWith("-target ") && prop == "target") return arg.substring("-target ".length).trim()
            }
        }
    }

    return project.properties.getProperty("maven.compiler.$prop")
}

internal fun getNonDefaultCompilerExecutions(project: MavenProject): List<String> {
    val plugin = findCompilerPlugin(project) ?: return emptyList()

    return plugin.executions
        .filter { it.id != "default-compile" && it.id != "default-testCompile" }
        .filter { (it.configuration as? Xpp3Dom)?.getChild("compileSourceRoots") != null }
        .map { it.id }
}

internal fun mainAndTestCompilerArgsDiffer(project: MavenProject): Boolean {
    val plugin = findCompilerPlugin(project) ?: return false
    val executions = plugin.executions
    if (executions == null || executions.isEmpty()) return false
    val compilerArgs = (executions.find { it.id == "default-compile" }?.configuration as? Xpp3Dom)?.getChild("compilerArgs")
    val testCompilerArgs = (executions.find { it.id == "default-testCompile"}?.configuration as? Xpp3Dom)?.getChild("compilerArgs")
    return compilerArgs != testCompilerArgs
}

internal fun findCompilerPlugin(project: MavenProject): Plugin? =
    findPlugin(project, "org.apache.maven.plugins","maven-compiler-plugin")

internal fun getPluginGoalConfiguration(project: MavenProject, groupId: String, artifactId: String, goal: String): Xpp3Dom? {
    val plugin = project.buildPlugins.find { it.groupId == groupId && it.artifactId == artifactId } ?: return null
    val execution = plugin.executions.find { it.goals.contains(goal) }
    return (execution?.configuration ?: plugin.configuration) as? Xpp3Dom
}

internal fun isValidName(name: String?): Boolean {
    if (name.isNullOrBlank()) return false
    if (name == "Unknown") return false

    for (element in name) {
        if (!(element.isDigit() || element.isLetter() || element == '-' || element == '_' || element == '.')) {
            return false
        }
    }
    return true
}

internal fun findPlugin(project: MavenProject, groupId: String, artifactId: String): Plugin? {
    return project.buildPlugins.find { it.groupId == groupId && it.artifactId == artifactId }
}

fun getArtifactPath(artifact: Artifact, newClassifier: String?): Path {
    if (newClassifier.isNullOrEmpty()) return artifact.file.toPath()
    val currentPath = artifact.file.toPath()
    val file = currentPath.fileName.toString()
    val newFileName = fileNameWithNewClassifier(file, artifact.classifier, newClassifier)
    return currentPath.parent.resolve(newFileName)
}

internal fun toAbsolutePath(project: MavenProject, path: String): String {
    val p = Path(path)
    if (p.isAbsolute) return path
    if (project.basedir != null) return project.basedir.toPath().resolve(path).absolutePathString()
    return p.absolutePathString()
}