// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.tests.integration

import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.project.ProjectInfoSpec
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.client.ClientKind
import com.intellij.openapi.components.BaseComponent
import com.intellij.openapi.components.impl.stores.IProjectStore
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.impl.ProjectStoreFactory
import com.intellij.project.ProjectStoreOwner
import com.intellij.workspaceModel.performanceTesting.extension.javaModuleSettings
import com.intellij.workspaceModel.performanceTesting.extension.modules
import com.intellij.workspaceModel.performanceTesting.helpers.JsonSerializer
import com.intellij.workspaceModel.performanceTesting.validator.models.LibraryDependencyEntityDto
import com.intellij.workspaceModel.performanceTesting.validator.models.LibraryRootEntityDto
import com.intellij.workspaceModel.performanceTesting.validator.models.ModuleDependencyEntityDto
import com.intellij.workspaceModel.performanceTesting.validator.models.ModuleEntityDto
import com.intellij.workspaceModel.performanceTesting.validator.models.ModuleEntityDtoFactory.moduleEntityToModuleEntityDto
import com.intellij.workspaceModel.performanceTesting.validator.models.ProjectStructureWithModules
import com.jetbrains.analyzer.api.AnalyzerFileSystems
import com.jetbrains.analyzer.api.defaultPluginSet
import com.jetbrains.analyzer.api.withAnalyzer
import com.jetbrains.analyzer.bootstrap.AnalyzerProjectId
import com.jetbrains.analyzer.bootstrap.WorkspaceModelSnapshot
import com.jetbrains.analyzer.bootstrap.analyzerProjectConfigForImport
import com.jetbrains.analyzer.bootstrap.readPluginDescriptor
import com.jetbrains.ls.imports.api.WorkspaceImporter
import com.jetbrains.ls.imports.downloadGradleBinaries
import com.jetbrains.ls.imports.downloadMavenBinaries
import com.jetbrains.ls.imports.gradle.GradleWorkspaceImporter
import com.jetbrains.ls.imports.jps.JpsWorkspaceImporter
import com.jetbrains.ls.imports.maven.MavenWorkspaceImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.SystemDependent
import org.jetbrains.annotations.Unmodifiable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.fail
import java.nio.file.Path
import kotlin.io.path.absolutePathString

private object Tests

fun gradleTest(
    testCase: TestCase<out ProjectInfoSpec>,
    projectStructureWithModules: ProjectStructureWithModules
) {
    downloadGradleBinaries().let { path ->
        GradleWorkspaceImporter.useGradleAndJava(path, Path.of(System.getProperty("java.home")))
    }

    doTest(testCase, projectStructureWithModules, GradleWorkspaceImporter)
}

internal fun mavenTest(
    testCase: TestCase<out ProjectInfoSpec>,
    projectStructureWithModules: ProjectStructureWithModules
) {
    val mavenPath = downloadMavenBinaries()
    MavenWorkspaceImporter.useMavenAndJava(mavenPath, Path.of(System.getProperty("java.home")))
    System.setProperty("forceM3Placeholder", "true")

    try {
        doTest(testCase, projectStructureWithModules, MavenWorkspaceImporter)
    } finally {
        System.clearProperty("forceM3Placeholder")
    }
}

internal fun jpsTest(testCase: TestCase<out ProjectInfoSpec>, projectStructureWithModules: ProjectStructureWithModules) {
    doTest(testCase, projectStructureWithModules, JpsWorkspaceImporter)
}

private fun doTest(
    testCase: TestCase<out ProjectInfoSpec>,
    projectStructureWithModules: ProjectStructureWithModules,
    importer: WorkspaceImporter
) {
    val projectDir = testCase.projectInfo.downloadAndUnpackProject() ?: fail("Couldn't download the project")

    runBlocking(Dispatchers.Default) {
        withAnalyzer(isUnitTestMode = true) { analyzer ->
            val currentSnapshot = WorkspaceModelSnapshot.empty()
            val virtualFileUrlManager = currentSnapshot.virtualFileUrlManager
            analyzer.withProject(
                analyzerProjectConfigForImport(
                    fileSystems = AnalyzerFileSystems.default(),
                    projectId = AnalyzerProjectId(),
                    entities = currentSnapshot.entityStore,
                    urlManager = virtualFileUrlManager,
                    pluginSet = defaultPluginSet(
                        listOf(
                            readPluginDescriptor(Tests::class.java, "/META-INF/fleet/analyzer/test-import.xml"),
                        )
                    )
                )
            ) { analyzerProject ->
                val originalProject = analyzerProject.project

                val projectWithStore = object : Project by originalProject, ProjectStoreOwner {
                    override val componentStore: IProjectStore =
                        ApplicationManager.getApplication().service<ProjectStoreFactory>().createStore(this).also {
                            it.setPath(projectDir)
                        }

                    override fun getPresentableUrl(): @SystemDependent @NonNls String? =
                        originalProject.getPresentableUrl()

                    override fun scheduleSave() {
                        originalProject.scheduleSave()
                    }

                    override fun isDefault(): Boolean =
                        originalProject.isDefault()

                    override fun getComponent(name: String): BaseComponent? =
                        originalProject.getComponent(name)

                    override fun <T> getServiceForClient(serviceClass: Class<T?>): T? =
                        originalProject.getServiceForClient(serviceClass)

                    override fun <T> getServices(
                        serviceClass: Class<T?>,
                        client: ClientKind?
                    ): @Unmodifiable List<T?> =
                        originalProject.getServices(serviceClass, client)

                    override fun <T> getServiceIfCreated(serviceClass: Class<T?>): T? =
                        originalProject.getServiceIfCreated(serviceClass)

                    override fun logError(error: Throwable, pluginId: PluginId) {
                        originalProject.logError(error, pluginId)
                    }
                }

                val storage = importer.importWorkspace(projectWithStore, projectDir, virtualFileUrlManager) {}
                    ?: fail("Workspace import failed")

                val expectedModules = projectStructureWithModules.modules
                System.setProperty("com.intellij.workspaceModel.performanceTesting.extension", projectDir.absolutePathString())
                val actualModules = try {

                    storage.modules.map { module ->
                        moduleEntityToModuleEntityDto(
                            module,
                            storage.javaModuleSettings.firstOrNull { it.module == module },
                            projectWithStore,
                            storage,
                            true
                        )
                    }.toList()

                } finally {
                    System.setProperty("com.intellij.workspaceModel.performanceTesting.extension", "")
                }

                reportDifferences(expectedModules, actualModules)

                assertEquals(
                    JsonSerializer.serializePretty(normalize(expectedModules)),
                    JsonSerializer.serializePretty(normalize(actualModules))
                )
            }
        }
    }
}

private fun reportDifferences(expected: List<ModuleEntityDto>, actual: List<ModuleEntityDto>) {
    val expectedMap = expected.associateBy { it.name }
    val actualMap = actual.associateBy { it.name }

    val expectedNames = expectedMap.keys
    val actualNames = actualMap.keys

    val missingModules = expectedNames - actualNames
    val extraModules = actualNames - expectedNames

    for (name in missingModules.sorted()) {
        System.err.println("Missing module: $name")
    }
    for (name in extraModules.sorted()) {
        System.err.println("Extra module found: $name")
    }

    val commonNames = (expectedNames intersect actualNames).sorted()
    for (name in commonNames) {
        val expectedModule = expectedMap[name]!!
        val actualModule = actualMap[name]!!
        compareModules(expectedModule, actualModule)
    }
}

private fun compareModules(expected: ModuleEntityDto, actual: ModuleEntityDto) {
    val prefix = "[Module ${expected.name}]"

    if (expected.moduleTypeId != actual.moduleTypeId) {
        System.err.println("$prefix moduleTypeId: expected <${expected.moduleTypeId}> but was <${actual.moduleTypeId}>")
    }

    compareSets(expected.contentDirs, actual.contentDirs, "$prefix contentDirs")
    compareSets(expected.sourceDirs, actual.sourceDirs, "$prefix sourceDirs")
    compareSets(expected.testSourceDirs, actual.testSourceDirs, "$prefix testSourceDirs")
    compareSets(expected.resourceDirs, actual.resourceDirs, "$prefix resourceDirs")
    compareSets(expected.testResourceDirs, actual.testResourceDirs, "$prefix testResourceDirs")
    compareSets(expected.generatedSourceDirs, actual.generatedSourceDirs, "$prefix generatedSourceDirs")
    compareSets(expected.excludeDirs, actual.excludeDirs, "$prefix excludeDirs")
    if (actual.totalDependenciesNumber != expected.totalDependenciesNumber) {
        System.err.println("$prefix totalDependenciesNumber: expected <${expected.totalDependenciesNumber}> but was <${actual.totalDependenciesNumber}>")
    }

    if (expected.outputDir != actual.outputDir) {
        System.err.println("$prefix outputDir: expected <${expected.outputDir}> but was <${actual.outputDir}>")
    }
    if (expected.testOutputDir != actual.testOutputDir) {
        System.err.println("$prefix testOutputDir: expected <${expected.testOutputDir}> but was <${actual.testOutputDir}>")
    }

    compareSets(expected.facetNames, actual.facetNames, "$prefix facetNames")

    if (expected.jdkName != actual.jdkName) {
        System.err.println("$prefix jdkName: expected <${expected.jdkName}> but was <${actual.jdkName}>")
    }
    if (expected.languageLevel != actual.languageLevel) {
        System.err.println("$prefix languageLevel: expected <${expected.languageLevel}> but was <${actual.languageLevel}>")
    }

    compareLibraries(expected.libraries, actual.libraries, prefix)
    compareModuleDependencies(expected.moduleDependencies, actual.moduleDependencies, prefix)
}

private fun compareSets(expected: Set<String>, actual: Set<String>, description: String) {
    val missing = expected - actual
    val extra = actual - expected

    if (missing.isNotEmpty()) {
        System.err.println("$description missing: $missing")
    }
    if (extra.isNotEmpty()) {
        System.err.println("$description extra: $extra")
    }
}

private fun compareLibraries(expected: List<LibraryDependencyEntityDto>, actual: List<LibraryDependencyEntityDto>, prefix: String) {
    val expectedMap = expected.associateBy { it.name }
    val actualMap = actual.associateBy { it.name }

    val allNames = (expectedMap.keys + actualMap.keys).sorted()

    for (name in allNames) {
        val expLib = expectedMap[name]
        val actLib = actualMap[name]

        val libPrefix = "$prefix Library $name"
        if (expLib == null) {
            System.err.println("$prefix Extra library found: $name")
        } else if (actLib == null) {
            System.err.println("$prefix Missing library: $name")
        } else {
            if (expLib.exported != actLib.exported) {
                System.err.println("$libPrefix exported: expected <${expLib.exported}> but was <${actLib.exported}>")
            }
            if (expLib.scope != actLib.scope) {
                System.err.println("$libPrefix scope: expected <${expLib.scope}> but was <${actLib.scope}>")
            }
            compareLibraryRoots(expLib.roots, actLib.roots, libPrefix)
        }
    }
}

private fun compareLibraryRoots(expected: List<LibraryRootEntityDto>, actual: List<LibraryRootEntityDto>, prefix: String) {
    val expectedSet = expected.toSet()
    val actualSet = actual.toSet()

    val missing = expectedSet - actualSet
    val extra = actualSet - expectedSet

    if (missing.isNotEmpty()) {
        System.err.println("$prefix missing roots: $missing")
    }
    if (extra.isNotEmpty()) {
        System.err.println("$prefix extra roots: $extra")
    }
}

private fun compareModuleDependencies(
    expected: List<ModuleDependencyEntityDto>,
    actual: List<ModuleDependencyEntityDto>,
    prefix: String
) {
    val expectedMap = expected.associateBy { it.name }
    val actualMap = actual.associateBy { it.name }

    val allNames = (expectedMap.keys + actualMap.keys).sorted()

    for (name in allNames) {
        val expDep = expectedMap[name]
        val actDep = actualMap[name]

        val depPrefix = "$prefix ModuleDependency $name"
        if (expDep == null) {
            System.err.println("$prefix Extra module dependency found: $name")
        } else if (actDep == null) {
            System.err.println("$prefix Missing module dependency: $name")
        } else {
            if (expDep.exported != actDep.exported) {
                System.err.println("$depPrefix exported: expected <${expDep.exported}> but was <${actDep.exported}>")
            }
            if (expDep.scope != actDep.scope) {
                System.err.println("$depPrefix scope: expected <${expDep.scope}> but was <${actDep.scope}>")
            }
            if (expDep.productionOnTest != actDep.productionOnTest) {
                System.err.println("$depPrefix productionOnTest: expected <${expDep.productionOnTest}> but was <${actDep.productionOnTest}>")
            }
        }
    }
}

private fun normalize(modules: List<ModuleEntityDto>): List<ModuleEntityDto> =
    modules.map { normalize(it) }.sortedBy { it.name }

private fun normalize(m: ModuleEntityDto): ModuleEntityDto = m.copy(
    contentDirs = m.contentDirs.sorted().toSet(),
    sourceDirs = m.sourceDirs.sorted().toSet(),
    resourceDirs = m.resourceDirs.sorted().toSet(),
    testSourceDirs = m.testSourceDirs.sorted().toSet(),
    testResourceDirs = m.testResourceDirs.sorted().toSet(),
    generatedSourceDirs = m.generatedSourceDirs.sorted().toSet(),
    excludeDirs = m.excludeDirs.sorted().toSet(),
    facetNames = m.facetNames.sorted().toSet(),
    libraries = m.libraries.sortedBy { it.name }.map { it.copy(roots = it.roots.sortedBy { root -> root.url }) },
    moduleDependencies = m.moduleDependencies.sortedBy { it.name },
)
