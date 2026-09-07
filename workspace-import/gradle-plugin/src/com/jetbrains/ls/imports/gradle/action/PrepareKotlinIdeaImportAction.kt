// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.action

import com.jetbrains.ls.imports.gradle.model.builder.PrepareKotlinIdeaImportTasksRequest
import com.jetbrains.ls.imports.gradle.utils.getIncludedBuilds
import org.gradle.tooling.BuildAction
import org.gradle.tooling.BuildController
import org.gradle.tooling.model.gradle.GradleBuild

class PrepareKotlinIdeaImportAction : BuildAction<String> {

    override fun execute(controller: BuildController): String {
        prepareKotlinTasks(controller, controller.buildModel)
        controller.getIncludedBuilds().forEach {
            prepareKotlinTasks(controller, it)
        }
        return "success"
    }

    private fun prepareKotlinTasks(controller: BuildController, build: GradleBuild) {
        build.projects.forEach {
            controller.findModel(it, PrepareKotlinIdeaImportTasksRequest::class.java)
        }
    }
}
