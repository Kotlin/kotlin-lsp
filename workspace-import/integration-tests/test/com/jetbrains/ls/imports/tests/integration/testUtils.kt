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
import com.intellij.platform.testFramework.core.FileComparisonFailedError
import com.intellij.project.ProjectStoreOwner
import com.intellij.workspaceModel.performanceTesting.extension.javaModuleSettings
import com.intellij.workspaceModel.performanceTesting.extension.modules
import com.intellij.workspaceModel.performanceTesting.helpers.JsonSerializer
import com.intellij.workspaceModel.performanceTesting.validator.models.ModuleEntityDto
import com.intellij.workspaceModel.performanceTesting.validator.models.ModuleEntityDtoFactory.moduleEntityToModuleEntityDto
import com.intellij.workspaceModel.performanceTesting.validator.models.ProjectStructureWithModules
import com.jetbrains.analyzer.api.AnalyzerFileSystems
import com.jetbrains.analyzer.api.withAnalyzer
import com.jetbrains.analyzer.bootstrap.AnalyzerProjectId
import com.jetbrains.analyzer.bootstrap.WorkspaceModelSnapshot
import com.jetbrains.analyzer.bootstrap.analyzerProjectConfigForImport
import com.jetbrains.analyzer.bootstrap.pluginSet
import com.jetbrains.analyzer.plugins.makePlugin
import com.jetbrains.ls.imports.api.WorkspaceImportException
import com.jetbrains.ls.imports.api.WorkspaceImportProgressReporter
import com.jetbrains.ls.imports.api.WorkspaceImporter
import com.jetbrains.ls.imports.downloadGradleBinaries
import com.jetbrains.ls.imports.downloadMavenBinaries
import com.jetbrains.ls.imports.gradle.GradleWorkspaceImporter
import com.jetbrains.ls.imports.jps.JpsWorkspaceImporter
import com.jetbrains.ls.imports.maven.MavenWorkspaceImporter
import com.jetbrains.ls.test.api.projectTests.projects.LspTestProjectFromGitHub
import com.jetbrains.ls.test.api.utils.testApplicationInits
import com.jetbrains.ls.test.api.utils.testPluginSet
import com.jetbrains.ls.test.api.utils.testProjectInits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.SystemDependent
import org.jetbrains.annotations.Unmodifiable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.fail
import java.nio.file.Path
import kotlin.io.path.absolutePathString

private val gradleDistributiveChecksumRegex: Regex = "/dists/gradle-[^/]+/([a-z0-9]{20,})(?=/)".toRegex()

fun withIgnoringNonClassesRoots(list: List<ModuleEntityDto>): List<ModuleEntityDto> {
    return list.map { module ->
        module.copy(
            libraries = module.libraries.map { lib ->
                lib.copy(
                    roots = lib.roots.filter { it.type.name == "CLASSES" }
                )
            }
        )
    }
}

// This is necessary because the hash in the Gradle distribution path is based on the URL from which the artifact was downloaded,
// and independent of the contents of the artifact.
fun withIgnoringGradleDistributiveChecksum(list: List<ModuleEntityDto>): List<ModuleEntityDto> {
    return list.map { module ->
        module.copy(
            libraries = module.libraries.map { lib ->
                lib.copy(
                    roots = lib.roots.map {
                        it.copy(
                            url = gradleDistributiveChecksumRegex.replaceFirst(it.url, "DISTRIBUTIVE_SOURCE_HASH")
                        )
                    }
                )
            }
        )
    }
}

fun gradleTest(
    testCase: TestCase<out ProjectInfoSpec>,
    projectStructureWithModules: ProjectStructureWithModules,
    resultMapper: (List<ModuleEntityDto>) -> List<ModuleEntityDto> = { it }
) {
    downloadGradleBinaries()
    System.setProperty("useNaiveGradleRepoSubstitution", "true")
    try {
        val projectDir = testCase.projectInfo.downloadAndUnpackProject() ?: fail("Cannot unpack project")
        doTest(projectDir, projectStructureWithModules, GradleWorkspaceImporter, null, resultMapper)
    } finally {
        System.clearProperty("useNaiveGradleRepoSubstitution")
    }
}

internal fun mavenTest(testCase: LspTestProjectFromGitHub, expected: LspTestData) {
    val projectDir = testCase.project.downloadAndUnpackProject()
    mavenTest(projectDir, expected, ::withIgnoringNonClassesRoots)

}

internal fun mavenTest(
    testCase: TestCase<out ProjectInfoSpec>,
    expected: LspTestData
) {
    val projectDir = testCase.projectInfo.downloadAndUnpackProject() ?: fail("Cannot unpack project")
    mavenTest(projectDir, expected, ::withIgnoringNonClassesRoots)
}

private fun mavenTest(
    projectDir: Path,
    expected: LspTestData,
    resultMapper: (List<ModuleEntityDto>) -> List<ModuleEntityDto> = { it }
) {
    val mavenPath = downloadMavenBinaries()
    MavenWorkspaceImporter.useMavenAndJava(mavenPath, Path.of(System.getProperty("java.home")))
    System.setProperty("forceM3Placeholder", "true")

    try {
        doTest(projectDir, expected.getStructure(), MavenWorkspaceImporter, expected.getFile(), resultMapper)
    } finally {
        System.clearProperty("forceM3Placeholder")
    }
}

internal fun jpsTest(testCase: TestCase<out ProjectInfoSpec>, projectStructureWithModules: ProjectStructureWithModules) {
    val projectDir = testCase.projectInfo.downloadAndUnpackProject() ?: fail("Couldn't download the project")
    doTest(projectDir, projectStructureWithModules, JpsWorkspaceImporter)
}

private fun doTest(
    projectDir: Path,
    expectedStructure: ProjectStructureWithModules,
    importer: WorkspaceImporter,
    expectedFileData: Path? = null,
    resultMapper: (List<ModuleEntityDto>) -> List<ModuleEntityDto> = { it }
): List<ModuleEntityDto> = runBlocking(Dispatchers.Default) {
    withAnalyzer(isUnitTestMode = true) { analyzer ->
        val currentSnapshot = WorkspaceModelSnapshot.empty()
        val virtualFileUrlManager = currentSnapshot.virtualFileUrlManager
        return@withAnalyzer analyzer.withProject(
            analyzerProjectConfigForImport(
                fileSystems = AnalyzerFileSystems.default(),
                projectId = AnalyzerProjectId(),
                entities = currentSnapshot.entityStore,
                urlManager = virtualFileUrlManager,
                pluginSet = pluginSet(
                    listOf(
                        makePlugin(
                            "com.jetbrains.fleet.analyzer.test-import",
                            mapOf("com.jetbrains.fleet.analyzer.test-import" to "/META-INF/fleet/analyzer/test-import.xml")
                        )
                    ) + testPluginSet.allPlugins
                ),
                applicationInits = testApplicationInits,
                projectInits = testProjectInits,
            )
        ) { analyzerProject ->
            val originalProject = analyzerProject.project

            val projectWithStore = TestProject(originalProject, projectDir)
            val progressReporter = SavingProgressReporter()
            val storage = try {
                importer.importWorkspace(projectWithStore, projectDir, null, virtualFileUrlManager, progressReporter)
                    ?: fail("Workspace import failed: $progressReporter")
            } catch (e: WorkspaceImportException) {
                fail(e.logMessage + progressReporter.toString())
            }

            val expectedModules = expectedStructure.modules
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


            val expected = JsonSerializer.serializePretty(normalize(resultMapper(expectedModules)))
            val actual = JsonSerializer.serializePretty(normalize(resultMapper(actualModules)))
            if (expectedFileData == null) {
                assertEquals(expected, actual)
            } else {
                if (expected != actual) {
                    throw FileComparisonFailedError(
                        "Workspace Model does not match", expected, actual,
                        expectedFileData.toString(),
                        null
                    )
                }
            }

            actualModules
        }
    }
}


class TestProject(val originalProject: Project, val projectDir: Path) : Project by originalProject, ProjectStoreOwner {
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

class SavingProgressReporter : WorkspaceImportProgressReporter {
    val unresolvedDeps = ArrayList<String>()
    val output = StringBuilder()
    override fun onUnresolvedDependency(depName: String) {
        unresolvedDeps.add(depName)
    }

    override fun onStdOutput(line: String) {
        println(line)
        output.append(line).append("\n")
    }

    override fun onErrorOutput(line: String) {
        System.err.println(line)
        output.append(line).append("\n")
    }

    override fun toString(): String {
        return output.toString() +
                "\r\n------------------------------------------" +
                "UNRESOLVED: $unresolvedDeps" +
                "\r\n------------------------------------------"
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
