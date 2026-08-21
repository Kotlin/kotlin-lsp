// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

import com.jetbrains.ls.imports.maven.defaultAnnotationProcessorSourcesDir
import com.jetbrains.ls.imports.maven.getCompilerGeneratedSourcesDir
import com.jetbrains.ls.imports.maven.getCompilerGeneratedTestSourcesDir
import com.jetbrains.ls.imports.maven.hasAnnotationProcessing
import org.apache.maven.model.Build
import org.apache.maven.model.Plugin
import org.apache.maven.model.PluginExecution
import org.apache.maven.project.MavenProject
import org.codehaus.plexus.util.xml.Xpp3Dom
import org.codehaus.plexus.util.xml.Xpp3DomBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.StringReader

/**
 * Unit tests for the mojo's compiler-plugin configuration reading: the layering must match what
 * Maven's `DefaultPluginConfigurationExpander` makes effective (execution configuration dominant
 * over the plugin-level one), and annotation processing is resolved per scope. The integration
 * goldens exercise only the happy path, so the layering rules are frozen here against real
 * maven-model objects.
 */
class MavenPluginConfigTest {

    private fun xml(text: String): Xpp3Dom = Xpp3DomBuilder.build(StringReader(text))

    private fun compilerPlugin(configuration: String? = null, executions: Map<String, String> = emptyMap()): Plugin =
        Plugin().apply {
            groupId = "org.apache.maven.plugins"
            artifactId = "maven-compiler-plugin"
            configuration?.let { this.configuration = xml(it) }
            executions.forEach { (id, config) ->
                addExecution(PluginExecution().apply {
                    this.id = id
                    this.configuration = xml(config)
                })
            }
        }

    private fun project(plugin: Plugin): MavenProject =
        MavenProject().apply {
            build = Build().apply {
                directory = "/repo/target"
                addPlugin(plugin)
            }
        }

    @Test
    fun `execution-level proc overrides a plugin-level none`() {
        val project = project(
            compilerPlugin(
                configuration = "<configuration><proc>none</proc></configuration>",
                executions = mapOf("default-testCompile" to "<configuration><proc>full</proc></configuration>"),
            )
        )
        assertTrue(project.hasAnnotationProcessing(testSources = true), "the test execution enables APT")
        assertFalse(project.hasAnnotationProcessing(testSources = false), "the main scope stays disabled")
    }

    @Test
    fun `annotation processing is scoped per execution`() {
        val project = project(
            compilerPlugin(
                executions = mapOf(
                    "default-testCompile" to
                        "<configuration><annotationProcessorPaths><path><groupId>g</groupId><artifactId>a</artifactId><version>1</version></path></annotationProcessorPaths></configuration>",
                ),
            )
        )
        assertTrue(project.hasAnnotationProcessing(testSources = true))
        assertFalse(project.hasAnnotationProcessing(testSources = false), "test-only APT must not register the main root")
        assertEquals("/repo/target/generated-test-sources/test-annotations", project.defaultAnnotationProcessorSourcesDir(testSources = true))
        assertNull(project.defaultAnnotationProcessorSourcesDir(testSources = false))
    }

    @Test
    fun `plugin-level annotationProcessorPaths enables both scopes`() {
        val project = project(
            compilerPlugin(
                configuration =
                    "<configuration><annotationProcessorPaths><path><groupId>g</groupId><artifactId>a</artifactId><version>1</version></path></annotationProcessorPaths></configuration>",
            )
        )
        assertEquals("/repo/target/generated-sources/annotations", project.defaultAnnotationProcessorSourcesDir(testSources = false))
        assertEquals("/repo/target/generated-test-sources/test-annotations", project.defaultAnnotationProcessorSourcesDir(testSources = true))
    }

    @Test
    fun `an empty proc element is not annotation processing`() {
        val project = project(compilerPlugin(configuration = "<configuration><proc></proc></configuration>"))
        assertFalse(project.hasAnnotationProcessing(testSources = false))
        assertFalse(project.hasAnnotationProcessing(testSources = true))
    }

    @Test
    fun `no compiler plugin means no annotation processing`() {
        val project = MavenProject().apply { build = Build().apply { directory = "/repo/target" } }
        assertFalse(project.hasAnnotationProcessing(testSources = false))
        assertNull(project.defaultAnnotationProcessorSourcesDir(testSources = true))
    }

    @Test
    fun `explicit generatedSourcesDirectory wins over the default`() {
        val project = project(
            compilerPlugin(
                configuration = "<configuration><generatedSourcesDirectory>/custom/gen</generatedSourcesDirectory><proc>full</proc></configuration>",
            )
        )
        assertEquals("/custom/gen", project.getCompilerGeneratedSourcesDir("default-compile"))
    }

    @Test
    fun `explicit generatedTestSourcesDirectory is read, execution level first`() {
        val project = project(
            compilerPlugin(
                configuration = "<configuration><generatedTestSourcesDirectory>/plugin/test-gen</generatedTestSourcesDirectory></configuration>",
                executions = mapOf("default-testCompile" to "<configuration><generatedTestSourcesDirectory>/exec/test-gen</generatedTestSourcesDirectory></configuration>"),
            )
        )
        assertEquals("/exec/test-gen", project.getCompilerGeneratedTestSourcesDir("default-testCompile"))
    }

    @Test
    fun `self-closed generatedSourcesDirectory does not crash`() {
        // plexus returns null from Xpp3Dom.getValue() for a self-closed node; this used to NPE and
        // abort the whole import.
        val project = project(
            compilerPlugin(configuration = "<configuration><generatedSourcesDirectory/></configuration>")
        )
        assertNull(project.getCompilerGeneratedSourcesDir("default-compile"))
    }
}
