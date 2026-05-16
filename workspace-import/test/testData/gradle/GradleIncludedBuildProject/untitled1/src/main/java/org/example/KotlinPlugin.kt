package org.example

import org.gradle.api.Plugin
import org.gradle.api.Project

class KotlinPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.plugins.apply("org.jetbrains.kotlin.jvm")
        project.repositories.add(
            project.repositories.mavenCentral()
        )
    }
}
