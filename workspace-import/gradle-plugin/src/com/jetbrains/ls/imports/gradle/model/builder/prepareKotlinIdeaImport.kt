@file:JvmName("PrepareKotlinIdeaImport")

// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.model.builder

import com.jetbrains.ls.imports.gradle.model.builder.android.androidVariants
import org.gradle.api.Project
import org.gradle.api.invocation.Gradle
import org.gradle.util.GradleVersion

/**
 * The Kotlin ecosystem, by default, defines a task called 'prepareKotlinIdeaImport' which is expected to
 * be called before importing a project into the IDE. This task can be used to generate sources or prepare the workspace in any other way.
 *
 * We're re-using the same name to be compliant with typical Kotlin projects and equally ensure this task to be executed
 * before building models for the IDE.
 */
const val PREPARE_KOTLIN_IDEA_IMPORT_TASK_NAME: String = "prepareKotlinIdeaImport"

/**
 * @see PREPARE_KOTLIN_IDEA_IMPORT_TASK_NAME
 */
fun Gradle.setupPrepareKotlinIdeaImport() {
    val setup = { project: Project ->
        project.afterEvaluate {
            val prepareKotlinIdeaImportTask = if (PREPARE_KOTLIN_IDEA_IMPORT_TASK_NAME in project.tasks.names)
                project.tasks.named(PREPARE_KOTLIN_IDEA_IMPORT_TASK_NAME)
            else project.tasks.register(PREPARE_KOTLIN_IDEA_IMPORT_TASK_NAME)

            prepareKotlinIdeaImportTask.configure { task ->
                task.outputs.upToDateWhen { false }

                /*
                Setup Android:
                Getting source directories from Android might require calling source-gen tasks first.
                We ensure that the 'prepareKotlinIdeaImport' task defines those sources as input, guaranteeing the
                underlying providers to be accessible during model building
                 */
                project.androidVariants.orEmpty().forEach { variant ->
                    variant.sources?.kotlin?.let { sources -> task.inputs.file(sources) }
                    variant.sources?.java?.let { sources -> task.inputs.file(sources) }
                    variant.sources?.resources?.let { sources -> task.inputs.file(sources) }
                }
            }
        }
    }

    if (GradleVersion.current() >= GradleVersion.version("8.8")) {
        lifecycle.afterProject(setup)
    } else {
        afterProject(setup)
    }
}
