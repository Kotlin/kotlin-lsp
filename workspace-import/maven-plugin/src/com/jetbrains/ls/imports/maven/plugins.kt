// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.maven

import com.jetbrains.ls.imports.maven.model.StandardMavenModuleType
import com.jetbrains.ls.imports.maven.model.containsMain
import com.jetbrains.ls.imports.maven.model.containsTest
import org.apache.maven.model.Plugin
import org.apache.maven.project.MavenProject
import org.codehaus.plexus.util.xml.Xpp3Dom

internal fun MutableList<SourceRootData>.addPluginGeneratedSources(
    project: MavenProject,
    moduleType: StandardMavenModuleType,
) {
    if (moduleType.containsMain) {
        addBuildHelperRoots(project, goal = "add-source", property = "sources", rootType = "java-source")
        addBuildHelperRoots(project, goal = "add-resource", property = "resources", rootType = "java-resource")
        addRemoteResourcesGeneratedResources(project)
        addModelloGeneratedSources(project)
        addAntlr4GeneratedSources(project, isTest = false)
        addAvroGeneratedSources(project, isTest = false)
        addProtobufGeneratedSources(project, isTest = false)
        addAscopesProtobufGeneratedSources(project, isTest = false)
        addProtocJarGeneratedSources(project)
        addOpenApiGeneratorGeneratedSources(project)
        addJaxb2GeneratedSources(project, isTest = false)
        addCxfCodegenGeneratedSources(project)
        addJooqGeneratedSources(project)
        addQuerydslGeneratedSources(project)
        addJavaccGeneratedSources(project)
        addJflexGeneratedSources(project)
        addThriftGeneratedSources(project, isTest = false)
        addSwaggerGeneratedSources(project)
    }
    if (moduleType.containsTest) {
        addBuildHelperRoots(project, goal = "add-test-source", property = "sources", rootType = "java-test")
        addBuildHelperRoots(project, goal = "add-test-resource", property = "resources", rootType = "java-test-resource")
        addAntlr4GeneratedSources(project, isTest = true)
        addAvroGeneratedSources(project, isTest = true)
        addProtobufGeneratedSources(project, isTest = true)
        addAscopesProtobufGeneratedSources(project, isTest = true)
        addJaxb2GeneratedSources(project, isTest = true)
        addThriftGeneratedSources(project, isTest = true)
    }
}

private fun MutableList<SourceRootData>.addBuildHelperRoots(
    project: MavenProject,
    goal: String,
    property: String,
    rootType: String,
) {
    val plugin = findPlugin(project, "org.codehaus.mojo", "build-helper-maven-plugin") ?: return
    plugin.executions.forEach { execution ->
        if (!execution.goals.contains(goal)) return@forEach
        val config = execution.configuration as? Xpp3Dom ?: return@forEach
        val sources = config.getChild(property) ?: return@forEach
        sources.children.forEach { sourceElement ->
            val path = sourceElement.value?.trim()
            if (!path.isNullOrEmpty()) {
                add(SourceRootData(toAbsolutePath(project, path), rootType))
            }
        }
    }
}

private fun MutableList<SourceRootData>.addRemoteResourcesGeneratedResources(project: MavenProject) {
    val plugin = findPlugin(project, "org.apache.maven.plugins", "maven-remote-resources-plugin") ?: return
    addOutputDirectoryFromExecutions(
        project, plugin,
        matchingGoals = setOf("process"),
        configChildName = "outputDirectory",
        defaultRelativePath = "maven-shared-archive-resources",
        rootType = "java-resource",
    )
}

private fun MutableList<SourceRootData>.addModelloGeneratedSources(project: MavenProject) {
    val plugin = findPlugin(project, "org.codehaus.modello", "modello-maven-plugin") ?: return
    val javaGeneratingGoals = setOf("java", "velocity", "java5", "jpox-jdo-mapping", "jpox-metadata-class")
    addOutputDirectoryFromExecutions(
        project, plugin,
        matchingGoals = javaGeneratingGoals,
        configChildName = "outputDirectory",
        defaultRelativePath = "generated-sources/modello",
        rootType = "java-source",
    )
}

private fun MutableList<SourceRootData>.addAntlr4GeneratedSources(project: MavenProject, isTest: Boolean) {
    val plugin = findPlugin(project, "org.antlr", "antlr4-maven-plugin") ?: return
    addOutputDirectoryFromExecutions(
        project, plugin,
        matchingGoals = setOf(if (isTest) "antlr4-test" else "antlr4"),
        configChildName = "outputDirectory",
        defaultRelativePath = if (isTest) "generated-test-sources/antlr4" else "generated-sources/antlr4",
        rootType = if (isTest) "java-test" else "java-source",
    )
}

private fun MutableList<SourceRootData>.addAvroGeneratedSources(project: MavenProject, isTest: Boolean) {
    val plugin = findPlugin(project, "org.apache.avro", "avro-maven-plugin") ?: return
    val mainGoals = setOf("schema", "protocol", "idl-protocol")
    val testGoals = setOf("test-schema", "test-protocol", "test-idl-protocol")
    addOutputDirectoryFromExecutions(
        project, plugin,
        matchingGoals = if (isTest) testGoals else mainGoals,
        configChildName = if (isTest) "testOutputDirectory" else "outputDirectory",
        defaultRelativePath = if (isTest) "generated-test-sources/avro" else "generated-sources/avro",
        rootType = if (isTest) "java-test" else "java-source",
    )
}

private fun MutableList<SourceRootData>.addProtobufGeneratedSources(project: MavenProject, isTest: Boolean) {
    val plugin = findPlugin(project, "org.xolstice.maven.plugins", "protobuf-maven-plugin") ?: return
    val mainGoals = setOf("compile", "compile-custom")
    val testGoals = setOf("test-compile", "test-compile-custom")
    addOutputDirectoryFromExecutions(
        project, plugin,
        matchingGoals = if (isTest) testGoals else mainGoals,
        configChildName = "outputDirectory",
        defaultRelativePath = if (isTest) "generated-test-sources/protobuf/java" else "generated-sources/protobuf/java",
        rootType = if (isTest) "java-test" else "java-source",
    )
}

private fun MutableList<SourceRootData>.addAscopesProtobufGeneratedSources(project: MavenProject, isTest: Boolean) {
    val plugin = findPlugin(project, "io.github.ascopes", "protobuf-maven-plugin") ?: return
    val mainGoals = setOf("generate", "generate-main")
    val testGoals = setOf("generate-test")
    addOutputDirectoryFromExecutions(
        project, plugin,
        matchingGoals = if (isTest) testGoals else mainGoals,
        configChildName = "outputDirectory",
        defaultRelativePath = if (isTest) "generated-test-sources/protobuf/generate-test" else "generated-sources/protobuf/generate",
        rootType = if (isTest) "java-test" else "java-source",
    )
}

private fun MutableList<SourceRootData>.addProtocJarGeneratedSources(project: MavenProject) {
    val plugin = findPlugin(project, "com.github.os72", "protoc-jar-maven-plugin") ?: return
    addOutputDirectoryFromExecutions(
        project, plugin,
        matchingGoals = setOf("run"),
        configChildName = "outputDirectory",
        defaultRelativePath = "generated-sources/protobuf/java",
        rootType = "java-source",
    )
}

private fun MutableList<SourceRootData>.addOpenApiGeneratorGeneratedSources(project: MavenProject) {
    val plugin = findPlugin(project, "org.openapitools", "openapi-generator-maven-plugin") ?: return
    plugin.executions.forEach { execution ->
        if (!execution.goals.contains("generate")) return@forEach
        val executionConfig = execution.configuration as? Xpp3Dom
        val pluginConfig = plugin.configuration as? Xpp3Dom
        val output = executionConfig?.getChild("output")?.value?.trim()
            ?: pluginConfig?.getChild("output")?.value?.trim()
            ?: "${project.build?.directory ?: "target"}/generated-sources/openapi"
        if (output.isNotEmpty()) {
            add(SourceRootData(toAbsolutePath(project, "$output/src/main/java"), "java-source"))
        }
    }
}

private fun MutableList<SourceRootData>.addJaxb2GeneratedSources(project: MavenProject, isTest: Boolean) {
    val plugin = findPlugin(project, "org.codehaus.mojo", "jaxb2-maven-plugin") ?: return
    val mainGoals = setOf("xjc", "schemagen")
    val testGoals = setOf("testXjc", "testSchemagen")
    addOutputDirectoryFromExecutions(
        project, plugin,
        matchingGoals = if (isTest) testGoals else mainGoals,
        configChildName = "outputDirectory",
        defaultRelativePath = if (isTest) "generated-test-sources/jaxb2" else "generated-sources/jaxb2",
        rootType = if (isTest) "java-test" else "java-source",
    )
}

private fun MutableList<SourceRootData>.addCxfCodegenGeneratedSources(project: MavenProject) {
    val plugin = findPlugin(project, "org.apache.cxf", "cxf-codegen-plugin") ?: return
    addOutputDirectoryFromExecutions(
        project, plugin,
        matchingGoals = setOf("wsdl2java"),
        configChildName = "sourceRoot",
        defaultRelativePath = "generated-sources/cxf",
        rootType = "java-source",
    )
}

private fun MutableList<SourceRootData>.addJooqGeneratedSources(project: MavenProject) {
    val plugin = findPlugin(project, "org.jooq", "jooq-codegen-maven") ?: return
    plugin.executions.forEach { execution ->
        if (!execution.goals.contains("generate")) return@forEach
        val executionConfig = execution.configuration as? Xpp3Dom
        val pluginConfig = plugin.configuration as? Xpp3Dom
        val targetDir = (executionConfig ?: pluginConfig)
            ?.getChild("generator")
            ?.getChild("target")
            ?.getChild("directory")
            ?.value?.trim()
            ?: "${project.build?.directory ?: "target"}/generated-sources/jooq"
        if (targetDir.isNotEmpty()) {
            add(SourceRootData(toAbsolutePath(project, targetDir), "java-source"))
        }
    }
}

private fun MutableList<SourceRootData>.addQuerydslGeneratedSources(project: MavenProject) {
    val plugin = findPlugin(project, "com.querydsl", "querydsl-maven-plugin") ?: return
    addOutputDirectoryFromExecutions(
        project, plugin,
        matchingGoals = setOf("export", "process"),
        configChildName = "targetFolder",
        defaultRelativePath = "generated-sources/java",
        rootType = "java-source",
    )
}

private fun MutableList<SourceRootData>.addJavaccGeneratedSources(project: MavenProject) {
    val plugin = findPlugin(project, "org.codehaus.mojo", "javacc-maven-plugin") ?: return
    addOutputDirectoryFromExecutions(
        project, plugin,
        matchingGoals = setOf("javacc", "jjtree", "jjtree-javacc", "jtb", "jtb-javacc"),
        configChildName = "outputDirectory",
        defaultRelativePath = "generated-sources/javacc",
        rootType = "java-source",
    )
}

private fun MutableList<SourceRootData>.addJflexGeneratedSources(project: MavenProject) {
    val plugin = findPlugin(project, "de.jflex", "jflex-maven-plugin") ?: return
    addOutputDirectoryFromExecutions(
        project, plugin,
        matchingGoals = setOf("generate"),
        configChildName = "outputDirectory",
        defaultRelativePath = "generated-sources/jflex",
        rootType = "java-source",
    )
}

private fun MutableList<SourceRootData>.addThriftGeneratedSources(project: MavenProject, isTest: Boolean) {
    val plugin = findPlugin(project, "org.apache.thrift", "thrift-maven-plugin") ?: return
    addOutputDirectoryFromExecutions(
        project, plugin,
        matchingGoals = setOf(if (isTest) "testCompile" else "compile"),
        configChildName = "generatedSourcesDirectory",
        defaultRelativePath = if (isTest) "generated-test-sources/thrift" else "generated-sources/thrift",
        rootType = if (isTest) "java-test" else "java-source",
    )
}

private fun MutableList<SourceRootData>.addSwaggerGeneratedSources(project: MavenProject) {
    val plugin = findPlugin(project, "io.swagger.core.v3", "swagger-maven-plugin") ?: return
    plugin.executions.forEach { execution ->
        if (!execution.goals.contains("resolve")) return@forEach
        val executionConfig = execution.configuration as? Xpp3Dom
        val pluginConfig = plugin.configuration as? Xpp3Dom
        val outputPath = executionConfig?.getChild("outputPath")?.value?.trim()
            ?: pluginConfig?.getChild("outputPath")?.value?.trim()
            ?: "${project.build?.directory ?: "target"}/generated-sources/swagger"
        if (outputPath.isNotEmpty()) {
            add(SourceRootData(toAbsolutePath(project, outputPath), "java-resource"))
        }
    }
}

private fun MutableList<SourceRootData>.addOutputDirectoryFromExecutions(
    project: MavenProject,
    plugin: Plugin,
    matchingGoals: Set<String>,
    configChildName: String,
    defaultRelativePath: String,
    rootType: String,
) {
    plugin.executions.forEach { execution ->
        if (execution.goals.none { it in matchingGoals }) return@forEach
        val executionConfig = execution.configuration as? Xpp3Dom
        val pluginConfig = plugin.configuration as? Xpp3Dom
        val outputDir = executionConfig?.getChild(configChildName)?.value?.trim()
            ?: pluginConfig?.getChild(configChildName)?.value?.trim()
            ?: "${project.build?.directory ?: "target"}/$defaultRelativePath"
        if (outputDir.isNotEmpty()) {
            add(SourceRootData(toAbsolutePath(project, outputDir), rootType))
        }
    }
}
