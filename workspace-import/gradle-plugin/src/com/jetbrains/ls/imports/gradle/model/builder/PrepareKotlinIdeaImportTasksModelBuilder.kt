// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.model.builder

import com.jetbrains.ls.imports.gradle.utils.reflected
import org.gradle.api.Project
import org.gradle.api.artifacts.component.BuildIdentifier
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.internal.build.BuildState
import org.gradle.tooling.provider.model.ToolingModelBuilder
import org.gradle.util.GradleVersion

private val TARGET_MODEL_NAME: String = PrepareKotlinIdeaImportTasksRequest::class.java.getName()

interface PrepareKotlinIdeaImportTasksRequest

class PrepareKotlinIdeaImportTasksModelBuilder : ToolingModelBuilder {

    override fun canBuild(modelName: String): Boolean = TARGET_MODEL_NAME == modelName

    override fun buildAll(modelName: String, project: Project): PrepareKotlinIdeaImportTasksRequest? {
        val taskNames = project.getKotlinImportTasks()
        if (taskNames.isEmpty()) {
            return null
        }
        try {
            project.addTasksToStartParameter(taskNames)
        } catch (e: Exception) {
            project.logger.warn("Unable to run $taskNames in ${project.path}. Generated sources can be missing.", e)
            return null
        }
        return object : PrepareKotlinIdeaImportTasksRequest {}
    }

    internal fun Project.addTasksToStartParameter(taskNames: List<String>) {
        if (project.gradle.parent == null) {
            /* Root of composite build: We can just add the task name */
            project.gradle.startParameter.setTaskNames(project.gradle.startParameter.taskNames.toSet() + taskNames)
            return
        }
        val buildId = (project as ProjectInternal).services.get(BuildState::class.java).buildIdentifier
        val projectPathPart = if (rootProject != project) project.path else ""
        val absoluteTaskPaths = taskNames.map { taskName -> "${buildId.getBuildPathCompat()}$projectPathPart:$taskName" }
        val rootBuild = generateSequence(project.gradle) { it.parent }.last()
        rootBuild.startParameter.setTaskNames(rootBuild.startParameter.taskNames.toSet() + absoluteTaskPaths)
    }

    private fun Project.getKotlinImportTasks(): List<String> = tasks.names
        .filter { taskName -> taskName.startsWith(PREPARE_KOTLIN_IDEA_IMPORT_TASK_NAME) }

    private fun BuildIdentifier.getBuildPathCompat(): String {
        if (GradleVersion.current() >= GradleVersion.version("8.2")) {
            return buildPath
        }
        val name = reflected.call("getName")?.unwrapAs<String>() ?: return ""
        return if (name.startsWith(":")) name else ":$name"
    }
}
