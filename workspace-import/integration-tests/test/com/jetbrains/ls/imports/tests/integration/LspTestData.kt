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

sealed interface LspTestData {

    private fun loadModulesFromJson(): List<ModuleEntityDto> {
        val jsonName = this.javaClass.simpleName.replaceFirstChar { it.lowercase(getDefault()) }
        return this.javaClass.classLoader.getResourceAsStream("workspaceData/maven/${jsonName}.json").use {
            ResourceDataDeserializer.deserializeStreamToData<List<ModuleEntityDto>>(it!!)
        }
    }

    fun getFile(): Path? {
        val jsonName = this.javaClass.simpleName.replaceFirstChar { it.lowercase(getDefault()) } + ".json"
        val communityPath = Path.of(PathManager.getCommunityHomePath())
        val modulePath = communityPath.parent / "language-server" / "community" / "workspace-import" / "integration-tests"
        val resourcesPath = modulePath / "testResources" / "workspaceData" / "maven"
        return (resourcesPath / jsonName).takeIf { it.isRegularFile() }
    }

    fun getStructure(): ProjectStructureWithModules {
        return ProjectStructureWithModules(modules = loadModulesFromJson())
    }

    object MavenDifferentJavaLevelsModulesData : LspTestData


    object MavenBatisDynamicSqlModulesData : LspTestData

    object MavenPetClinicMicroservicesModulesData : LspTestData

    object MavenGsonModulesData : LspTestData

    object MavenWithShadePluginModulesData : LspTestData

    object MavenEmptyProjectCustomSrcRootModulesData : LspTestData

    object MavenLogBackModulesData : LspTestData

    object MavenComplexStructuresModulesData : LspTestData

    object MavenWeblogicDeployModulesData : LspTestData

    object MavenWithEjbFacetModulesData : LspTestData

    object MavenBananaSmoothiiModulesData : LspTestData

    object MavenTutSpringBootKotlinModulesData : LspTestData

    object MavenProjectWithDifferentTypesOfRootsModulesData : LspTestData

    object MavenSpringCloudExamplesModulesData : LspTestData

    object MavenJavaDesignPatternsSmallModulesData : LspTestData

    object MavenJacksonCoreModulesData : LspTestData

    object MavenGuavaModulesData : LspTestData

    object MavenJUnit4ModulesData : LspTestData

    object MavenProjectWithProfilesModulesDataTest : LspTestData

    object MavenSmallProjectWithTwoModulesModulesData : LspTestData

    object MavenSmallProjectWithResourcesModulesData : LspTestData

    object MavenSpringBootExamplesModulesData : LspTestData

    object MavenTitanModulesData : LspTestData

    object MavenZipkinModulesData : LspTestData
}



