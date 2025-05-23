package com.jetbrains.ls.imports.json


import com.intellij.platform.workspace.jps.entities.ContentRootEntity
import com.intellij.platform.workspace.jps.entities.DependencyScope
import com.intellij.platform.workspace.jps.entities.ExcludeUrlEntity
import com.intellij.platform.workspace.jps.entities.FacetEntity
import com.intellij.platform.workspace.jps.entities.FacetEntityTypeId
import com.intellij.platform.workspace.jps.entities.InheritedSdkDependency
import com.intellij.platform.workspace.jps.entities.LibraryDependency
import com.intellij.platform.workspace.jps.entities.LibraryEntity
import com.intellij.platform.workspace.jps.entities.LibraryPropertiesEntity
import com.intellij.platform.workspace.jps.entities.LibraryRoot
import com.intellij.platform.workspace.jps.entities.LibraryRootTypeId
import com.intellij.platform.workspace.jps.entities.LibraryTableId
import com.intellij.platform.workspace.jps.entities.LibraryTableId.ModuleLibraryTableId
import com.intellij.platform.workspace.jps.entities.LibraryTypeId
import com.intellij.platform.workspace.jps.entities.ModuleDependency
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.jps.entities.ModuleId
import com.intellij.platform.workspace.jps.entities.ModuleSourceDependency
import com.intellij.platform.workspace.jps.entities.ModuleTypeId
import com.intellij.platform.workspace.jps.entities.SdkDependency
import com.intellij.platform.workspace.jps.entities.SdkEntity
import com.intellij.platform.workspace.jps.entities.SdkId
import com.intellij.platform.workspace.jps.entities.SdkRoot
import com.intellij.platform.workspace.jps.entities.SdkRootTypeId
import com.intellij.platform.workspace.jps.entities.SourceRootEntity
import com.intellij.platform.workspace.jps.entities.SourceRootTypeId
import com.intellij.platform.workspace.jps.entities.libraryProperties
import com.intellij.platform.workspace.jps.serialization.impl.toPath
import com.intellij.platform.workspace.storage.EntitySource
import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.entities
import com.intellij.platform.workspace.storage.url.VirtualFileUrl
import com.intellij.platform.workspace.storage.url.VirtualFileUrlManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import com.intellij.openapi.projectRoots.impl.JavaSdkImpl
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.platform.workspace.jps.entities.ModuleDependencyItem
import com.intellij.platform.workspace.storage.annotations.Child
import com.intellij.util.descriptors.ConfigFileItem
import org.jetbrains.kotlin.config.KotlinModuleKind
import org.jetbrains.kotlin.idea.workspaceModel.CompilerSettingsData
import org.jetbrains.kotlin.idea.workspaceModel.KotlinSettingsEntity
import com.jetbrains.ls.imports.utils.toIntellijUri


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
            .map { toDataClass(it) }
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
    contentRoot: @Child ContentRootEntity,
    workspacePath: Path
): ContentRootData = ContentRootData(
    path = toPath(contentRoot.url, workspacePath),
    excludedPatterns = contentRoot.excludedPatterns,
    excludedUrls = listOf(),
    sourceRoots = contentRoot.sourceRoots.map { sourceRoot ->
        SourceRootData(
            path = toPath(sourceRoot.url, workspacePath),
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
                path = toPath(root.url, workspacePath),
                type = root.type.name,
                inclusionOptions = InclusionOptions.valueOf(root.inclusionOptions.name),
            )
        },
        excludedRoots = entity.excludedRoots.map { toPath(it.url, workspacePath) },
        properties = entity.libraryProperties?.propertiesXmlTag?.let { parseXml(it).copy(tag = "") },
    )

private fun toDataClass(entity: SdkEntity, workspacePath: Path): SdkData =
    SdkData(
        name = entity.name,
        type = entity.type,
        version = entity.version,
        homePath = entity.homePath?.let { toPath(it, workspacePath) },
        roots = when {
            entity.homePath != null && entity.type == "JavaSDK" -> null // Let's calculate them on restore
            else -> entity.roots.map { SdkRootData(it.url.url, it.type.name) }
        },
        additionalData = entity.additionalData
    )

// TODO: Store relative paths where possible
@Suppress("DEPRECATION")
private fun toDataClass(entity: KotlinSettingsEntity): KotlinSettingsData =
    KotlinSettingsData(
        name = entity.name,
        sourceRoots = entity.sourceRoots,
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
        pureKotlinSourceFolders = entity.pureKotlinSourceFolders,
        kind = KotlinSettingsData.KotlinModuleKind.valueOf(entity.kind.name),
        compilerArguments = entity.compilerArguments,
        additionalArguments = entity.compilerSettings?.additionalArguments,
        scriptTemplates = entity.compilerSettings?.scriptTemplates,
        scriptTemplatesClasspath = entity.compilerSettings?.scriptTemplatesClasspath,
        copyJsLibraryFiles = entity.compilerSettings?.copyJsLibraryFiles == true,
        outputDirectoryForJsLibraryFiles = entity.compilerSettings?.outputDirectoryForJsLibraryFiles,
        targetPlatform = entity.targetPlatform,
        externalSystemRunTasks = entity.externalSystemRunTasks,
        version = entity.version,
        flushNeeded = entity.flushNeeded,
    )

private const val USER_HOME_PREFIX = "<HOME>/"
private const val WORKSPACE_PREFIX = "<WORKSPACE>/"
private val userHome = Path.of(System.getProperty("user.home"))

private fun toPath(url: VirtualFileUrl, workspacePath: Path): String {
    val path = url.toPath()
    var pathString = when {
        path.startsWith(workspacePath) -> WORKSPACE_PREFIX + workspacePath.relativize(path)
        path.startsWith(userHome) -> USER_HOME_PREFIX + userHome.relativize(path)
        else -> path.absolutePathString()
    }
    pathString = FileUtilRt.toSystemIndependentName(pathString)
    return pathString
}


fun workspaceModel(
    data: WorkspaceData,
    workspacePath: Path,
    entitySource: EntitySource,
    virtualFileUrlManager: VirtualFileUrlManager
): MutableEntityStorage {



    val storage = MutableEntityStorage.create()

    for (sdkData in data.sdks) {
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
            homePath = sdkData.homePath?.let { it.toIntellijUri(virtualFileUrlManager) }
        }
    }

    val libraryEntities = mutableMapOf<String, LibraryEntity>()
    for (libraryData in data.libraries) {
        val tableId = when (libraryData.level) {
            "module" -> ModuleLibraryTableId(ModuleId(libraryData.module!!))
            else -> LibraryTableId.ProjectLibraryTableId
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

    fun toEntityBuilder(moduleData: ModuleData): ModuleEntity.Builder = ModuleEntity(
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
        storage addEntity toEntity(kotlinSettingsData, entitySource, moduleBuilders[kotlinSettingsData.module]!!)
    }

    data.modules.forEach { moduleData ->
        val moduleEntity = moduleBuilders[moduleData.name]!!
        moduleData.facets.forEach {
            addFacetRecursive(it, moduleEntity)
        }
    }

    moduleBuilders.values.forEach { storage.addEntity(it) }

    return storage
}

private fun toEntity(
    kotlinSettingsData: KotlinSettingsData,
    entitySource: EntitySource,
    module: ModuleEntity.Builder
): KotlinSettingsEntity.Builder = KotlinSettingsEntity(
    name = kotlinSettingsData.name,
    moduleId = ModuleId(kotlinSettingsData.module),
    sourceRoots = kotlinSettingsData.sourceRoots,
    configFileItems = kotlinSettingsData.configFileItems.map { ConfigFileItem(it.id, it.url) },
    useProjectSettings = kotlinSettingsData.useProjectSettings,
    implementedModuleNames = kotlinSettingsData.implementedModuleNames,
    dependsOnModuleNames = kotlinSettingsData.dependsOnModuleNames,
    additionalVisibleModuleNames = kotlinSettingsData.additionalVisibleModuleNames,
    sourceSetNames = kotlinSettingsData.sourceSetNames,
    isTestModule = kotlinSettingsData.isTestModule,
    externalProjectId = kotlinSettingsData.externalProjectId,
    isHmppEnabled = kotlinSettingsData.isHmppEnabled,
    pureKotlinSourceFolders = kotlinSettingsData.pureKotlinSourceFolders,
    kind = KotlinModuleKind.valueOf(kotlinSettingsData.kind.name),
    externalSystemRunTasks = kotlinSettingsData.externalSystemRunTasks,
    version = kotlinSettingsData.version,
    flushNeeded = kotlinSettingsData.flushNeeded,
    entitySource = entitySource,
) {
    this.module = module
    if (kotlinSettingsData.additionalArguments != null
        && kotlinSettingsData.scriptTemplates != null
        && kotlinSettingsData.scriptTemplatesClasspath != null
        && kotlinSettingsData.outputDirectoryForJsLibraryFiles != null
    )
        this.compilerSettings = CompilerSettingsData(
            additionalArguments = kotlinSettingsData.additionalArguments,
            scriptTemplates = kotlinSettingsData.scriptTemplates,
            scriptTemplatesClasspath = kotlinSettingsData.scriptTemplatesClasspath,
            copyJsLibraryFiles = kotlinSettingsData.copyJsLibraryFiles,
            outputDirectoryForJsLibraryFiles = kotlinSettingsData.outputDirectoryForJsLibraryFiles,
        )
}

private fun addFacetRecursive(
    data: FacetData,
    moduleEntity: ModuleEntity.Builder,
): FacetEntity.Builder =
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

internal fun toAbsolutePath(path: String, workspacePath: Path,): Path =
    when {
        path.startsWith(WORKSPACE_PREFIX) -> {
            val relativePath = path.removePrefix(WORKSPACE_PREFIX)
            if (relativePath.isEmpty()) workspacePath else workspacePath.resolve(relativePath)
        }
        path.startsWith(USER_HOME_PREFIX) -> {
            val relativePath = path.removePrefix(USER_HOME_PREFIX)
            if (relativePath.isEmpty()) userHome else userHome.resolve(relativePath)
        }
        else -> Path.of(path)
    }