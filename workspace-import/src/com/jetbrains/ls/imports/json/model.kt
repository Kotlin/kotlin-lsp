// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.json

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WorkspaceData(
    val modules: List<ModuleData> = emptyList(),
    val libraries: List<LibraryData> = emptyList(),
    val sdks: List<SdkData> = emptyList(),
    val kotlinSettings: List<KotlinSettingsData> = emptyList(),
    val javaSettings: List<JavaSettingsData> = emptyList(),
)

@Serializable
data class ModuleData(
    val name: String,
    val type: String? = "JAVA_MODULE",
    val dependencies: List<DependencyData> = emptyList(),
    val contentRoots: List<ContentRootData> = emptyList(),
    val facets: List<FacetData> = emptyList(),
)

@Serializable
sealed class DependencyData {

    @Serializable
    @SerialName("module")
    data class Module(
        val name: String,
        val scope: DependencyDataScope,
        val isExported: Boolean = false,
        val isTestJar: Boolean = false,
    ) : DependencyData() {

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            if (!super.equals(other)) return false

            other as Module

            if (isExported != other.isExported) return false
            if (isTestJar != other.isTestJar) return false
            if (name != other.name) return false
            if (scope != other.scope) return false

            return true
        }

        override fun hashCode(): Int {
            var result = super.hashCode()
            result = 31 * result + isExported.hashCode()
            result = 31 * result + isTestJar.hashCode()
            result = 31 * result + name.hashCode()
            result = 31 * result + scope.hashCode()
            return result
        }
    }

    @Serializable
    @SerialName("library")
    data class Library(
        val name: String,
        val scope: DependencyDataScope,
        val isExported: Boolean = false,
    ) : DependencyData() {

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            if (!super.equals(other)) return false

            other as Library

            if (isExported != other.isExported) return false
            if (name != other.name) return false
            if (scope != other.scope) return false

            return true
        }

        override fun hashCode(): Int {
            var result = super.hashCode()
            result = 31 * result + isExported.hashCode()
            result = 31 * result + name.hashCode()
            result = 31 * result + scope.hashCode()
            return result
        }
    }

    @Serializable
    @SerialName("inheritedSdk")
    data object InheritedSdk : DependencyData()

    @Serializable
    @SerialName("moduleSource")
    data object ModuleSource : DependencyData()

    @Serializable
    @SerialName("sdk")
    data class Sdk(val name: String, val kind: String) : DependencyData() {

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            if (!super.equals(other)) return false

            other as Sdk

            if (name != other.name) return false
            if (kind != other.kind) return false

            return true
        }

        override fun hashCode(): Int {
            var result = super.hashCode()
            result = 31 * result + name.hashCode()
            result = 31 * result + kind.hashCode()
            return result
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        return true
    }

    override fun hashCode(): Int {
        return javaClass.hashCode()
    }

}


@Serializable
data class ContentRootData(
    val path: String,
    val excludedPatterns: List<String> = emptyList(),
    val excludedUrls: List<String> = emptyList(),
    val sourceRoots: List<SourceRootData> = emptyList()
)

@Serializable
data class SourceRootData(
    val path: String,
    val type: String,
)

@Serializable
data class FacetData(
    val name: String,
    val type: String,
    val configuration: XmlElement? = null,
    val underlyingFacet: FacetData? = null
)

@Serializable
data class LibraryData(
    val name: String,
    val level: String = "project",
    val module: String? = null,
    val type: String?,
    val roots: List<LibraryRootData>,
    val excludedRoots: List<String> = emptyList(),
    val properties: XmlElement? = null,
)

enum class InclusionOptions {
    @SerialName("root_itself")
    ROOT_ITSELF,
    @SerialName("archives_under_root")
    ARCHIVES_UNDER_ROOT,
    @SerialName("archives_under_root_recursively")
    ARCHIVES_UNDER_ROOT_RECURSIVELY
}

@Serializable
data class LibraryRootData(
    val path: String,
    val type: String = "CLASSES",
    val inclusionOptions: InclusionOptions = InclusionOptions.ROOT_ITSELF
)


@Serializable
data class SdkData(
    val name: String,
    val type: String,
    val version: String?,
    val homePath: String?,
    val roots: List<SdkRootData>? = null, // Can be calculated from homePath when omitted
    val additionalData: String,
)

@Serializable
data class SdkRootData(
    val url: String,
    val type: String,
)


@Serializable
data class KotlinSettingsData(
    val name: String,
    val sourceRoots: List<String>,
    val configFileItems: List<ConfigFileItemData>,
    val module: String,

    // trivial parameters (String, Boolean)
    val useProjectSettings: Boolean,
    val implementedModuleNames: List<String>,
    val dependsOnModuleNames: List<String>,
    val additionalVisibleModuleNames: Set<String>,
    val productionOutputPath: String?,
    val testOutputPath: String?,
    val sourceSetNames: List<String>,
    val isTestModule: Boolean,
    val externalProjectId: String,
    val isHmppEnabled: Boolean,

    val pureKotlinSourceFolders: List<String>,

    //semi-trivial parameters (enums)
    val kind: KotlinModuleKind,

    //non-trivial parameters
    val compilerArguments: String?,

    val additionalArguments: String?,
    val scriptTemplates: String?,
    val scriptTemplatesClasspath: String?,
    val copyJsLibraryFiles: Boolean = false,
    val outputDirectoryForJsLibraryFiles: String?,

    val targetPlatform: String?,
    val externalSystemRunTasks: List<String>,
    val version: Int,
    val flushNeeded: Boolean
) {
    enum class KotlinModuleKind {
        @SerialName("default")
        DEFAULT,
        @SerialName("source_set_holder")
        SOURCE_SET_HOLDER,
        @SerialName("compilation_and_source_set_holder")
        COMPILATION_AND_SOURCE_SET_HOLDER
    }
}

@Serializable
data class ConfigFileItemData(val id: String, val url: String)

enum class DependencyDataScope {
    @SerialName("compile")
    COMPILE,

    @SerialName("test")
    TEST,

    @SerialName("runtime")
    RUNTIME,

    @SerialName("provided")
    PROVIDED
}

@Serializable
data class XmlElement(
    val tag: String = "",
    val attributes: Map<String, String> = emptyMap(),
    val children: List<XmlElement> = emptyList(),
    val text: String? = null
)

@Serializable
data class JavaSettingsData(
    val module: String,
    val inheritedCompilerOutput: Boolean,
    val excludeOutput: Boolean,
    val compilerOutput: String?,
    val compilerOutputForTests: String?,
    val languageLevelId: String?,
    val manifestAttributes: Map<String, String>
)

@Serializable
data class KotlinJvmCompilerArguments(
    val jvmTarget: String? = null,
    val pluginOptions: List<String> = emptyList(),
    val pluginClasspaths: List<String> = emptyList(),
)