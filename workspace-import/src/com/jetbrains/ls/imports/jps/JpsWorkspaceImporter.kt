// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.jps

import com.intellij.java.workspace.entities.JavaModuleSettingsEntity
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.ExpandMacroToPathMap
import com.intellij.openapi.components.impl.getAllMacros
import com.intellij.openapi.diagnostic.fileLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.impl.JavaHomeFinder
import com.intellij.openapi.projectRoots.impl.JavaHomeFinder.getFinder
import com.intellij.openapi.projectRoots.impl.JavaSdkImpl
import com.intellij.openapi.util.JDOMUtil
import com.intellij.platform.eel.provider.getEelDescriptor
import com.intellij.platform.workspace.jps.entities.ContentRootEntity
import com.intellij.platform.workspace.jps.entities.DependencyScope
import com.intellij.platform.workspace.jps.entities.ExcludeUrlEntity
import com.intellij.platform.workspace.jps.entities.ExternalSystemModuleOptionsEntity
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
import com.intellij.platform.workspace.jps.entities.exModuleOptions
import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.url.VirtualFileUrlManager
import com.intellij.util.PathUtil
import com.intellij.util.containers.nullize
import com.intellij.util.lang.JavaVersion
import com.jetbrains.ls.imports.api.WorkspaceEntitySource
import com.jetbrains.ls.imports.api.WorkspaceImportException
import com.jetbrains.ls.imports.api.WorkspaceImportProgressReporter
import com.jetbrains.ls.imports.api.WorkspaceImportParameters
import com.jetbrains.ls.imports.api.WorkspaceImporter
import com.jetbrains.ls.imports.api.applyChangesWithDeduplication
import com.jetbrains.ls.imports.gradle.GradleWorkspaceImporter
import com.jetbrains.ls.imports.json.flattenExportedDependencies
import com.jetbrains.ls.imports.maven.MavenWorkspaceImporter
import com.jetbrains.ls.imports.utils.toIntellijUri
import com.jetbrains.ls.snapshot.api.impl.core.toFileUrl
import org.eclipse.aether.repository.RemoteRepository
import org.jdom.Element
import org.jetbrains.idea.maven.aether.ArtifactRepositoryManager
import org.jetbrains.idea.maven.aether.ProgressConsumer
import org.jetbrains.jps.model.JpsElementFactory
import org.jetbrains.jps.model.JpsModel
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.jetbrains.jps.model.java.JavaSourceRootType
import org.jetbrains.jps.model.java.JpsJavaExtensionService
import org.jetbrains.jps.model.java.JpsJavaModuleType
import org.jetbrains.jps.model.java.JpsJavaSdkType
import org.jetbrains.jps.model.library.JpsLibrary
import org.jetbrains.jps.model.library.JpsLibraryType
import org.jetbrains.jps.model.library.JpsMavenRepositoryLibraryDescriptor
import org.jetbrains.jps.model.library.JpsOrderRootType
import org.jetbrains.jps.model.library.JpsRepositoryLibraryType
import org.jetbrains.jps.model.module.JpsLibraryDependency
import org.jetbrains.jps.model.module.JpsModuleDependency
import org.jetbrains.jps.model.module.JpsModuleSourceDependency
import org.jetbrains.jps.model.module.JpsSdkDependency
import org.jetbrains.jps.model.serialization.JpsGlobalLoader
import org.jetbrains.jps.model.serialization.JpsGlobalSettingsLoading
import org.jetbrains.jps.model.serialization.JpsMavenSettings
import org.jetbrains.jps.model.serialization.JpsModelSerializationDataService
import org.jetbrains.jps.model.serialization.JpsProjectLoader
import org.jetbrains.jps.model.serialization.PathMacroUtil
import org.jetbrains.jps.model.serialization.impl.JpsPathVariablesConfigurationImpl
import org.jetbrains.jps.util.JpsPathUtil
import org.jetbrains.kotlin.cli.common.arguments.copyOf
import org.jetbrains.kotlin.config.isHmpp
import org.jetbrains.kotlin.idea.facet.KotlinFacetType
import org.jetbrains.kotlin.idea.workspaceModel.CompilerArgumentsSerializer
import org.jetbrains.kotlin.idea.workspaceModel.KotlinSettingsEntity
import org.jetbrains.kotlin.idea.workspaceModel.kotlinSettings
import org.jetbrains.kotlin.idea.workspaceModel.toCompilerSettingsData
import org.jetbrains.kotlin.jps.model.JpsKotlinFacetModuleExtension
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

private val LOG = fileLogger()

object JpsWorkspaceImporter : WorkspaceImporter {
    override suspend fun importWorkspace(
        project: Project,
        parameters: WorkspaceImportParameters,
        virtualFileUrlManager: VirtualFileUrlManager,
        progress: WorkspaceImportProgressReporter,
    ): EntityStorage? {
        val projectDirectory = parameters.projectDirectory
        val defaultSdkPath = parameters.defaultSdkPath
        val options = parameters.options
        if (!canImportWorkspace(projectDirectory)) return null
        return try {
            val model = JpsElementFactory.getInstance().createModel()
            val macroExpandMap = ExpandMacroToPathMap()

            initGlobalJpsOptions(model)
            JpsModelSerializationDataService.computeAllPathVariables(model.global).let { pathVariables ->
                JpsProjectLoader.loadProject(model.getProject(), pathVariables, model.getGlobal().getPathMapper(), projectDirectory, true, null)

                getAllMacros(
                    (projectDirectory / ".idea" / "modules.xml").absolutePathString()
                ).forEach { (name, value) ->
                    macroExpandMap.addMacroExpand(name, value)
                }
                pathVariables.forEach { (name, value) ->
                    macroExpandMap.addMacroExpand(name, value)
                }
                macroExpandMap.addMacroExpand(
                    PathMacroUtil.PROJECT_DIR_MACRO_NAME, projectDirectory.absolutePathString()
                )
            }

            val storage = MutableEntityStorage.create()
            importJpsModel(storage, projectDirectory, virtualFileUrlManager, model, macroExpandMap, progress)

            findLinkedProjects(projectDirectory, macroExpandMap).forEach { (path, importer) ->
                LOG.info("Importing linked project: $path")
                val diff = importer.importWorkspace(
                    project = project,
                    parameters = WorkspaceImportParameters(
                        projectDirectory = path,
                        defaultSdkPath = defaultSdkPath,
                        options = options,
                    ),
                    virtualFileUrlManager = virtualFileUrlManager,
                    progress = progress,
                ) ?: return@forEach
                storage.applyChangesWithDeduplication(diff)
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

    private fun importJpsModel(
        storage: MutableEntityStorage,
        projectDirectory: Path,
        virtualFileUrlManager: VirtualFileUrlManager,
        model: JpsModel,
        macroExpandMap: ExpandMacroToPathMap,
        progress: WorkspaceImportProgressReporter
    ) {
        val entitySource = WorkspaceEntitySource(projectDirectory.toIntellijUri(virtualFileUrlManager))
        val libs = mutableSetOf<String>()
        val sdks = mutableSetOf<String>()
        // Lazily created so the resolver (and its remote-repository setup) is only initialized when a library actually
        // needs to be downloaded.
        val repositoryManager = lazy { createArtifactRepositoryManager(projectDirectory) }

        model.project.modules.forEach { module ->
            val kotlinFacetModuleExtension = module.container.getChild(JpsKotlinFacetModuleExtension.KIND)

            val directDeps = module.dependenciesList.dependencies.mapNotNull { dependency ->
                val javaExtension = JpsJavaExtensionService.getInstance().getDependencyExtension(dependency)
                when (dependency) {
                    is JpsLibraryDependency -> {
                        val library = dependency.library ?: return@mapNotNull null
                        if (libs.add(library.name)) {
                            val roots = resolveLibraryRoots(library, virtualFileUrlManager, progress, repositoryManager)
                                ?: return@mapNotNull null
                            val libEntity = LibraryEntity(
                                name = library.name,
                                tableId = ProjectLibraryTableId,
                                roots = roots,
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

            val entity = ModuleEntity(
                name = module.name,
                dependencies = directDeps,
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
                val moduleProjectPath = JpsModelSerializationDataService.getBaseDirectoryPath(module)?.toString()
                    ?: module.contentRootsList.urls.firstOrNull()?.let { JpsPathUtil.urlToPath(it) }
                    ?: projectDirectory.toString()
                this.exModuleOptions = ExternalSystemModuleOptionsEntity(entitySource) {
                    this.linkedProjectPath = moduleProjectPath
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
                                val args = args.pluginClasspaths.let { classpath ->
                                    args.copyOf().apply {
                                        pluginClasspaths = classpath.map {
                                            macroExpandMap.substitute(it, true)
                                        }.toTypedArray()
                                    }
                                }
                                CompilerArgumentsSerializer.serializeToString(args)
                            }
                            this.compilerSettings = settings.compilerSettings.toCompilerSettingsData()
                            this.targetPlatform = settings.targetPlatform.toString()
                        }
                    )
                }
            }
            storage addEntity entity

            val javaService = JpsJavaExtensionService.getInstance()
            val javaModuleExtension = javaService.getModuleExtension(module)
            storage addEntity JavaModuleSettingsEntity(
                inheritedCompilerOutput = javaModuleExtension?.isInheritOutput ?: true,
                excludeOutput = javaModuleExtension?.isExcludeOutput ?: true,
                entitySource = entitySource,
            ) {
                this.module = entity
                this.languageLevelId = javaService.getLanguageLevel(module)?.name
                this.compilerOutput = javaService.getOutputUrl(module, false)?.let { virtualFileUrlManager.getOrCreateFromUrl(it) }
                this.compilerOutputForTests = javaService.getOutputUrl(module, true)?.let { virtualFileUrlManager.getOrCreateFromUrl(it) }
            }
        }

        // JPS uses a non-flat dependency model; flatten transitively-exported module deps into direct deps
        // so the analyzer's non-recursive OrderEnumerator resolves them. See AnalyzerOrderEnumerationHandler.
        flattenExportedDependencies(storage, includeLibraries = false)
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
    }

    override fun canImportWorkspace(projectDirectory: Path): Boolean {
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
            SdkEntity(
                name = sdkName,
                type = JavaSdk.getInstance().name,
                roots = buildList {
                    JavaSdkImpl.findClasses(Path.of(sdk.path), false).mapTo(this) {
                        SdkRoot(
                            virtualFileUrlManager.getOrCreateFromUrl(it.replace("!/", "!/modules/")),
                            SdkRootTypeId.CLASSES,
                        )
                    }
                    JavaSdkImpl.findSources(Path.of(sdk.path)).mapTo(this) {
                        SdkRoot(
                            virtualFileUrlManager.getOrCreateFromUrl(it),
                            SdkRootTypeId.SOURCES,
                        )
                    }
                },
                additionalData = "",
                entitySource = entitySource
            ) {
                homePath = virtualFileUrlManager.getOrCreateFromUrl(Path.of(sdk.path).toFileUrl().url)
            }
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

/**
 * Builds the workspace-model roots for [library].
 *
 * When some compiled roots are missing on disk (e.g. the artifact was never downloaded into the local Maven repository)
 * and the library is a Maven repository library, the artifact together with its transitive dependencies is resolved and
 * downloaded into the local Maven repository via [repositoryManager]. The downloaded files land at the macro-expanded
 * paths the JPS roots already point to, so afterwards the roots are taken as serialized in the JPS model.
 *
 * Returns `null` when some compiled root still cannot be resolved, in which case the caller skips the dependency and the
 * missing roots are reported as unresolved.
 */
private fun resolveLibraryRoots(
    library: JpsLibrary,
    virtualFileUrlManager: VirtualFileUrlManager,
    progress: WorkspaceImportProgressReporter,
    repositoryManager: Lazy<ArtifactRepositoryManager>,
): List<LibraryRoot>? {
    val compiledUrls = library.getRootUrls(JpsOrderRootType.COMPILED)

    if (compiledUrls.any { !Path.of(JpsPathUtil.urlToPath(it)).exists() }) {
        library.mavenRepositoryDescriptor()?.let { descriptor ->
            downloadFromMavenRepository(repositoryManager.value, descriptor)
        }
    }

    val missingCompiled = compiledUrls.filter { !Path.of(JpsPathUtil.urlToPath(it)).exists() }
    if (missingCompiled.isNotEmpty()) {
        missingCompiled.forEach(progress::onUnresolvedDependency)
        return null
    }

    return buildList {
        compiledUrls.mapTo(this) { url ->
            LibraryRoot(virtualFileUrlManager.getOrCreateFromUrl(url), LibraryRootTypeId.COMPILED)
        }
        library.getRootUrls(JpsOrderRootType.SOURCES).mapTo(this) { url ->
            LibraryRoot(virtualFileUrlManager.getOrCreateFromUrl(url), LibraryRootTypeId.SOURCES)
        }
    }
}

/** Returns the Maven coordinates of [this] library if it is a resolvable Maven repository library, otherwise `null`. */
private fun JpsLibrary.mavenRepositoryDescriptor(): JpsMavenRepositoryLibraryDescriptor? =
    asTyped(JpsRepositoryLibraryType.INSTANCE)?.properties?.data?.takeIf { it.mavenId != null }

/**
 * Resolves [descriptor] (and its transitive dependencies) from the configured Maven repositories, downloading the
 * artifacts into the local Maven repository as a side effect. Failures are logged and swallowed; the caller then falls
 * back to reporting the library as unresolved.
 */
private fun downloadFromMavenRepository(
    repositoryManager: ArtifactRepositoryManager,
    descriptor: JpsMavenRepositoryLibraryDescriptor,
) {
    try {
        LOG.info("Library is missing locally, downloading from Maven repositories: ${descriptor.mavenId}")
        repositoryManager.resolveDependency(
            descriptor.groupId,
            descriptor.artifactId,
            descriptor.version,
            descriptor.isIncludeTransitiveDependencies,
            descriptor.excludedDependencies,
        )
    }
    catch (e: ProcessCanceledException) {
        throw e
    }
    catch (e: Exception) {
        LOG.warn("Failed to download library '${descriptor.mavenId}' from Maven repositories", e)
    }
}

/**
 * Creates an [ArtifactRepositoryManager] that resolves against the project's local Maven repository.
 *
 * Remote repositories are taken from `.idea/jarRepositories.xml` when that file is present; otherwise the default
 * remote repositories (Maven Central, JBoss) are used. Downloaded artifacts are placed into the local repository,
 * matching the macro-expanded paths the JPS library roots point to.
 */
// ArtifactRepositoryManager only accepts a java.io.File for the local repository path.
@Suppress("IO_FILE_USAGE")
private fun createArtifactRepositoryManager(projectDirectory: Path): ArtifactRepositoryManager {
    val remoteRepositories = readRemoteRepositories(projectDirectory)
        ?: ArtifactRepositoryManager.createDefaultRemoteRepositories()
    return ArtifactRepositoryManager(
        Path.of(JpsMavenSettings.getMavenRepositoryPath()).toFile(),
        remoteRepositories,
        ProgressConsumer.DEAF,
    )
}

/**
 * Reads the remote repositories configured in `.idea/jarRepositories.xml`.
 *
 * The file stores each repository under a `RemoteRepositoriesConfiguration` component, e.g.:
 * ```xml
 * <component name="RemoteRepositoriesConfiguration">
 *   <remote-repository>
 *     <option name="id" value="central-proxy" />
 *     <option name="name" value="Maven Central Proxy" />
 *     <option name="url" value="https://cache-redirector.jetbrains.com/repo1.maven.org/maven2" />
 *   </remote-repository>
 * </component>
 * ```
 *
 * The repository `id` matters because the Aether resolver matches it against authentication and mirror settings from
 * `.m2/settings.xml`. Returns `null` when the file is absent or declares no usable repositories, so the caller falls
 * back to the default remote repositories.
 */
private fun readRemoteRepositories(projectDirectory: Path): List<RemoteRepository>? {
    val root = loadIdeaXml(projectDirectory, "jarRepositories.xml") ?: return null
    val component = root.children
        .firstOrNull { it.name == "component" && it.getAttributeValue("name") == "RemoteRepositoriesConfiguration" }
        ?: return null
    return component.children
        .asSequence()
        .filter { it.name == "remote-repository" }
        .mapNotNull { repository ->
            val options = repository.children.filter { it.name == "option" }
            fun option(name: String): String? =
                options.firstOrNull { it.getAttributeValue("name") == name }?.getAttributeValue("value")
            val id = option("id") ?: return@mapNotNull null
            val url = option("url") ?: return@mapNotNull null
            ArtifactRepositoryManager.createRemoteRepository(id, url)
        }
        .toList()
        .nullize()
}

/**
 * Returns linked external project directories paired with the importer that should handle them.
 *
 * Currently looks for:
 *  - Maven projects registered in `.idea/misc.xml` under `MavenProjectsManager.originalFiles`
 *  - Gradle projects registered in `.idea/gradle.xml` under `GradleSettings.linkedExternalProjectsSettings`
 *
 * Example Maven excerpt:
 * ```xml
 * <component name="MavenProjectsManager">
 *   <option name="originalFiles">
 *     <list>
 *       <option value="$PROJECT_DIR$/subproject/pom.xml" />
 *     </list>
 *   </option>
 * </component>
 * ```
 *
 * Example Gradle excerpt:
 * ```xml
 * <component name="GradleSettings">
 *   <option name="linkedExternalProjectsSettings">
 *     <GradleProjectSettings>
 *       <option name="externalProjectPath" value="$PROJECT_DIR$/subproject" />
 *     </GradleProjectSettings>
 *   </option>
 * </component>
 * ```
 */
private fun findLinkedProjects(
    projectDirectory: Path,
    macroExpandMap: ExpandMacroToPathMap
): Sequence<Pair<Path, WorkspaceImporter>> = sequence {
    yieldAll(findLinkedMavenProjects(projectDirectory, macroExpandMap).map { it to MavenWorkspaceImporter })
    yieldAll(findLinkedGradleProjects(projectDirectory, macroExpandMap).map { it to GradleWorkspaceImporter })
}

private fun findLinkedMavenProjects(
    projectDirectory: Path,
    macroExpandMap: ExpandMacroToPathMap,
): Sequence<Path> = sequence {
    val root = loadIdeaXml(projectDirectory, "misc.xml") ?: return@sequence
    val mavenComponent = root.children
        .firstOrNull { it.name == "component" && it.getAttributeValue("name") == "MavenProjectsManager" }
        ?: return@sequence
    val originalFilesOption = mavenComponent.children
        .firstOrNull { it.name == "option" && it.getAttributeValue("name") == "originalFiles" }
        ?: return@sequence
    val list = originalFilesOption.getChild("list") ?: return@sequence
    list.children
        .asSequence()
        .filter { it.name == "option" }
        .mapNotNull { it.getAttributeValue("value") }
        .map { Path.of(PathUtil.toSystemDependentName(macroExpandMap.substitute(it, true))) }
        .filter { it.isRegularFile() }
        .forEach {
            yield(it.parent)
        }
}

private fun findLinkedGradleProjects(
    projectDirectory: Path,
    macroExpandMap: ExpandMacroToPathMap,
): Sequence<Path> = sequence {
    val root = loadIdeaXml(projectDirectory, "gradle.xml") ?: return@sequence
    val gradleComponent = root.children
        .firstOrNull { it.name == "component" && it.getAttributeValue("name") == "GradleSettings" }
        ?: return@sequence
    val linkedSettingsOption = gradleComponent.children
        .firstOrNull { it.name == "option" && it.getAttributeValue("name") == "linkedExternalProjectsSettings" }
        ?: return@sequence
    linkedSettingsOption.children
        .asSequence()
        .filter { it.name == "GradleProjectSettings" }
        .mapNotNull { settings ->
            settings.children
                .firstOrNull { it.name == "option" && it.getAttributeValue("name") == "externalProjectPath" }
                ?.getAttributeValue("value")
        }
        .map { Path.of(PathUtil.toSystemDependentName(macroExpandMap.substitute(it, true))) }
        .filter { it.isDirectory() }
        .forEach {
            yield(it)
        }
}

private fun loadIdeaXml(projectDirectory: Path, fileName: String): Element? {
    val xml = projectDirectory / ".idea" / fileName
    if (!xml.exists()) return null
    return try {
        JDOMUtil.load(xml)
    } catch (e: Exception) {
        LOG.warn("Failed to parse $xml: ${e.message}")
        null
    }
}

private fun findJdks(projectPath: Path): Set<JavaHomeFinder.JdkEntry> {
    val knownJdks = getFinder(projectPath.getEelDescriptor())
        .checkConfiguredJdks(false)
        .checkEmbeddedJava(false)
        .findExistingJdkEntries()
    if (knownJdks.isEmpty()) {
        throw WorkspaceImportException(
            "Unable to find a JDK on the machine. JPS workspace import requires at least one configured JDK.",
            "No JDKs found while importing the JPS (.iml) workspace."
        )
    }
    return knownJdks
}
