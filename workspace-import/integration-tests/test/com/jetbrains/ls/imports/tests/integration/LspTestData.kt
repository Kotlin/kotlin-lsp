// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.tests.integration

import com.intellij.workspaceModel.integrationTests.data.ResourceDataDeserializer
import com.intellij.workspaceModel.performanceTesting.validator.models.ModuleEntityDto
import com.intellij.workspaceModel.performanceTesting.validator.models.ProjectStructureWithModules
import java.util.Locale.getDefault

sealed interface LspTestData {

    fun loadModulesFromJson(): List<ModuleEntityDto> {
        val jsonName = this.javaClass.simpleName.replaceFirstChar { it.lowercase(getDefault()) }
        return this.javaClass.classLoader.getResourceAsStream("workspaceData/maven/${jsonName}.json").use {
            ResourceDataDeserializer.deserializeStreamToData<List<ModuleEntityDto>>(it!!)
        }
    }

    fun getStructure(): ProjectStructureWithModules {
        return ProjectStructureWithModules(modules = loadModulesFromJson())
    }

    object MavenDifferentJavaLevelsModulesData : LspTestData
}



