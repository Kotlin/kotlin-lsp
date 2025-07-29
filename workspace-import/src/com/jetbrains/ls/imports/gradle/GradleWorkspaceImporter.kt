// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle

import com.intellij.platform.workspace.jps.entities.*
import com.intellij.platform.workspace.jps.entities.LibraryTableId.ProjectLibraryTableId
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.url.VirtualFileUrlManager
import com.jetbrains.ls.imports.api.WorkspaceEntitySource
import com.jetbrains.ls.imports.api.WorkspaceImportException
import com.jetbrains.ls.imports.api.WorkspaceImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.model.DomainObjectSet
import org.gradle.tooling.model.idea.*
import org.jetbrains.kotlin.config.KotlinFacetSettings
import org.jetbrains.kotlin.config.KotlinModuleKind
import org.jetbrains.kotlin.idea.facet.KotlinFacetType
import org.jetbrains.kotlin.idea.workspaceModel.KotlinSettingsEntity
import org.jetbrains.kotlin.idea.workspaceModel.kotlinSettings
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.exists
import com.jetbrains.ls.imports.utils.toIntellijUri
import java.io.ByteArrayOutputStream

object GradleWorkspaceImporter : WorkspaceImporter {
    override suspend fun importWorkspace(
        projectDirectory: Path,
        virtualFileUrlManager: VirtualFileUrlManager,
        onUnresolvedDependency: (String) -> Unit,
    ): MutableEntityStorage {
        val storage = try {
            withContext(Dispatchers.IO) {
                val connector = GradleConnector.newConnector().forProjectDirectory(projectDirectory.toFile())

                connector.connect().use { connection ->
                    val ideaProject = connection.getModel(IdeaProject::class.java)
                    val storage = MutableEntityStorage.create()
                    val entitySource = WorkspaceEntitySource(projectDirectory.toIntellijUri(virtualFileUrlManager))
                    val libs = mutableSetOf<String>()

                    ideaProject.modules.forEach { module ->
                        for (isMain in arrayOf(true, false)) {
                            val entity = ModuleEntity(
                                name = module.moduleName(isMain),
                                dependencies = buildList {
                                    module.dependencies.mapNotNullTo(this) { dependency ->
                                        if (isMain && dependency.scope.scope == "TEST") return@mapNotNullTo null
                                        when (dependency) {
                                            is IdeaSingleEntryLibraryDependency -> {
                                                val ver = dependency.gradleModuleVersion ?: return@mapNotNullTo null
                                                val name = if (ver.version.isNotEmpty())
                                                    "Gradle: ${ver.group}:${ver.name}:${ver.version}"
                                                else
                                                    "Gradle: ${ver.group}:${ver.name}"
                                                if (libs.add(name)) {
                                                    val libEntity = LibraryEntity(
                                                        name = name,
                                                        tableId = ProjectLibraryTableId,
                                                        roots = listOfNotNull(
                                                            LibraryRoot(
                                                                dependency.file.toPath().toIntellijUri(virtualFileUrlManager),
                                                                LibraryRootTypeId.COMPILED
                                                            ),
                                                            dependency.source?.let {
                                                                LibraryRoot(
                                                                    it.toPath().toIntellijUri(virtualFileUrlManager),
                                                                    LibraryRootTypeId.SOURCES
                                                                )
                                                            }
                                                        ),
                                                        entitySource = entitySource
                                                    ) {
                                                        typeId = LibraryTypeId("java-imported")
                                                    }
                                                    storage addEntity libEntity
                                                    storage addEntity LibraryPropertiesEntity(entitySource) {
                                                        propertiesXmlTag =
                                                            "<properties groupId=\"${ver.group}\" artifactId=\"${ver.name}\" version=\"${ver.version}\" baseVersion=\"${ver.version}\" />"
                                                        library = libEntity
                                                    }
                                                }
                                                LibraryDependency(
                                                    library = LibraryId(name, ProjectLibraryTableId),
                                                    exported = dependency.exported,
                                                    scope = DependencyScope.valueOf(dependency.scope.scope)
                                                )
                                            }

                                            is IdeaModuleDependency -> ModuleDependency(
                                                module = ModuleId(dependency.targetModuleName + ".main"),
                                                exported = dependency.exported,
                                                scope = DependencyScope.valueOf(dependency.scope.scope),
                                                productionOnTest = false
                                            )

                                            else -> null
                                        }
                                    }
                                    add(ModuleSourceDependency)
                                    module.jdkName.let { jdkName ->
                                        if (jdkName != null) add(SdkDependency(SdkId(jdkName, "JavaSDK")))
                                        else add(InheritedSdkDependency)
                                    }
                                    if (!isMain) {
                                        add(
                                            ModuleDependency(
                                                module = ModuleId(module.moduleName(isMain = true)),
                                                exported = false,
                                                scope = DependencyScope.COMPILE,
                                                productionOnTest = false
                                            )
                                        )
                                    }
                                },
                                entitySource = entitySource
                            ) {
                                createFacet(module, isMain = isMain)?.let { facet -> kotlinSettings += facet }
                                this.type = ModuleTypeId("JAVA_MODULE")
                                this.contentRoots = module.contentRoots.mapNotNull { root ->
                                    val rootPath = root.rootDirectory.toPath()
                                    if (!rootPath.exists()) return@mapNotNull null
                                    ContentRootEntity(
                                        rootPath.toIntellijUri(virtualFileUrlManager),
                                        listOf(),
                                        entitySource
                                    ) {
                                        fun sourceRoots(
                                            rootType: String,
                                            directories: DomainObjectSet<out IdeaSourceDirectory>
                                        ): List<SourceRootEntity.Builder> =
                                            directories.mapNotNull { sourceDirectory ->
                                                val sourceRoot = sourceDirectory.directory.toPath()
                                                if (!sourceRoot.exists()) return@mapNotNull null
                                                SourceRootEntity(
                                                    url = sourceRoot.toIntellijUri(virtualFileUrlManager),
                                                    rootTypeId = SourceRootTypeId(rootType),
                                                    entitySource = entitySource
                                                ) {
                                                    this.contentRoot = this@ContentRootEntity
                                                }
                                            }

                                        this.sourceRoots = if (isMain)
                                            sourceRoots("java-source", root.sourceDirectories) +
                                                    sourceRoots("java-resource", root.resourceDirectories)
                                        else
                                            sourceRoots("java-test", root.testDirectories) +
                                                    sourceRoots("java-test-resource", root.testResourceDirectories)

                                        this.excludedUrls = root.excludeDirectories.map {
                                            ExcludeUrlEntity(
                                                url = it.toPath().toIntellijUri(virtualFileUrlManager),
                                                entitySource = entitySource
                                            )
                                        }

                                        this.module = this@ModuleEntity
                                    }
                                }
                            }

                            storage addEntity entity
                        }
                    }
                    val out = ByteArrayOutputStream()
                    connection.newBuild().forTasks("dependencies")
                        .setStandardOutput(out)
                        .run()
                    val output = out.toString()
                    output.lines()
                        .filter { it.endsWith(" FAILED") }
                        .distinct()
                        .map { it.removeSuffix(" FAILED").substringAfterLast(' ') }
                        .forEach { onUnresolvedDependency(it) }
                    storage
                }
            }

        } catch (e: Throwable) {
            handleError(e)
        }
        return storage
    }

    private fun ModuleEntity.Builder.createFacet(module: IdeaModule, isMain: Boolean): KotlinSettingsEntity.Builder? {
        return KotlinSettingsEntity(
            name = KotlinFacetType.INSTANCE.presentableName,
            moduleId = ModuleId(module.moduleName(isMain)),
            sourceRoots = emptyList(),
            configFileItems = emptyList(),
            useProjectSettings = true,
            implementedModuleNames = emptyList(),
            dependsOnModuleNames = emptyList(),
            additionalVisibleModuleNames = buildSet {
                if (!isMain) {
                    add(module.moduleName(isMain = true))
                }
            },
            sourceSetNames = emptyList(),
            isTestModule = true,
            externalProjectId = "",
            isHmppEnabled = true, // always enabled
            pureKotlinSourceFolders = emptyList(),
            kind = KotlinModuleKind.DEFAULT,
            externalSystemRunTasks = emptyList(),
            version = KotlinFacetSettings.CURRENT_VERSION,
            flushNeeded = false,
            entitySource = entitySource
        )
    }

    private fun IdeaModule.moduleName(isMain: Boolean): String {
        return if (isMain) "${this.name}.main" else "${this.name}.test"
    }

    override fun isApplicableDirectory(projectDirectory: Path): Boolean {
        return listOf(
            "build.gradle",
            "build.gradle.kts",
            "settings.gradle",
            "settings.gradle.kts"
        ).any { (projectDirectory / it).exists() }
    }

    fun handleError(e: Throwable): Nothing {
        var err: Throwable? = e
        while (err != null) {
            if (err::class.qualifiedName == "org.gradle.internal.exceptions.LocationAwareException" && err.message != null) {
                val message = try {
                    val source = (err::class.java.getMethod("getSourceDisplayName").invoke(err) as String).removePrefix("build file ")
                    val lineNumber = err::class.java.getMethod("getLineNumber").invoke(err) as Int
                    "Gradle import failed at $source line $lineNumber."
                } catch (_: Exception) {
                    break
                }
                throw WorkspaceImportException(message, "Gradle import failed:\n${err.message}")
            }
            err = err.cause
        }
        throw e
    }
}
