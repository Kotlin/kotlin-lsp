// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.maven.model

import com.jetbrains.ls.imports.maven.DependencyDataScope
import com.jetbrains.ls.imports.maven.JavaSettingsData
import com.jetbrains.ls.imports.maven.findCompilerPlugin
import com.jetbrains.ls.imports.maven.isValidName
import com.jetbrains.ls.imports.maven.parseJavaFeatureNumber
import com.jetbrains.ls.imports.maven.toAbsolutePath
import org.apache.maven.artifact.Artifact
import org.apache.maven.project.MavenProject
import org.codehaus.plexus.util.xml.Xpp3Dom

internal enum class StandardMavenModuleType {
    AGGREGATOR,
    SINGLE_MODULE,
    COMPOUND_MODULE,
    MAIN_ONLY,
    MAIN_ONLY_ADDITIONAL,
    TEST_ONLY
}

internal data class MavenModuleData(
    val moduleName: String,
    val type: StandardMavenModuleType,
    val sourceLanguageLevel: String?,
)

interface MavenImportDependencyWithArtifact {
    val artifact: Artifact
    val scope: DependencyDataScope
}

internal sealed class MavenImportDependency(val scope: DependencyDataScope) {
    class Module(val moduleName: String, scope: DependencyDataScope, val isTestJar: Boolean) : MavenImportDependency(scope)
    class Library(override val artifact: Artifact, scope: DependencyDataScope) : MavenImportDependency(scope),
        MavenImportDependencyWithArtifact

    class System(override val artifact: Artifact, scope: DependencyDataScope) : MavenImportDependency(scope),
        MavenImportDependencyWithArtifact

    class AttachedJar(val name: String, val roots: List<Pair<String, String>>, scope: DependencyDataScope) : MavenImportDependency(scope)
}

internal class MavenTreeModuleImportData(
    val mavenProject: MavenProject,
    val moduleData: MavenModuleData,
    val dependencies: List<MavenImportDependency>,
) {
    val javaSettings: JavaSettingsData
        get() {
            val level = moduleData.sourceLanguageLevel
            val isPreview = level?.endsWith("-preview") == true
            val rawLevel = level?.removeSuffix("-preview")
            val feature = parseJavaFeatureNumber(rawLevel)
            val jdkLevel = when {
                feature == null -> null
                feature <= 8 -> "JDK_1_$feature"
                else -> "JDK_$feature"
            }
            val finalJdkLevel = if (isPreview) {
                jdkLevel?.let { "${it}_PREVIEW" }
            } else {
                jdkLevel
            }
            val outputDir = mavenProject.build?.outputDirectory?.let { toAbsolutePath(mavenProject, it) }
            val testOutputDir = mavenProject.build?.testOutputDirectory?.let { toAbsolutePath(mavenProject, it) }

            val (compilerOutput, compilerOutputForTests) = when (moduleData.type) {
                StandardMavenModuleType.SINGLE_MODULE -> outputDir to testOutputDir
                StandardMavenModuleType.MAIN_ONLY,
                StandardMavenModuleType.MAIN_ONLY_ADDITIONAL -> outputDir to null
                StandardMavenModuleType.TEST_ONLY -> testOutputDir to testOutputDir
                else -> null to null
            }
            return JavaSettingsData(
                module = moduleData.moduleName,
                inheritedCompilerOutput = false,
                excludeOutput = false,
                compilerOutput = compilerOutput,
                compilerOutputForTests = compilerOutputForTests,
                languageLevelId = finalJdkLevel,
                manifestAttributes = emptyMap(),
                compilerArguments = collectCompilerArguments(mavenProject, moduleData.type)
            )
        }
}

/**
 * Collects the additional Java compiler arguments configured for [project]'s maven-compiler-plugin
 * (`<compilerArgs>`, `<compilerArgument>`, `<compilerArguments>`, `<parameters>` and the
 * `maven.compiler.parameters` property). For [StandardMavenModuleType.TEST_ONLY] modules the test-compile
 * configuration is preferred.
 */
internal fun collectCompilerArguments(project: MavenProject, type: StandardMavenModuleType): List<String> {
    val forTests = type == StandardMavenModuleType.TEST_ONLY
    val result = mutableListOf<String>()
    if (project.properties.getProperty("maven.compiler.parameters").toBoolean()) {
        result.add("-parameters")
    }

    val plugin = findCompilerPlugin(project)
    val goal = if (forTests) "testCompile" else "compile"
    val config = plugin?.let {
        (it.executions?.firstOrNull { execution -> execution.goals?.contains(goal) == true }?.configuration as? Xpp3Dom)
        ?: it.configuration as? Xpp3Dom
    } ?: return result

    config.getChild("parameters")?.value?.let { parameters ->
        if (parameters.toBoolean()) {
            if ("-parameters" !in result) result.add("-parameters")
        }
        else {
            result.remove("-parameters")
        }
    }

    if (forTests) {
        val testArgs = collectMapArgs(config, "testCompilerArguments") + listOfNotNull(singleArg(config, "testCompilerArgument"))
        if (testArgs.isNotEmpty()) {
            result.addAll(testArgs)
            return result
        }
    }

    result.addAll(collectMapArgs(config, "compilerArguments"))
    config.getChild("compilerArgs")?.children?.forEach { arg ->
        arg.value?.takeUnless { it.isBlank() }?.let { result.add(it) }
    }
    singleArg(config, "compilerArgument")?.let { result.add(it) }
    return result
}

private fun collectMapArgs(config: Xpp3Dom, tag: String): List<String> {
    val node = config.getChild(tag) ?: return emptyList()
    val result = mutableListOf<String>()
    for (child in node.children) {
        val key = child.name.let { if (it.startsWith("-")) it else "-$it" }
        val value = child.value?.takeUnless { it.isBlank() }
        if (key.startsWith("-A") && value != null) {
            result.add("$key=$value")
        }
        else {
            result.add(key)
            value?.let { result.add(it) }
        }
    }
    return result
}

private fun singleArg(config: Xpp3Dom, tag: String): String? =
    config.getChild(tag)?.value?.takeUnless { it.isBlank() }?.trim()

internal data class LanguageLevels(
    val sourceLevel: String?,
    val testSourceLevel: String?,
    val targetLevel: String?,
    val testTargetLevel: String?,
) {
    fun mainAndTestLevelsDiffer(): Boolean {
        return (testSourceLevel != null && testSourceLevel != sourceLevel)
                || (testTargetLevel != null && testTargetLevel != targetLevel)
    }
}


internal class MavenProjectImportData(
    val mavenProject: MavenProject,
    val module: MavenModuleData,
    val submodules: List<MavenModuleData>,
) {
    val defaultMainSubmodule = submodules.firstOrNull { it.type == StandardMavenModuleType.MAIN_ONLY }
    val mainSubmodules =
        submodules.filter { it.type == StandardMavenModuleType.MAIN_ONLY || it.type == StandardMavenModuleType.MAIN_ONLY_ADDITIONAL }
    val testSubmodules = submodules.filter { it.type == StandardMavenModuleType.TEST_ONLY }
}

internal class NameItem(
    val project: MavenProject,
    val existingName: String? = null
) : Comparable<NameItem> {
    val originalName: String = calcOriginalName()
    val groupId: String = project.groupId.takeIf { isValidName(it) } ?: ""
    var number: Int = -1
    var hasDuplicatedGroup: Boolean = false

    private fun calcOriginalName(): String =
        existingName ?: getDefaultModuleName()

    private fun getDefaultModuleName(): String =
        project.artifactId.takeIf { isValidName(it) } ?: project.basedir.name

    fun getResultName(): String {
        if (existingName != null) return existingName

        if (number == -1) return originalName
        var result = "$originalName (${number + 1})"
        if (!hasDuplicatedGroup && groupId.isNotEmpty()) {
            result += " ($groupId)"
        }
        return result
    }

    override fun compareTo(other: NameItem): Int {
        val path1 = project.basedir?.absolutePath ?: ""
        val path2 = other.project.basedir?.absolutePath ?: ""
        return path1.compareTo(path2, ignoreCase = true)
    }
}

internal val StandardMavenModuleType.containsMain: Boolean
    get() = when (this) {
        StandardMavenModuleType.SINGLE_MODULE,
        StandardMavenModuleType.MAIN_ONLY,
        StandardMavenModuleType.MAIN_ONLY_ADDITIONAL -> true

        else -> false
    }
internal val StandardMavenModuleType.containsTest: Boolean
    get() = when (this) {
        StandardMavenModuleType.SINGLE_MODULE,
        StandardMavenModuleType.TEST_ONLY -> true

        else -> false
    }