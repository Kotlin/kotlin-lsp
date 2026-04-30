// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package com.jetbrains.ls.imports.maven

import org.apache.maven.execution.MavenSession
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoFailureException
import org.apache.maven.project.MavenProject
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import java.io.File

/**
 * ```
 * mvn install:install-file \
 *   -Dfile=out/jps-artifacts/language-server.workspace-import.maven-plugin.jar \
 *   -DgroupId=com.jetbrains.ls \
 *   -DartifactId=imports-maven-plugin \
 *   -Dversion=0.100 \
 *   -Dpackaging=maven-plugin
 *
 * MAVEN_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005" \
 * mvn com.jetbrains.ls:imports-maven-plugin:info -f pom.xml -DoutputFile=workspace.json
 * ```
 */
@Suppress("IO_FILE_USAGE")
class ProcessSourcesMojo : AbstractMojo() {
    private lateinit var project: MavenProject
    private lateinit var session: MavenSession
    private var outputFile: String? = null
    private lateinit var repositorySystem: RepositorySystem
    private lateinit var repositorySystemSession: RepositorySystemSession

    override fun execute() {
        val outputFile = this.outputFile ?: throw MojoFailureException("Output file should be defined")
        val modules = getAllModules(project.deepestExecutionProject())

        val kotlinSettings = modules.mapNotNull {
            it.mavenProject.extractKotlinSettings(it.moduleData, repositorySystem, repositorySystemSession)
        }

        val modulesData = modules.map {
            it.toModuleData(kotlinSettings)
        }


        val data = WorkspaceData(
            modules = modulesData,
            libraries = emptyList(),
            sdks = emptyList(),
            kotlinSettings = kotlinSettings,
            javaSettings = emptyList()
        )
        printJsonDataIntoFile(data, File(outputFile))
    }
}

