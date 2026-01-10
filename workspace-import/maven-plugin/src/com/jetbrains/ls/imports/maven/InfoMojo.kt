// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.maven

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.project.MavenProject
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import java.io.FileOutputStream
import java.io.PrintStream

/**
 * ```
 * mvn install:install-file \
 *   -Dfile=out/jps-artifacts/language-server.project-import.maven-plugin.jar \
 *   -DgroupId=com.jetbrains.ls \
 *   -DartifactId=imports-maven-plugin \
 *   -Dversion=0.99 \
 *   -Dpackaging=maven-plugin
 *
 * MAVEN_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005" \
 * mvn com.jetbrains.ls:imports-maven-plugin:info -f pom.xml -DoutputFile=workspace.json
 * ```
 */
class InfoMojo : AbstractMojo() {
    private lateinit var project: MavenProject
    private lateinit var repositorySystem: RepositorySystem
    private lateinit var repositorySystemSession: RepositorySystemSession

    private var outputFile: String? = null

    override fun execute() {
        val print = outputFile?.takeIf { it.isNotEmpty() }?.let {
            PrintStream(FileOutputStream(it, true))
        } ?: System.out
        try {
            @OptIn(ExperimentalSerializationApi::class)
            val json = Json {
                prettyPrint = true
                prettyPrintIndent = " "
                encodeDefaults = false
            }
            val jsonString = json.encodeToString(
                project.toWorkspaceData(repositorySystem, repositorySystemSession)
            )
            print.println(jsonString)
            print.flush()
        } catch (e: Throwable) {
            e.printStackTrace(System.out)
        } finally {
            print?.close()
        }
    }
}
