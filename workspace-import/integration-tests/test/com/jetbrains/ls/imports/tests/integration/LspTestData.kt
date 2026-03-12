// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.tests.integration

import com.intellij.openapi.application.PathManager
import com.intellij.workspaceModel.integrationTests.data.ResourceDataDeserializer
import com.intellij.workspaceModel.performanceTesting.validator.models.ModuleEntityDto
import com.intellij.workspaceModel.performanceTesting.validator.models.ProjectStructureWithModules
import java.nio.file.Path
import java.util.Locale.getDefault
import kotlin.io.path.div
import kotlin.io.path.isRegularFile

enum class LspTestData {

    MavenDifferentJavaLevelsModulesData,
    MavenBatisDynamicSqlModulesData,
    MavenPetClinicMicroservicesModulesData,
    MavenGsonModulesData,
    MavenWithShadePluginModulesData,
    MavenEmptyProjectCustomSrcRootModulesData,
    MavenLogBackModulesData,
    MavenComplexStructuresModulesData,
    MavenWeblogicDeployModulesData,
    MavenWithEjbFacetModulesData,
    MavenBananaSmoothiiModulesData,
    MavenTutSpringBootKotlinModulesData,
    MavenProjectWithDifferentTypesOfRootsModulesData,
    MavenSpringCloudExamplesModulesData,
    MavenJavaDesignPatternsSmallModulesData,
    MavenJacksonCoreModulesData,
    MavenGuavaModulesData,
    MavenJUnit4ModulesData,
    MavenProjectWithProfilesModulesDataTest,
    MavenSmallProjectWithTwoModulesModulesData,
    MavenSmallProjectWithResourcesModulesData,
    MavenSpringBootExamplesModulesData,
    MavenTitanModulesData,
    MavenZipkinModulesData;

    private fun loadModulesFromJson(): List<ModuleEntityDto> {
        val jsonName = name.replaceFirstChar { it.lowercase(getDefault()) }
        return this.javaClass.classLoader.getResourceAsStream("workspaceData/maven/${jsonName}.json").use {
            ResourceDataDeserializer.deserializeStreamToData<List<ModuleEntityDto>>(it!!)
        }
    }

    fun getFile(): Path? {
        val jsonName = name.replaceFirstChar { it.lowercase(getDefault()) } + ".json"
        val communityPath = Path.of(PathManager.getCommunityHomePath())
        val modulePath = communityPath.parent / "language-server" / "community" / "workspace-import" / "integration-tests"
        val resourcesPath = modulePath / "testResources" / "workspaceData" / "maven"
        return (resourcesPath / jsonName).takeIf { it.isRegularFile() }
    }

    fun getStructure(): ProjectStructureWithModules {
        return ProjectStructureWithModules(modules = loadModulesFromJson())
    }
}



