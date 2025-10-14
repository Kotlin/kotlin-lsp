// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.jps

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.fileLogger
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.impl.JavaHomeFinder
import com.intellij.openapi.projectRoots.impl.JavaSdkImpl
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.platform.eel.provider.getEelDescriptor
import com.intellij.platform.workspace.jps.entities.*
import com.intellij.platform.workspace.jps.entities.LibraryTableId.ProjectLibraryTableId
import com.intellij.platform.workspace.storage.InternalEnvironmentName
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.url.VirtualFileUrlManager
import com.intellij.workspaceModel.ide.impl.legacyBridge.sdk.SdkBridgeImpl
import com.intellij.workspaceModel.ide.impl.legacyBridge.sdk.SdkBridgeImpl.Companion.mutableSdkMap
import com.intellij.workspaceModel.ide.impl.legacyBridge.sdk.customName
import com.jetbrains.ls.imports.api.WorkspaceEntitySource
import com.jetbrains.ls.imports.api.WorkspaceImportException
import com.jetbrains.ls.imports.api.WorkspaceImporter
import com.jetbrains.ls.imports.utils.toIntellijUri
import org.jetbrains.jps.model.JpsElementFactory
import org.jetbrains.jps.model.JpsModel
import org.jetbrains.jps.model.java.*
import org.jetbrains.jps.model.library.JpsLibraryType
import org.jetbrains.jps.model.library.JpsOrderRootType
import org.jetbrains.jps.model.module.JpsLibraryDependency
import org.jetbrains.jps.model.module.JpsModuleDependency
import org.jetbrains.jps.model.module.JpsModuleSourceDependency
import org.jetbrains.jps.model.module.JpsSdkDependency
import org.jetbrains.jps.model.serialization.*
import org.jetbrains.jps.model.serialization.impl.JpsPathVariablesConfigurationImpl
import org.jetbrains.jps.util.JpsPathUtil
import org.jetbrains.kotlin.config.isHmpp
import org.jetbrains.kotlin.idea.facet.KotlinFacetType
import org.jetbrains.kotlin.idea.workspaceModel.KotlinSettingsEntity
import org.jetbrains.kotlin.idea.workspaceModel.kotlinSettings
import org.jetbrains.kotlin.idea.workspaceModel.toCompilerSettingsData
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.exists

private val LOG = fileLogger()

object JpsWorkspaceImporter : WorkspaceImporter {
    override suspend fun importWorkspace(
        projectDirectory: Path,
        virtualFileUrlManager: VirtualFileUrlManager,
        onUnresolvedDependency: (String) -> Unit,
    ): MutableEntityStorage = try {
        val model = JpsElementFactory.getInstance().createModel()
        initGlobalJpsOptions(model)
        val pathVariables = JpsModelSerializationDataService.computeAllPathVariables(model.global)
        JpsProjectLoader.loadProject(model.getProject(), pathVariables, model.getGlobal().getPathMapper(), projectDirectory, true, null)

        val storage = MutableEntityStorage.create()
        val entitySource = WorkspaceEntitySource(projectDirectory.toIntellijUri(virtualFileUrlManager))
        val libs = mutableSetOf<String>()
        val sdks = mutableSetOf<String>()

        model.project.modules.forEach { module ->
            val kotlinFacetModuleExtension = module.container.getChild(JpsKotlinFacetModuleExtension.KIND)
            val entity = ModuleEntity(
                name = module.name,
                dependencies = module.dependenciesList.dependencies.mapNotNull { dependency ->
                    val javaExtension = JpsJavaExtensionService.getInstance().getDependencyExtension(dependency)
                    when (dependency) {
                        is JpsLibraryDependency -> {
                            val library = dependency.library ?: return@mapNotNull null
                            if (libs.add(library.name)) {
                                val libEntity = LibraryEntity(
                                    name = library.name,
                                    tableId = ProjectLibraryTableId,
                                    roots = buildList {
                                        library.getRootUrls(JpsOrderRootType.COMPILED).mapNotNullTo(this) { url ->
                                            val fileUrl = virtualFileUrlManager.getOrCreateFromUrl(url)
                                            if (!Path.of(JpsPathUtil.urlToPath(url)).exists()) {
                                                onUnresolvedDependency(url)
                                                return@mapNotNull null
                                            }
                                            LibraryRoot(
                                                fileUrl,
                                                LibraryRootTypeId.COMPILED
                                            )
                                        }
                                        library.getRootUrls(JpsOrderRootType.SOURCES).mapTo(this) { url ->
                                            LibraryRoot(virtualFileUrlManager.getOrCreateFromUrl(url), LibraryRootTypeId.SOURCES)
                                        }
                                    },
                                    entitySource = entitySource
                                ) {
                                    typeId = LibraryTypeId(library.type.javaClass.simpleName)
                                }
                                storage addEntity libEntity
                            }
                            LibraryDependency(
                                library = LibraryId(library.name, ProjectLibraryTableId),
                                exported = javaExtension?.isExported == true,
                                scope = DependencyScope.valueOf(javaExtension?.scope?.name ?: "COMPILE")
                            )
                        }
                        is JpsModuleDependency -> {
                            val module = dependency.module ?: return@mapNotNull null
                            ModuleDependency(
                                module = ModuleId(module.name),
                                exported = javaExtension?.isExported == true,
                                scope = DependencyScope.valueOf(javaExtension?.scope?.name ?: "COMPILE"),
                                productionOnTest = false
                            )
                        }
                        is JpsSdkDependency -> {
                            val sdkReference = dependency.sdkReference ?: return@mapNotNull null
                            sdks.add(sdkReference.sdkName)
                            SdkDependency(
                                sdk = SdkId(
                                    name = sdkReference.sdkName,
                                    type = dependency.sdkType.toSdkType()
                                )
                            )
                        }
                        is JpsModuleSourceDependency -> ModuleSourceDependency
                        else -> null
                    }
                },
                entitySource = entitySource
            ) {
                this.type = ModuleTypeId(with(module.moduleType) {
                    when (this) {
                        is JpsJavaModuleType -> "JAVA_MODULE"
                        else -> javaClass.toString()
                    }
                })
                this.contentRoots = module.contentRootsList.urls.mapNotNull { rootUrl ->
                    ContentRootEntity(
                        rootUrl.toIntellijUri(virtualFileUrlManager),
                        module.excludePatterns
                            .filter { rootUrl.startsWith(it.baseDirUrl) || it.baseDirUrl.startsWith(rootUrl) }
                            .map { it.pattern },
                        entitySource
                    ) {
                        this.module = this@ModuleEntity
                        this.sourceRoots = module.sourceRoots.filter { it.url.startsWith(rootUrl) }.map {
                            SourceRootEntity(
                                url = it.url.toIntellijUri(virtualFileUrlManager),
                                rootTypeId = SourceRootTypeId(
                                    with(it.rootType) {
                                        when (this) {
                                            is JavaSourceRootType -> if (isForTests) "java-test" else "java-source"
                                            is JavaResourceRootType -> if (isForTests) "java-test-resource" else "java-resource"
                                            else -> this.toString()
                                        }
                                    }),
                                entitySource = entitySource
                            ) {
                                this.contentRoot = this@ContentRootEntity
                            }

                        }
                        this.excludedUrls = module.excludeRootsList.urls.filter { it.startsWith(rootUrl) }.map {
                            ExcludeUrlEntity(
                                url = it.toIntellijUri(virtualFileUrlManager),
                                entitySource = entitySource
                            )
                        }
                    }
                }
                if (kotlinFacetModuleExtension != null) {
                    val settings = kotlinFacetModuleExtension.settings
                    this.kotlinSettings = listOf(
                        KotlinSettingsEntity(
                            name = KotlinFacetType.INSTANCE.presentableName,
                            moduleId = ModuleId(module.name),
                            sourceRoots = emptyList(),
                            configFileItems = emptyList(),
                            useProjectSettings = settings.useProjectSettings,
                            implementedModuleNames = settings.implementedModuleNames,
                            dependsOnModuleNames = settings.dependsOnModuleNames,
                            additionalVisibleModuleNames = settings.additionalVisibleModuleNames,
                            sourceSetNames = settings.sourceSetNames,
                            isTestModule = settings.isTestModule,
                            externalProjectId = settings.externalProjectId,
                            isHmppEnabled = settings.mppVersion.isHmpp,
                            pureKotlinSourceFolders = settings.pureKotlinSourceFolders,
                            kind = settings.kind,
                            externalSystemRunTasks = emptyList(),
                            version = settings.version,
                            flushNeeded = false,
                            entitySource = entitySource
                        ) {
                            this.compilerSettings = settings.compilerSettings.toCompilerSettingsData()
                            this.targetPlatform = settings.targetPlatform.toString()
                        }
                    )
                }
            }
            storage addEntity entity
        }
        if (model.global.libraryCollection.libraries.isEmpty()) {
            detectJavaSdks(projectDirectory, sdks, virtualFileUrlManager, entitySource).forEach { builder ->
                val entity = storage addEntity builder
                // for KaLibrarySdkModuleImpl
                storage.mutableSdkMap.addMapping(entity, SdkBridgeImpl(builder, InternalEnvironmentName.Local))
            }
        }
        model.global.libraryCollection.libraries.forEach { library ->
            if (!sdks.contains(library.name)) return@forEach
            when (library.type) {
                is JpsJavaSdkType -> {
                    val builder = SdkEntity(
                        name = library.name,
                        type = library.type.toSdkType(),
                        roots = buildList {
                            library.getRootUrls(JpsOrderRootType.COMPILED).mapNotNullTo(this) { url ->
                                val url = url.run { if (startsWith("jrt://")) replace("!/", "!/modules/") else this }
                                SdkRoot(
                                    virtualFileUrlManager.getOrCreateFromUrl(url),
                                    SdkRootTypeId(OrderRootType.CLASSES.customName),
                                )
                            }
                            library.getRootUrls(JpsOrderRootType.SOURCES).mapNotNullTo(this) { url ->
                                SdkRoot(
                                    virtualFileUrlManager.getOrCreateFromUrl(url),
                                    SdkRootTypeId(OrderRootType.SOURCES.customName),
                                )
                            }
                        },
                        additionalData = "",
                        entitySource = entitySource
                    )
                    val entity = storage addEntity builder
                    // for KaLibrarySdkModuleImpl
                    storage.mutableSdkMap.addMapping(entity, SdkBridgeImpl(builder, InternalEnvironmentName.Local))
                }

                is JpsLibraryType -> {
                }
            }
        }
        storage

    } catch (e: IOException) {
        throw WorkspaceImportException(
            "Error parsing workspace.json",
            "Error parsing workspace.json:\n ${e.message ?: e.stackTraceToString()}"
        )
    } catch (e: Exception) {
        throw e
    }

    override fun isApplicableDirectory(projectDirectory: Path): Boolean {
        return (projectDirectory / ".idea" / "modules.xml").exists()
    }
}

private fun detectJavaSdks(
    projectDirectory: Path,
    sdks: Collection<String>,
    virtualFileUrlManager: VirtualFileUrlManager,
    entitySource: WorkspaceEntitySource,
): List<SdkEntity.Builder> {
    val detectedSdks = JavaHomeFinder.findJdks(projectDirectory.getEelDescriptor(), false)
    if (detectedSdks.isEmpty()) return emptyList()
    return sdks.map { sdkName ->
        val sdk = detectedSdks.find {
            val suggestedName = it.versionInfo?.suggestedName()
            suggestedName != null && sdkName.contains(suggestedName, ignoreCase = true)
        } ?: detectedSdks.maxBy { it.versionInfo?.version?.feature ?: 0 }
        LOG.info("Detected SDK [$sdkName]: ${sdk.path}")
        val classRoots = JavaSdkImpl.findClasses(Path.of(sdk.path), false)
            .map { (it.replace("!/", "!/modules/")) }
        SdkEntity(
            name = sdkName,
            type = JavaSdk.getInstance().name,
            roots = buildList {
                classRoots.mapTo(this) {
                    SdkRoot(
                        virtualFileUrlManager.getOrCreateFromUrl(it),
                        SdkRootTypeId(OrderRootType.CLASSES.customName),
                    )
                }
                val srcZip = Path.of(sdk.path, "lib", "src.zip")
                if (srcZip.exists()) {
                    val prefix = "jar://${FileUtilRt.toSystemIndependentName(srcZip.toString())}!/"
                    classRoots.mapTo(this) {
                        SdkRoot(
                            virtualFileUrlManager.getOrCreateFromUrl("$prefix${it.substringAfterLast("/")}"),
                            SdkRootTypeId(OrderRootType.SOURCES.customName),
                        )
                    }
                }
            },
            additionalData = "",
            entitySource = entitySource
        )
    }
}

private fun initGlobalJpsOptions(model: JpsModel) {
    System.getProperty("idea.config.path.original")?.let {
        val optionsDir = Path.of(it) / PathManager.OPTIONS_DIRECTORY
        JpsGlobalSettingsLoading.loadGlobalSettings(model.global, optionsDir)
        return
    }
    val configuration = model.global.getContainer().setChild(
        JpsGlobalLoader.PATH_VARIABLES_ROLE, JpsPathVariablesConfigurationImpl()
    )
    val mavenPath = JpsMavenSettings.getMavenRepositoryPath()
    configuration.addPathVariable("MAVEN_REPOSITORY", mavenPath)
    LOG.info("Detected Maven repo: $mavenPath")
}

private fun JpsLibraryType<*>.toSdkType(): String = when (this) {
    is JpsJavaSdkType -> JavaSdk.getInstance().name
    else -> toString()
}