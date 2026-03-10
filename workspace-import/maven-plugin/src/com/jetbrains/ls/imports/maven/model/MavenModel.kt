// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.maven.model

import com.jetbrains.ls.imports.maven.DependencyDataScope
import com.jetbrains.ls.imports.maven.JavaSettingsData
import com.jetbrains.ls.imports.maven.isValidName
import com.jetbrains.ls.imports.maven.parseJavaFeatureNumber
import com.jetbrains.ls.imports.maven.toAbsolutePath
import org.apache.maven.artifact.Artifact
import org.apache.maven.project.MavenProject

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
                manifestAttributes = emptyMap()
            )
        }
}

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