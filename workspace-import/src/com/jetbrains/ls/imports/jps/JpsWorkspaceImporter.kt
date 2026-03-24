// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.jps

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.ExpandMacroToPathMap
import com.intellij.openapi.components.impl.ProjectWidePathMacroContributor
import com.intellij.openapi.diagnostic.fileLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.impl.JavaHomeFinder
import com.intellij.openapi.projectRoots.impl.JavaHomeFinder.getFinder
import com.intellij.openapi.projectRoots.impl.JavaSdkImpl
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.platform.eel.provider.getEelDescriptor
import com.intellij.platform.workspace.jps.entities.ContentRootEntity
import com.intellij.platform.workspace.jps.entities.DependencyScope
import com.intellij.platform.workspace.jps.entities.ExcludeUrlEntity
import com.intellij.platform.workspace.jps.entities.LibraryDependency
import com.intellij.platform.workspace.jps.entities.LibraryEntity
import com.intellij.platform.workspace.jps.entities.LibraryId
import com.intellij.platform.workspace.jps.entities.LibraryRoot
import com.intellij.platform.workspace.jps.entities.LibraryRootTypeId
import com.intellij.platform.workspace.jps.entities.LibraryTableId.ProjectLibraryTableId
import com.intellij.platform.workspace.jps.entities.LibraryTypeId
import com.intellij.platform.workspace.jps.entities.ModuleDependency
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.jps.entities.ModuleId
import com.intellij.platform.workspace.jps.entities.ModuleSourceDependency
import com.intellij.platform.workspace.jps.entities.ModuleTypeId
import com.intellij.platform.workspace.jps.entities.SdkDependency
import com.intellij.platform.workspace.jps.entities.SdkEntity
import com.intellij.platform.workspace.jps.entities.SdkEntityBuilder
import com.intellij.platform.workspace.jps.entities.SdkId
import com.intellij.platform.workspace.jps.entities.SdkRoot
import com.intellij.platform.workspace.jps.entities.SdkRootTypeId
import com.intellij.platform.workspace.jps.entities.SourceRootEntity
import com.intellij.platform.workspace.jps.entities.SourceRootTypeId
import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.url.VirtualFileUrlManager
import com.intellij.util.containers.nullize
import com.intellij.util.lang.JavaVersion
import com.jetbrains.ls.imports.api.WorkspaceEntitySource
import com.jetbrains.ls.imports.api.WorkspaceImportException
import com.jetbrains.ls.imports.api.WorkspaceImportProgressReporter
import com.jetbrains.ls.imports.api.WorkspaceImporter
import com.jetbrains.ls.imports.utils.toIntellijUri
import org.jetbrains.jps.model.JpsElementFactory
import org.jetbrains.jps.model.JpsModel
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.jetbrains.jps.model.java.JavaSourceRootType
import org.jetbrains.jps.model.java.JpsJavaExtensionService
import org.jetbrains.jps.model.java.JpsJavaModuleType
import org.jetbrains.jps.model.java.JpsJavaSdkType
import org.jetbrains.jps.model.library.JpsLibraryType
import org.jetbrains.jps.model.library.JpsOrderRootType
import org.jetbrains.jps.model.module.JpsLibraryDependency
import org.jetbrains.jps.model.module.JpsModuleDependency
import org.jetbrains.jps.model.module.JpsModuleSourceDependency
import org.jetbrains.jps.model.module.JpsSdkDependency
import org.jetbrains.jps.model.serialization.JpsGlobalLoader
import org.jetbrains.jps.model.serialization.JpsGlobalSettingsLoading
import org.jetbrains.jps.model.serialization.JpsMavenSettings
import org.jetbrains.jps.model.serialization.JpsModelSerializationDataService
import org.jetbrains.jps.model.serialization.JpsProjectLoader
import org.jetbrains.jps.model.serialization.impl.JpsPathVariablesConfigurationImpl
import org.jetbrains.jps.util.JpsPathUtil
import org.jetbrains.kotlin.cli.common.arguments.copyOf
import org.jetbrains.kotlin.config.isHmpp
import org.jetbrains.kotlin.idea.facet.KotlinFacetType
import org.jetbrains.kotlin.idea.workspaceModel.CompilerArgumentsSerializer
import org.jetbrains.kotlin.idea.workspaceModel.KotlinSettingsEntity
import org.jetbrains.kotlin.idea.workspaceModel.kotlinSettings
import org.jetbrains.kotlin.idea.workspaceModel.toCompilerSettingsData
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.div
import kotlin.io.path.exists

private val LOG = fileLogger()

object JpsWorkspaceImporter : WorkspaceImporter {
    override suspend fun importWorkspace(
        project: Project,
        projectDirectory: Path,
        defaultSdkPath: Path?,
        virtualFileUrlManager: VirtualFileUrlManager,
        progress: WorkspaceImportProgressReporter
    ): EntityStorage? {
        if (!isApplicableDirectory(projectDirectory)) return null
        return try {
            if (!isApplicableDirectory(projectDirectory)) return null
            val model = JpsElementFactory.getInstance().createModel()
            initGlobalJpsOptions(model)
            val pathVariables = JpsModelSerializationDataService.computeAllPathVariables(model.global)
            JpsProjectLoader.loadProject(model.getProject(), pathVariables, model.getGlobal().getPathMapper(), projectDirectory, true, null)

            val storage = MutableEntityStorage.create()
            val entitySource = WorkspaceEntitySource(projectDirectory.toIntellijUri(virtualFileUrlManager))
            val libs = mutableSetOf<String>()
            val sdks = mutableSetOf<String>()
            val macroExpandMap = run {
                val map = ExpandMacroToPathMap()
                ProjectWidePathMacroContributor.getAllMacros((projectDirectory / ".idea" / "misc.xml").absolutePathString()).forEach { (name, value) ->
                    map.addMacroExpand(name, value)
                }
                map
            }

            // Pre-compute the set of modules each module transitively exports, for flattening.
            // JPS projects use a non-flat dependency model (A→B→C), so exported transitive deps
            // must be added as direct deps of each module (like Maven/Gradle importers do).
            val modulesByName = model.project.modules.associateBy { it.name }
            val transitiveExportsCache = mutableMapOf<String, Set<String>>()

            fun transitiveExports(moduleName: String): Set<String> {
                transitiveExportsCache[moduleName]?.let { return it }
                transitiveExportsCache[moduleName] = emptySet() // guard against dependency cycles
                val jpsModule = modulesByName[moduleName] ?: return emptySet()
                val result = buildSet {
                    for (dep in jpsModule.dependenciesList.dependencies) {
                        if (dep !is JpsModuleDependency) continue
                        val depModule = dep.module ?: continue
                        if (JpsJavaExtensionService.getInstance().getDependencyExtension(dep)?.isExported != true) continue
                        add(depModule.name)
                        addAll(transitiveExports(depModule.name))
                    }
                }
                transitiveExportsCache[moduleName] = result
                return result
            }

            model.project.modules.forEach { module ->
                val kotlinFacetModuleExtension = module.container.getChild(JpsKotlinFacetModuleExtension.KIND)

                val directDeps = module.dependenciesList.dependencies.mapNotNull { dependency ->
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
                                                progress.onUnresolvedDependency(url)
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
                            val depModule = dependency.module ?: return@mapNotNull null
                            ModuleDependency(
                                module = ModuleId(depModule.name),
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
                }

                // Add transitively exported module deps as direct deps (flattening).
                // A module X reachable via an exported chain from direct dep D is added once;
                // its exported flag mirrors the first-hop dep (A→D) so that downstream modules
                // that depend on this module also see X when D is exported.
                val presentModules = directDeps.filterIsInstance<ModuleDependency>().mapTo(mutableSetOf()) { it.module.name }
                val flattenedDeps = buildList {
                    for (dep in module.dependenciesList.dependencies) {
                        if (dep !is JpsModuleDependency) continue
                        val depModuleName = dep.module?.name ?: continue
                        val javaExt = JpsJavaExtensionService.getInstance().getDependencyExtension(dep)
                        val exported = javaExt?.isExported == true
                        val scope = DependencyScope.valueOf(javaExt?.scope?.name ?: "COMPILE")
                        for (transitiveName in transitiveExports(depModuleName)) {
                            if (presentModules.add(transitiveName)) {
                                add(ModuleDependency(
                                    module = ModuleId(transitiveName),
                                    exported = exported,
                                    scope = scope,
                                    productionOnTest = false
                                ))
                            }
                        }
                    }
                }

                val entity = ModuleEntity(
                    name = module.name,
                    dependencies = directDeps + flattenedDeps,
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
                                this.compilerArguments = settings.compilerArguments?.let { args ->
                                    val args = args.pluginClasspaths?.let { classpath ->
                                        args.copyOf().apply {
                                            pluginClasspaths = classpath.map {
                                                macroExpandMap.substitute(it, true)
                                            }.toTypedArray()
                                        }
                                    } ?: args
                                    CompilerArgumentsSerializer.serializeToString(args)
                                }
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
                    storage addEntity builder
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
                                        SdkRootTypeId.CLASSES,
                                    )
                                }
                                library.getRootUrls(JpsOrderRootType.SOURCES).mapNotNullTo(this) { url ->
                                    SdkRoot(
                                        virtualFileUrlManager.getOrCreateFromUrl(url),
                                        SdkRootTypeId.SOURCES,
                                    )
                                }
                            },
                            additionalData = "",
                            entitySource = entitySource
                        )
                        storage addEntity builder
                    }

                    is JpsLibraryType -> {
                    }
                }
            }
            storage

        } catch (e: IOException) {
            throw WorkspaceImportException(
                "Error parsing workspace.json",
                "Error parsing workspace.json:\n ${e.message ?: e.stackTraceToString()}",
                e
            )
        }
    }

    private fun isApplicableDirectory(projectDirectory: Path): Boolean {
        return (projectDirectory / ".idea" / "modules.xml").exists()
    }

    private fun detectJavaSdks(
        projectDirectory: Path,
        sdks: Collection<String>,
        virtualFileUrlManager: VirtualFileUrlManager,
        entitySource: WorkspaceEntitySource,
    ): List<SdkEntityBuilder> {
        val detectedSdks = findJdks(projectDirectory)
        if (detectedSdks.isEmpty()) return emptyList()
        return sdks.map { sdkName ->
            val zeroVersion = JavaVersion.compose(0, 0, 0)
            val sdk = (detectedSdks.filter { sdk ->
                sdk.versionInfo?.suggestedName().let { suggestedName ->
                    suggestedName != null && sdkName.contains(suggestedName, ignoreCase = true)
                }
            }.nullize() ?: detectedSdks).maxBy {
                it.versionInfo?.version ?: zeroVersion
            }
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
                            SdkRootTypeId.CLASSES,
                        )
                    }
                    val srcZip = Path.of(sdk.path, "lib", "src.zip")
                    if (srcZip.exists()) {
                        val prefix = "jar://${FileUtilRt.toSystemIndependentName(srcZip.toString())}!/"
                        classRoots.mapTo(this) {
                            SdkRoot(
                                virtualFileUrlManager.getOrCreateFromUrl("$prefix${it.substringAfterLast("/")}"),
                                SdkRootTypeId.SOURCES,
                            )
                        }
                    }
                },
                additionalData = "",
                entitySource = entitySource
            )
        }
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

fun findJdks(projectPath: Path): Set<JavaHomeFinder.JdkEntry> {
    val knownJdks = getFinder(projectPath.getEelDescriptor())
        .checkConfiguredJdks(false)
        .checkEmbeddedJava(false)
        .findExistingJdkEntries()
    if (knownJdks.isEmpty()) {
        throw WorkspaceImportException(
            "Unable to find JDK for Gradle execution. No JDK's found on the machine!",
            "There are no JDKs on the machine. Unable to run Gradle."
        )
    }
    return knownJdks
}
