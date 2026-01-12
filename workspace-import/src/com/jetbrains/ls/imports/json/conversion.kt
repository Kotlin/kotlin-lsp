// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.json


import com.intellij.openapi.diagnostic.fileLogger
import com.intellij.openapi.projectRoots.impl.JavaSdkImpl
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.platform.workspace.jps.entities.*
import com.intellij.platform.workspace.jps.entities.LibraryTableId.ModuleLibraryTableId
import com.intellij.platform.workspace.jps.serialization.impl.toPath
import com.intellij.platform.workspace.storage.EntitySource
import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.entities
import com.intellij.platform.workspace.storage.url.VirtualFileUrl
import com.intellij.platform.workspace.storage.url.VirtualFileUrlManager
import com.intellij.util.descriptors.ConfigFileItem
import com.intellij.util.system.OS
import com.intellij.util.text.nullize
import com.jetbrains.ls.imports.utils.toIntellijUri
import kotlinx.serialization.json.Json
import org.jetbrains.jps.model.serialization.JpsMavenSettings
import org.jetbrains.kotlin.config.KotlinModuleKind
import org.jetbrains.kotlin.idea.workspaceModel.CompilerSettingsData
import org.jetbrains.kotlin.idea.workspaceModel.KotlinSettingsEntity
import org.jetbrains.kotlin.idea.workspaceModel.KotlinSettingsEntityBuilder
import java.nio.file.Path
import kotlin.io.path.absolutePathString

private val LOG = fileLogger()

fun workspaceData(storage: EntityStorage, workspacePath: Path): WorkspaceData =
    WorkspaceData(
        modules = storage.entities<ModuleEntity>()
            .map { toDataClass(it, workspacePath) }
            .sortedBy { it.name }
            .toList(),
        libraries = storage.entities<LibraryEntity>()
            .filter { it.name != "JDK" }
            .map { toDataClass(it, workspacePath) }
            .sortedBy { it.name }
            .toList(),
        sdks = storage.entities<SdkEntity>()
            .map { toDataClass(it, workspacePath) }
            .sortedBy { it.name }.toList(),
        kotlinSettings = storage.entities<KotlinSettingsEntity>()
            .map { toDataClass(it, workspacePath) }
            .sortedBy { it.module }
            .toList(),
    )

private val JSON = Json { prettyPrint = true }

fun toJson(storage: EntityStorage, workspacePath: Path): String =
    toJson(workspaceData(storage, workspacePath))

fun toJson(data: WorkspaceData): String =
    JSON.encodeToString(data)

private fun toDataClass(entity: ModuleEntity, workspacePath: Path): ModuleData =
    ModuleData(
        name = entity.name,
        type = entity.type?.name,
        dependencies = entity.dependencies.map { dependency ->
            toDataClass(dependency)
        },
        contentRoots = entity.contentRoots.map { contentRoot ->
            toDataClass(contentRoot, workspacePath)
        },
        facets = entity.facets.map { facet -> toDataClass(facet) }
    )

private fun toDataClass(
    contentRoot: ContentRootEntity,
    workspacePath: Path
): ContentRootData = ContentRootData(
    path = toRelativePath(contentRoot.url, workspacePath),
    excludedPatterns = contentRoot.excludedPatterns,
    excludedUrls = listOf(),
    sourceRoots = contentRoot.sourceRoots.map { sourceRoot ->
        SourceRootData(
            path = toRelativePath(sourceRoot.url, workspacePath),
            type = sourceRoot.rootTypeId.name,
        )
    }
)

private fun toDataClass(dependency: ModuleDependencyItem): DependencyData = when (dependency) {
    is ModuleDependency -> DependencyData.Module(
        name = dependency.module.name,
        scope = DependencyDataScope.valueOf(dependency.scope.name),
        isExported = dependency.exported
    )

    is LibraryDependency -> DependencyData.Library(
        name = dependency.library.name,
        scope = DependencyDataScope.valueOf(dependency.scope.name),
        isExported = dependency.exported
    )

    InheritedSdkDependency -> DependencyData.InheritedSdk
    ModuleSourceDependency -> DependencyData.ModuleSource
    is SdkDependency -> DependencyData.Sdk(dependency.sdk.name, dependency.sdk.type)
}

private fun toDataClass(facet: FacetEntity): FacetData =
    FacetData(
        name = facet.name,
        type = facet.typeId.name,
        configuration = facet.configurationXmlTag?.let { parseXml(it).copy(tag = "") },
        underlyingFacet = facet.underlyingFacet?.let { toDataClass(it) }
    )

private fun toDataClass(entity: LibraryEntity, workspacePath: Path): LibraryData =
    LibraryData(
        name = entity.name,
        type = entity.typeId?.name,
        level = entity.tableId.level,
        module = (entity.tableId as? ModuleLibraryTableId)?.moduleId?.name,
        roots = entity.roots.map { root ->
            LibraryRootData(
                path = toRelativePath(root.url, workspacePath),
                type = root.type.name,
                inclusionOptions = InclusionOptions.valueOf(root.inclusionOptions.name),
            )
        },
        excludedRoots = entity.excludedRoots.map { toRelativePath(it.url, workspacePath) },
        properties = entity.libraryProperties?.propertiesXmlTag?.let { parseXml(it).copy(tag = "") },
    )

private fun toDataClass(entity: SdkEntity, workspacePath: Path): SdkData =
    SdkData(
        name = entity.name,
        type = entity.type,
        version = entity.version,
        homePath = entity.homePath?.let { toRelativePath(it, workspacePath) },
        roots = when {
            entity.homePath != null && entity.type == "JavaSDK" -> null // Let's calculate them on restore
            else -> entity.roots.map { SdkRootData(it.url.url, it.type.name) }
        },
        additionalData = entity.additionalData
    )

// TODO: Store relative paths where possible
@Suppress("DEPRECATION")
private fun toDataClass(
    entity: KotlinSettingsEntity,
    workspacePath: Path
): KotlinSettingsData =
    KotlinSettingsData(
        name = entity.name,
        sourceRoots = entity.sourceRoots.map {
            toRelativePath(Path.of(it), workspacePath)
        },
        configFileItems = entity.configFileItems.map { ConfigFileItemData(it.id, it.url) },
        module = entity.module.name,
        useProjectSettings = entity.useProjectSettings,
        implementedModuleNames = entity.implementedModuleNames,
        dependsOnModuleNames = entity.dependsOnModuleNames,
        additionalVisibleModuleNames = entity.additionalVisibleModuleNames,
        productionOutputPath = entity.productionOutputPath,
        testOutputPath = entity.testOutputPath,
        sourceSetNames = entity.sourceSetNames,
        isTestModule = entity.isTestModule,
        externalProjectId = entity.externalProjectId,
        isHmppEnabled = entity.isHmppEnabled,
        pureKotlinSourceFolders = entity.pureKotlinSourceFolders.map {
            toRelativePath(Path.of(it), workspacePath)
        },
        kind = KotlinSettingsData.KotlinModuleKind.valueOf(entity.kind.name),
        compilerArguments = entity.compilerArguments?.let {
            toRelativeKotlinCompilerArguments(it)
        },
        additionalArguments = entity.compilerSettings?.additionalArguments?.nullize(),
        scriptTemplates = entity.compilerSettings?.scriptTemplates?.nullize(),
        scriptTemplatesClasspath = entity.compilerSettings?.scriptTemplatesClasspath?.nullize(),
        copyJsLibraryFiles = entity.compilerSettings?.copyJsLibraryFiles == true,
        outputDirectoryForJsLibraryFiles = entity.compilerSettings?.outputDirectoryForJsLibraryFiles?.nullize(),
        targetPlatform = entity.targetPlatform,
        externalSystemRunTasks = entity.externalSystemRunTasks,
        version = entity.version,
        flushNeeded = entity.flushNeeded,
    )

private const val USER_HOME_PREFIX = "<HOME>/"
private const val WORKSPACE_PREFIX = "<WORKSPACE>/"
private const val MAVEN_PREFIX = "<MAVEN_REPO>/"
private val userHome = Path.of(System.getProperty("user.home"))
private val m2Repo = Path.of(JpsMavenSettings.getMavenRepositoryPath())
    .also {
        LOG.info("Detected Maven repo: $it")
    }

private fun toRelativePath(url: VirtualFileUrl, workspacePath: Path): String {
    return toRelativePath(url.toPath(), workspacePath)
}

private fun toRelativePath(path: Path, workspacePath: Path): String {
    var pathString = when {
        path.startsWith(workspacePath) -> WORKSPACE_PREFIX + workspacePath.relativize(path)
        path.startsWith(m2Repo) -> MAVEN_PREFIX + m2Repo.relativize(path)
        path.startsWith(userHome) -> USER_HOME_PREFIX + userHome.relativize(path)
        else -> path.absolutePathString()
    }
    pathString = FileUtilRt.toSystemIndependentName(pathString)
    return pathString
}


fun MutableEntityStorage.importWorkspaceData(
    data: WorkspaceData,
    workspacePath: Path,
    entitySource: EntitySource,
    virtualFileUrlManager: VirtualFileUrlManager,
    ignoreDuplicateLibsAndSdks: Boolean = false,
) {
    val storage = this
    for (sdkData in data.sdks) {
        if (ignoreDuplicateLibsAndSdks && SdkId(sdkData.name, sdkData.type) in storage) {
            continue
        }
        val roots =
            sdkData.roots?.map { SdkRoot(virtualFileUrlManager.getOrCreateFromUrl(it.url), SdkRootTypeId(it.type)) }
                ?: sdkData.homePath?.let { homePath ->
                    val uris = JavaSdkImpl.findClasses(toAbsolutePath(homePath, workspacePath), false)
                        .map { it.replace("!/", "!/modules/") }
                    uris.map { SdkRoot(it.toIntellijUri(virtualFileUrlManager), SdkRootTypeId("classPath")) }
                }
                ?: emptyList()

        storage addEntity SdkEntity(
            name = sdkData.name,
            type = sdkData.type,
            roots = roots,
            additionalData = sdkData.additionalData,
            entitySource = entitySource
        ) {
            version = sdkData.version
            homePath = sdkData.homePath?.toIntellijUri(virtualFileUrlManager)
        }
    }

    val libraryEntities = mutableMapOf<String, LibraryEntity>()
    for (libraryData in data.libraries) {
        val tableId = when (libraryData.level) {
            "module" -> ModuleLibraryTableId(ModuleId(libraryData.module!!))
            else -> LibraryTableId.ProjectLibraryTableId
        }
        if (ignoreDuplicateLibsAndSdks && LibraryId(libraryData.name, tableId) in storage) {
            continue
        }
        val roots = libraryData.roots.map {
            LibraryRoot(toAbsolutePath(it.path, workspacePath).toIntellijUri(virtualFileUrlManager), LibraryRootTypeId(it.type), LibraryRoot.InclusionOptions.valueOf(it.inclusionOptions.name))
        }
        val libraryEntity = storage addEntity LibraryEntity(
            name = libraryData.name,
            tableId = tableId,
            roots = roots,
            entitySource = entitySource
        ) {
            typeId = libraryData.type?.let { LibraryTypeId(it) }
            excludedRoots = libraryData.excludedRoots.map { ExcludeUrlEntity(toAbsolutePath(it, workspacePath).toIntellijUri(virtualFileUrlManager), entitySource) }
            libraryProperties = libraryData.properties?.let {
                LibraryPropertiesEntity(
                    entitySource = entitySource,
                ) {
                    propertiesXmlTag = toXml(it.copy(tag = "properties"))
                }
            }
        }
        libraryEntities[libraryData.name] = libraryEntity
    }

    fun toEntityBuilder(moduleData: ModuleData): ModuleEntityBuilder = ModuleEntity(
        name = moduleData.name,
        dependencies = emptyList(),
        entitySource = entitySource
    ) {
        type = moduleData.type?.let { ModuleTypeId(it) }
        contentRoots = moduleData.contentRoots.map { contentRootData ->
            ContentRootEntity(
                url = toAbsolutePath(contentRootData.path, workspacePath).toIntellijUri(virtualFileUrlManager),
                excludedPatterns = contentRootData.excludedPatterns,
                entitySource = entitySource,
            ) {
                sourceRoots =
                    contentRootData.sourceRoots.map { SourceRootEntity(toAbsolutePath(it.path, workspacePath).toIntellijUri(virtualFileUrlManager), SourceRootTypeId(it.type), entitySource) }
            }
        }

        dependencies = moduleData.dependencies.mapNotNull { dependency ->
            when (dependency) {
                is DependencyData.Module -> ModuleDependency(
                    module = ModuleId(dependency.name),
                    exported = dependency.isExported,
                    scope = DependencyScope.valueOf(dependency.scope.name),
                    productionOnTest = false
                )

                is DependencyData.Library -> libraryEntities[dependency.name]?.let {
                    LibraryDependency(
                        library = it.symbolicId,
                        exported = dependency.isExported,
                        scope = DependencyScope.valueOf(dependency.scope.name)
                    )
                }

                is DependencyData.Sdk -> SdkDependency(SdkId(dependency.name, dependency.kind))
                DependencyData.InheritedSdk -> InheritedSdkDependency
                DependencyData.ModuleSource -> ModuleSourceDependency
            }
        }.toMutableList()
    }

    val moduleBuilders = data.modules.associate { moduleData ->
        moduleData.name to toEntityBuilder(moduleData)
    }

    for (kotlinSettingsData in data.kotlinSettings) {
        storage addEntity toEntity(kotlinSettingsData, entitySource, moduleBuilders[kotlinSettingsData.module]!!, workspacePath)
    }

    data.modules.forEach { moduleData ->
        val moduleEntity = moduleBuilders[moduleData.name]!!
        moduleData.facets.forEach {
            addFacetRecursive(it, moduleEntity)
        }
    }

    moduleBuilders.values.forEach {
        storage addEntity it
    }
}

private fun toEntity(
    kotlinSettingsData: KotlinSettingsData,
    entitySource: EntitySource,
    module: ModuleEntityBuilder,
    workspacePath: Path
): KotlinSettingsEntityBuilder = KotlinSettingsEntity(
    name = kotlinSettingsData.name,
    moduleId = ModuleId(kotlinSettingsData.module),
    sourceRoots = kotlinSettingsData.sourceRoots.map {
        toAbsolutePath(it, workspacePath).toString()
    },
    configFileItems = kotlinSettingsData.configFileItems.map { ConfigFileItem(it.id, it.url) },
    useProjectSettings = kotlinSettingsData.useProjectSettings,
    implementedModuleNames = kotlinSettingsData.implementedModuleNames,
    dependsOnModuleNames = kotlinSettingsData.dependsOnModuleNames,
    additionalVisibleModuleNames = kotlinSettingsData.additionalVisibleModuleNames,
    sourceSetNames = kotlinSettingsData.sourceSetNames,
    isTestModule = kotlinSettingsData.isTestModule,
    externalProjectId = kotlinSettingsData.externalProjectId,
    isHmppEnabled = kotlinSettingsData.isHmppEnabled,
    pureKotlinSourceFolders = kotlinSettingsData.pureKotlinSourceFolders.map {
        toAbsolutePath(it, workspacePath).toString()
    },
    kind = KotlinModuleKind.valueOf(kotlinSettingsData.kind.name),
    externalSystemRunTasks = kotlinSettingsData.externalSystemRunTasks,
    version = kotlinSettingsData.version,
    flushNeeded = kotlinSettingsData.flushNeeded,
    entitySource = entitySource,
) {
    this.module = module
    this.targetPlatform = kotlinSettingsData.targetPlatform
    this.compilerArguments = kotlinSettingsData.compilerArguments?.let {
        toAbsoluteKotlinCompilerArguments(it)
    }
    this.compilerSettings = with(kotlinSettingsData) {
        CompilerSettingsData(
            additionalArguments = additionalArguments ?: "",
            scriptTemplates = scriptTemplates ?: "",
            scriptTemplatesClasspath = scriptTemplatesClasspath ?: "",
            copyJsLibraryFiles = copyJsLibraryFiles,
            outputDirectoryForJsLibraryFiles = outputDirectoryForJsLibraryFiles ?: "",
        )
    }
}

private fun addFacetRecursive(
  data: FacetData,
  moduleEntity: ModuleEntityBuilder,
): FacetEntityBuilder =
    FacetEntity(
        name = data.name,
        typeId = FacetEntityTypeId(data.type),
        moduleId = ModuleId(moduleEntity.name),
        entitySource = moduleEntity.entitySource
    ) {
        configurationXmlTag = data.configuration?.let { toXml(it.copy(tag = "configuration")) }
        this.underlyingFacet = data.underlyingFacet?.let {
            addFacetRecursive(it, moduleEntity)
        }
        this.module = moduleEntity
    }

private fun toRelativeKotlinCompilerArguments(json: String): String = when (OS.CURRENT) {
    OS.Windows if json.contains("\\\\") -> json.replace("${m2Repo.toAbsolutePath()}\\".replace("\\", "\\\\"), MAVEN_PREFIX.replace("/", "\\\\"))
    else -> json.replace("${m2Repo.toAbsolutePath()}/", MAVEN_PREFIX)
}

private fun toAbsoluteKotlinCompilerArguments(json: String): String = when (OS.CURRENT) {
    OS.Windows if json.contains("\\\\") -> json.replace(MAVEN_PREFIX.replace("/", "\\\\"), "${m2Repo.toAbsolutePath()}\\".replace("\\", "\\\\"))
    else -> json.replace(MAVEN_PREFIX, "${m2Repo.toAbsolutePath()}/")
}

internal fun toAbsolutePath(path: String, workspacePath: Path): Path =
    when {
        path.startsWith(WORKSPACE_PREFIX) -> {
            val relativePath = path.removePrefix(WORKSPACE_PREFIX)
            if (relativePath.isEmpty()) workspacePath else workspacePath.resolve(relativePath)
        }

        path.startsWith(MAVEN_PREFIX) -> {
            val relativePath = path.removePrefix(MAVEN_PREFIX)
            if (relativePath.isEmpty()) m2Repo else m2Repo.resolve(relativePath)
        }

        path.startsWith(USER_HOME_PREFIX) -> {
            val relativePath = path.removePrefix(USER_HOME_PREFIX)
            if (relativePath.isEmpty()) userHome else userHome.resolve(relativePath)
        }

        else -> Path.of(path)
    }