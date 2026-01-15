// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.maven

import kotlinx.serialization.json.Json
import org.apache.maven.project.MavenProject
import org.codehaus.plexus.util.xml.Xpp3Dom
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.collection.CollectRequest
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.resolution.DependencyRequest
import org.eclipse.aether.resolution.DependencyResolutionException
import org.eclipse.aether.resolution.DependencyResult
import org.eclipse.aether.util.artifact.JavaScopes
import java.util.*


private val KOTLIN_COMPILER_PLUGIN_JAR_PATTERN = Regex(".*-compiler-plugin.*\\.jar")
private val IMPORTED_CLASSIFIERS = setOf("client")

fun MavenProject.toWorkspaceData(
    repositorySystem: RepositorySystem,
    repositorySystemSession: RepositorySystemSession
): WorkspaceData {
    val modules = getAllModules(this)
    val modulesData = modules.map { it.toModuleData() }
    val kotlinSettings = modules.mapNotNull {
        it.mavenProject.extractKotlinSettings(it.moduleData.moduleName, repositorySystem, repositorySystemSession)
    }
    val libraries = collectLibraries(modules, repositorySystem, repositorySystemSession)

    return WorkspaceData(
        modules = modulesData,
        libraries = libraries,
        sdks = emptyList(),
        kotlinSettings = kotlinSettings,
        javaSettings = modules.map { it.javaSettings }
    )
}

private fun getAllModules(topLevelProject: MavenProject): List<MavenTreeModuleImportData> {
    val moduleNames = mapModuleNames(topLevelProject)
    val projects = listOf(topLevelProject) + (topLevelProject.collectedProjects ?: emptyList())

    val allModules = mutableListOf<MavenProjectImportData>()
    val moduleImportDataByMavenId = mutableMapOf<String, MavenProjectImportData>()

    for (project in projects) {
        val moduleName = moduleNames[project] ?: continue
        val mavenProjectImportData = getModuleImportData(project, moduleName)
        moduleImportDataByMavenId[project.id] = mavenProjectImportData
        allModules.add(mavenProjectImportData)
    }

    val allModuleDataWithDependencies = mutableListOf<MavenTreeModuleImportData>()
    for (importData in allModules) {
        val mavenModuleImportDataList = splitToModules(importData, moduleImportDataByMavenId)
        allModuleDataWithDependencies.addAll(mavenModuleImportDataList)
    }

    return allModuleDataWithDependencies
}

private fun mapModuleNames(root: MavenProject): Map<MavenProject, String> {
    val projects = listOf(root) + (root.collectedProjects ?: emptyList())
    val mavenProjectToModuleName = mutableMapOf<MavenProject, String>()
    val names = Array(projects.size) { i ->
        NameItem(projects[i])
    }

    names.sort()

    val nameCountersLowerCase = mutableMapOf<String, Int>()

    for (i in names.indices) {
        if (names[i].hasDuplicatedGroup) continue

        for (k in i + 1 until names.size) {
            if (names[i].originalName.equals(names[k].originalName, ignoreCase = true)) {
                nameCountersLowerCase[names[i].originalName.lowercase(Locale.ROOT)] = 0

                if (names[i].groupId == names[k].groupId) {
                    names[i].hasDuplicatedGroup = true
                    names[k].hasDuplicatedGroup = true
                }
            }
        }
    }

    val existingNames = mutableSetOf<String>()

    for (nameItem in names) {
        if (nameItem.existingName != null) {
            existingNames.add(nameItem.getResultName())
        }
    }

    for (nameItem in names) {
        if (nameItem.existingName == null) {
            val c = nameCountersLowerCase[nameItem.originalName.lowercase(Locale.ROOT)]

            if (c != null) {
                nameItem.number = c
                nameCountersLowerCase[nameItem.originalName.lowercase(Locale.ROOT)] = c + 1
            }

            while (true) {
                val name = nameItem.getResultName()
                if (existingNames.add(name)) break

                nameItem.number++
                nameCountersLowerCase[nameItem.originalName.lowercase(Locale.ROOT)] = nameItem.number + 1
            }
        }
    }

    for (each in names) {
        mavenProjectToModuleName[each.project] = each.getResultName()
    }

    return mavenProjectToModuleName
}

internal fun MavenTreeModuleImportData.toModuleData(): ModuleData {
    val project = this.mavenProject
    val module = this.moduleData

    val sourceRoots = buildList {
        if (module.type.containsMain) {
            project.compileSourceRoots?.forEach { sourceRoot ->
                add(SourceRootData(sourceRoot, "java-source"))
            }
            project.resources?.forEach { resource ->
                add(SourceRootData(resource.directory, "java-resource"))
            }
        }
        if (module.type.containsTest) {
            project.testCompileSourceRoots?.forEach { testRoot ->
                add(SourceRootData(testRoot, "java-test"))
            }
            project.testResources?.forEach { resource ->
                add(SourceRootData(resource.directory, "java-test-resource"))
            }
        }
    }

    val dependencies = buildList {
        add(DependencyData.InheritedSdk)
        add(DependencyData.ModuleSource)

        this@toModuleData.dependencies.forEach { dep ->
            when (dep) {
                is MavenImportDependency.Module -> {
                    add(DependencyData.Module(dep.moduleName, dep.scope, false))
                }

                is MavenImportDependency.Library -> {
                    val artifact = dep.artifact
                    val libName =
                        "Maven: ${artifact.groupId}:${artifact.artifactId}${if (artifact.version != null && artifact.version.isNotEmpty()) ":${artifact.version}" else ""}"
                    add(DependencyData.Library(libName, dep.scope, false))
                }

                is MavenImportDependency.System -> {
                    val artifact = dep.artifact
                    val libName = "Maven: ${artifact.groupId}:${artifact.artifactId}:${artifact.version}"
                    add(DependencyData.Library(libName, dep.scope, false))
                }

                is MavenImportDependency.AttachedJar -> {
                    add(DependencyData.Library(dep.name, dep.scope, false))
                }
            }
        }
    }

    return ModuleData(
        name = module.moduleName,
        type = "JAVA_MODULE",
        dependencies = dependencies,
        contentRoots = listOf(
            ContentRootData(
                path = project.basedir?.absolutePath ?: "",
                sourceRoots = sourceRoots
            )
        ),
        facets = emptyList(),
    )
}

private fun getModuleImportData(
    project: MavenProject,
    moduleName: String,
): MavenProjectImportData {
    val languageLevels = getLanguageLevels(project)
    if (needCreateCompoundModule(project, languageLevels)) {
        return getModuleImportDataCompound(project, moduleName, languageLevels)
    } else {
        return getModuleImportDataSingle(project, moduleName, languageLevels)
    }
}

private fun getModuleImportDataCompound(
    project: MavenProject,
    moduleName: String,
    languageLevels: LanguageLevels,
): MavenProjectImportData {
    val sourceLevel = languageLevels.sourceLevel
    val testSourceLevel = languageLevels.testSourceLevel
    val moduleData = MavenModuleData(moduleName, StandardMavenModuleType.COMPOUND_MODULE, sourceLevel)

    val mainData = MavenModuleData("$moduleName.main", StandardMavenModuleType.MAIN_ONLY, sourceLevel)
    val testData = MavenModuleData("$moduleName.test", StandardMavenModuleType.TEST_ONLY, testSourceLevel)

    val compileSourceRootModules = getNonDefaultCompilerExecutions(project).map { executionId ->
        val suffix = executionId
        val level = getSourceLanguageLevel(project, executionId) ?: sourceLevel
        MavenModuleData("$moduleName.$suffix", StandardMavenModuleType.MAIN_ONLY_ADDITIONAL, level)
    }

    return MavenProjectImportData(project, moduleData, listOf(mainData) + compileSourceRootModules + testData)
}

private fun getModuleImportDataSingle(
    project: MavenProject,
    moduleName: String,
    languageLevels: LanguageLevels,
): MavenProjectImportData {
    val type = if ("pom" == project.packaging) StandardMavenModuleType.AGGREGATOR else StandardMavenModuleType.SINGLE_MODULE
    val moduleData = MavenModuleData(moduleName, type, languageLevels.sourceLevel)
    return MavenProjectImportData(project, moduleData, listOf())
}

private fun needCreateCompoundModule(project: MavenProject, languageLevels: LanguageLevels): Boolean {
    if ("pom" == project.packaging) return false
    if (languageLevels.mainAndTestLevelsDiffer()) return true
    if (getNonDefaultCompilerExecutions(project).isNotEmpty()) return true
    return false
}

private fun splitToModules(
    importData: MavenProjectImportData,
    moduleImportDataByMavenId: Map<String, MavenProjectImportData>
): List<MavenTreeModuleImportData> {
    val submodules = importData.submodules
    val project = importData.mavenProject
    val module = importData.module

    val mainDependencies = mutableListOf<MavenImportDependency>()
    val testDependencies = mutableListOf<MavenImportDependency>()

    importData.mainSubmodules.forEach {
        testDependencies.add(MavenImportDependency.Module(it.moduleName, DependencyDataScope.COMPILE, false))
    }

    val testSubmodules = importData.testSubmodules
    for (artifact in project.dependencies) {
        for (dependency in getDependency(moduleImportDataByMavenId, artifact, project)) {
            if (testSubmodules.isNotEmpty() && dependency.scope == DependencyDataScope.TEST) {
                testDependencies.add(dependency)
            } else {
                mainDependencies.add(dependency)
            }
        }
    }

    if (submodules.isEmpty()) return listOf(MavenTreeModuleImportData(project, module, mainDependencies + testDependencies))

    val result = mutableListOf(MavenTreeModuleImportData(project, module, emptyList()))
    val defaultMainSubmodule = importData.defaultMainSubmodule
    val additionalMainDependencies = if (defaultMainSubmodule == null) emptyList()
    else listOf(MavenImportDependency.Module(defaultMainSubmodule.moduleName, DependencyDataScope.COMPILE, false))

    for (submodule in submodules) {
        val dependencies = when (submodule.type) {
            StandardMavenModuleType.MAIN_ONLY -> mainDependencies
            StandardMavenModuleType.MAIN_ONLY_ADDITIONAL -> mainDependencies + additionalMainDependencies
            StandardMavenModuleType.TEST_ONLY -> testDependencies + mainDependencies
            else -> null
        } ?: continue
        result.add(MavenTreeModuleImportData(project, submodule, dependencies))
    }

    return result
}

private fun getModuleName(data: MavenProjectImportData, isTestJar: Boolean): String {
    val submodule = if (isTestJar) data.testSubmodules.firstOrNull() else data.mainSubmodules.firstOrNull()
    return submodule?.moduleName ?: data.module.moduleName
}


private fun MavenProject.collectLibraries(
    modulesData: List<MavenTreeModuleImportData>,
    repositorySystem: RepositorySystem,
    repositorySystemSession: RepositorySystemSession
): List<LibraryData> {
    val moduleArtifacts = modulesData.map { it.mavenProject.artifactId }.toSet()

    val allLibraries = modulesData
        .flatMap { it.mavenProject.dependencies }
        .filter { "${it.groupId}:${it.artifactId}:${it.version}" !in moduleArtifacts }
        .distinct()

    val artifactsByLibrary =
        allLibraries.resolveDependencies("", remoteProjectRepositories, repositorySystem, repositorySystemSession, true, true)
            ?.let { result ->
                result.artifactResults
                    .filter { it.artifact != null }
                    .groupBy { "Maven: ${it.artifact.groupId}:${it.artifact.artifactId}:${it.artifact.version}" }
            } ?: emptyMap()

    val libraries = artifactsByLibrary.map { (libName, libArtifacts) ->
        LibraryData(
            name = libName,
            level = "project",
            module = null,
            type = "repository",
            roots = libArtifacts.map {
                LibraryRootData(
                    path = it.artifact.file.absolutePath,
                    type = it.artifact.classifier?.takeIf { it.isNotEmpty() }?.uppercase() ?: "CLASSES",
                )
            }
        )
    }
    return libraries
}

private fun getDependency(
    moduleImportDataByMavenId: Map<String, MavenProjectImportData>,
    artifact: org.apache.maven.model.Dependency,
    mavenProject: MavenProject
): List<MavenImportDependency> {
    val scope = toDependencyDataScope(artifact.scope)
    val depProjectData = moduleImportDataByMavenId.values.find {
        it.mavenProject.groupId == artifact.groupId && it.mavenProject.artifactId == artifact.artifactId
    }

    if (depProjectData != null) {
        if (depProjectData.mavenProject == mavenProject) return emptyList()

        val result = mutableListOf<MavenImportDependency>()
        val isTestJar = "test-jar" == artifact.type || "tests" == artifact.classifier
        val moduleName = getModuleName(depProjectData, isTestJar)

        createAttachArtifactDependency(depProjectData.mavenProject, scope, artifact)?.let { result.add(it) }

        val classifier = artifact.classifier
        if (classifier != null && IMPORTED_CLASSIFIERS.contains(classifier) && !isTestJar && "system" != artifact.scope) {
            result.add(MavenImportDependency.Library(artifact, scope))
        }

        result.add(MavenImportDependency.Module(moduleName, scope, isTestJar))
        return result
    } else if ("system" == artifact.scope) {
        return listOf(MavenImportDependency.System(artifact, scope))
    } else {
        val finalArtifact = if ("bundle" == artifact.type) {
            artifact.clone().apply { type = "jar" }
        } else artifact
        return listOf(MavenImportDependency.Library(finalArtifact, scope))
    }
}

private fun List<org.apache.maven.model.Dependency>.resolveDependencies(
    name: String,
    remoteRepositories: List<RemoteRepository>,
    repositorySystem: RepositorySystem,
    repositorySystemSession: RepositorySystemSession,
    resolveJavadoc: Boolean,
    resolveSources: Boolean,
): DependencyResult? {
    val collectRequest = CollectRequest()

    distinctBy { listOf(it.groupId, it.artifactId, it.version, it.classifier, it.type, it.scope) }
        .forEach { dep ->
            fun addArtifactDependency(classifier: String?) {
                collectRequest.addDependency(
                    org.eclipse.aether.graph.Dependency(
                        DefaultArtifact(
                            dep.groupId,
                            dep.artifactId,
                            classifier,
                            dep.type ?: "jar",
                            dep.version
                        ),
                        dep.scope ?: DependencyDataScope.COMPILE.name
                    )
                )
            }

            if (resolveJavadoc) {
                addArtifactDependency("javadoc")
            }
            if (resolveSources) {
                addArtifactDependency("sources")
            }
            addArtifactDependency(dep.classifier ?: "")
        }
    if (collectRequest.dependencies.isEmpty()) {
        return null
    }

    collectRequest.repositories = remoteRepositories

    val allowedScopes = setOf(
        JavaScopes.COMPILE, JavaScopes.RUNTIME,
        JavaScopes.PROVIDED, JavaScopes.TEST
    )
    val dependencyRequest = DependencyRequest(collectRequest) { node, _ ->
        val scope = node.dependency?.scope ?: JavaScopes.COMPILE
        scope in allowedScopes
    }

    return try {
        val result = repositorySystem.resolveDependencies(repositorySystemSession, dependencyRequest)
        println("[$name] Resolved ${result.artifactResults.size} dependencies")
        result
    } catch (e: DependencyResolutionException) {
        println("[$name] Resolution failed: ${e.message}")
        e.result
    }
}

private fun toDependencyDataScope(scope: String?): DependencyDataScope {
    return when (scope) {
        "test" -> DependencyDataScope.TEST
        "provided" -> DependencyDataScope.PROVIDED
        "runtime" -> DependencyDataScope.RUNTIME
        else -> DependencyDataScope.COMPILE
    }
}

private fun createAttachArtifactDependency(
    mavenProject: MavenProject,
    scope: DependencyDataScope,
    artifact: org.apache.maven.model.Dependency
): MavenImportDependency.AttachedJar? {
    val buildHelperCfg = getPluginGoalConfiguration(mavenProject, "org.codehaus.mojo", "build-helper-maven-plugin", "attach-artifact")
        ?: return null

    val roots = mutableListOf<Pair<String, String>>()
    var create = false

    val artifacts = buildHelperCfg.getChild("artifacts")
    if (artifacts != null) {
        for (artifactElement in artifacts.getChildren("artifact")) {
            val typeString = artifactElement.getChild("type")?.value?.trim()
            if (typeString != null && typeString != "jar") continue

            val filePath = artifactElement.getChild("file")?.value?.trim() ?: continue
            val classifier = artifactElement.getChild("classifier")?.value?.trim()

            val rootType = when (classifier) {
                "sources" -> "SOURCES"
                "javadoc" -> "JAVADOC"
                else -> "COMPILED"
            }
            roots.add(filePath to rootType)
            create = true
        }
    }

    return if (create) MavenImportDependency.AttachedJar(getAttachedJarsLibName(artifact), roots, scope) else null
}

private fun getAttachedJarsLibName(artifact: org.apache.maven.model.Dependency): String {
    val id = "${artifact.groupId}:${artifact.artifactId}:${artifact.version}"
    return "Maven: ATTACHED-JAR: $id"
}

private fun getLanguageLevels(project: MavenProject): LanguageLevels {
    return LanguageLevels(
        getSourceLanguageLevel(project),
        getTestSourceLanguageLevel(project),
        getTargetLanguageLevel(project),
        getTestTargetLanguageLevel(project)
    )
}

private fun getSourceLanguageLevel(project: MavenProject, executionId: String? = null): String? {
    return getCompilerProp(project, "source", executionId)
}

private fun getTestSourceLanguageLevel(project: MavenProject): String? {
    return getCompilerProp(project, "testSource") ?: getSourceLanguageLevel(project)
}

private fun getTargetLanguageLevel(project: MavenProject, executionId: String? = null): String? {
    return getCompilerProp(project, "target", executionId)
}

private fun getTestTargetLanguageLevel(project: MavenProject): String? {
    return getCompilerProp(project, "testTarget") ?: getTargetLanguageLevel(project)
}

private fun getCompilerProp(project: MavenProject, prop: String, executionId: String? = null): String? {
    val plugin = project.buildPlugins.find { it.groupId == "org.apache.maven.plugins" && it.artifactId == "maven-compiler-plugin" }
        ?: return project.properties.getProperty("maven.compiler.$prop")

    if (executionId != null) {
        val execution = plugin.executions.find { it.id == executionId }
        (execution?.configuration as? Xpp3Dom)?.getChild(prop)?.value?.let { return it }
    }

    (plugin.configuration as? Xpp3Dom)?.getChild(prop)?.value?.let { return it }
    return project.properties.getProperty("maven.compiler.$prop")
}

private fun getNonDefaultCompilerExecutions(project: MavenProject): List<String> {
    val plugin = project.buildPlugins.find { it.groupId == "org.apache.maven.plugins" && it.artifactId == "maven-compiler-plugin" }
        ?: return emptyList()

    return plugin.executions
        .filter { it.id != "default-compile" && it.id != "default-testCompile" }
        .filter { (it.configuration as? Xpp3Dom)?.getChild("compileSourceRoots") != null }
        .map { it.id }
}

private fun getPluginGoalConfiguration(project: MavenProject, groupId: String, artifactId: String, goal: String): Xpp3Dom? {
    val plugin = project.buildPlugins.find { it.groupId == groupId && it.artifactId == artifactId } ?: return null
    val execution = plugin.executions.find { it.goals.contains(goal) }
    return (execution?.configuration ?: plugin.configuration) as? Xpp3Dom
}

private fun MavenProject.extractKotlinSettings(
    moduleName: String,
    repositorySystem: RepositorySystem,
    repositorySystemSession: RepositorySystemSession
): KotlinSettingsData? {
    val kotlinPlugin = this.buildPlugins?.find { plugin ->
        plugin.groupId == "org.jetbrains.kotlin" && plugin.artifactId.startsWith("kotlin-maven-plugin")
    } ?: return null

    println("Found Kotlin plugin: ${kotlinPlugin.groupId}:${kotlinPlugin.artifactId}:${kotlinPlugin.version}")

    val pluginClasspath = buildList {
        kotlinPlugin.dependencies
            .resolveDependencies(
                "Plugin: ${kotlinPlugin.artifactId}",
                remoteProjectRepositories,
                repositorySystem,
                repositorySystemSession,
                false,
                false
            )?.let { result ->
                result.artifactResults.forEach { artifactResult ->
                    val artifact = artifactResult.artifact
                    if (artifact.file.name.matches(KOTLIN_COMPILER_PLUGIN_JAR_PATTERN)) {
                        add(artifact.file.absolutePath)
                    }
                }
            }
    }

    val config = kotlinPlugin.configuration as? org.codehaus.plexus.util.xml.Xpp3Dom

    val sourceRoots = buildList {
        compileSourceRoots?.forEach { add(it) }
        testCompileSourceRoots?.forEach { add(it) }
    }

    val jvmTarget = config?.getChild("jvmTarget")?.value
    val compilerArgs = config?.getChild("args")?.children?.map { it.value } ?: emptyList()
    val pluginOptions = config?.getChild("pluginOptions")?.children?.map { "plugin:${it.value}" } ?: emptyList()

    val compilerArguments = KotlinJvmCompilerArguments(
        jvmTarget = jvmTarget,
        pluginOptions = pluginOptions,
        pluginClasspaths = pluginClasspath
    )

    return KotlinSettingsData(
        name = "Kotlin",
        sourceRoots = sourceRoots,
        configFileItems = emptyList(),
        module = moduleName,
        useProjectSettings = false,
        implementedModuleNames = emptyList(),
        dependsOnModuleNames = emptyList(),
        additionalVisibleModuleNames = emptySet(),
        productionOutputPath = this.build?.outputDirectory,
        testOutputPath = this.build?.testOutputDirectory,
        sourceSetNames = emptyList(),
        isTestModule = false,
        externalProjectId = "${this.groupId}:${this.artifactId}:${this.version}",
        isHmppEnabled = true,
        pureKotlinSourceFolders = emptyList(),
        kind = KotlinSettingsData.KotlinModuleKind.DEFAULT,
        compilerArguments = "J${Json.encodeToString(compilerArguments)}",
        additionalArguments = compilerArgs.joinToString(" "),
        scriptTemplates = null,
        scriptTemplatesClasspath = null,
        copyJsLibraryFiles = false,
        outputDirectoryForJsLibraryFiles = null,
        targetPlatform = null,
        externalSystemRunTasks = emptyList(),
        version = 5,
        flushNeeded = false
    )
}

private fun isValidName(name: String?): Boolean {
    if (name.isNullOrBlank()) return false
    if (name == "Unknown") return false

    for (element in name) {
        if (!(element.isDigit() || element.isLetter() || element == '-' || element == '_' || element == '.')) {
            return false
        }
    }
    return true
}

private val StandardMavenModuleType.containsMain: Boolean
    get() = when (this) {
        StandardMavenModuleType.SINGLE_MODULE,
        StandardMavenModuleType.MAIN_ONLY,
        StandardMavenModuleType.MAIN_ONLY_ADDITIONAL -> true
        else -> false
    }

private val StandardMavenModuleType.containsTest: Boolean
    get() = when (this) {
        StandardMavenModuleType.SINGLE_MODULE,
        StandardMavenModuleType.TEST_ONLY -> true
        else -> false
    }

internal enum class StandardMavenModuleType {
    AGGREGATOR,
    SINGLE_MODULE,
    COMPOUND_MODULE,
    MAIN_ONLY,
    MAIN_ONLY_ADDITIONAL,
    TEST_ONLY
}

internal data class MavenModuleData(
    val moduleName: String,
    val type: StandardMavenModuleType,
    val sourceLanguageLevel: String?,
)

internal sealed class MavenImportDependency(val scope: DependencyDataScope) {
    class Module(val moduleName: String, scope: DependencyDataScope, val isTestJar: Boolean) : MavenImportDependency(scope)
    class Library(val artifact: org.apache.maven.model.Dependency, scope: DependencyDataScope) : MavenImportDependency(scope)
    class System(val artifact: org.apache.maven.model.Dependency, scope: DependencyDataScope) : MavenImportDependency(scope)
    class AttachedJar(val name: String, val roots: List<Pair<String, String>>, scope: DependencyDataScope) : MavenImportDependency(scope)
}

internal class MavenTreeModuleImportData(
    val mavenProject: MavenProject,
    val moduleData: MavenModuleData,
    val dependencies: List<MavenImportDependency>,
) {
    val javaSettings: JavaSettingsData
        get() {
            val level = moduleData.sourceLanguageLevel
            val jdkLevel = when {
                level == null -> null
                level.length == 1 -> "JDK_1_$level"
                else -> "JDK_${level.replace('.', '_')}"
            }
            return JavaSettingsData(
                module = moduleData.moduleName,
                inheritedCompilerOutput = false,
                excludeOutput = false,
                compilerOutput = null,
                compilerOutputForTests = null,
                languageLevelId = jdkLevel,
                manifestAttributes = emptyMap()
            )
        }
}

private data class LanguageLevels(
    val sourceLevel: String?,
    val testSourceLevel: String?,
    val targetLevel: String?,
    val testTargetLevel: String?,
) {
    fun mainAndTestLevelsDiffer(): Boolean {
        return (testSourceLevel != null && testSourceLevel != sourceLevel)
                || (testTargetLevel != null && testTargetLevel != targetLevel)
    }
}

private class MavenProjectImportData(
    val mavenProject: MavenProject,
    val module: MavenModuleData,
    val submodules: List<MavenModuleData>,
) {
    val defaultMainSubmodule = submodules.firstOrNull { it.type == StandardMavenModuleType.MAIN_ONLY }
    val mainSubmodules =
        submodules.filter { it.type == StandardMavenModuleType.MAIN_ONLY || it.type == StandardMavenModuleType.MAIN_ONLY_ADDITIONAL }
    val testSubmodules = submodules.filter { it.type == StandardMavenModuleType.TEST_ONLY }
}

private class NameItem(
    val project: MavenProject,
    val existingName: String? = null
) : Comparable<NameItem> {
    val originalName: String = calcOriginalName()
    val groupId: String = project.groupId.takeIf { isValidName(it) } ?: ""
    var number: Int = -1
    var hasDuplicatedGroup: Boolean = false

    private fun calcOriginalName(): String =
        existingName ?: getDefaultModuleName()

    private fun getDefaultModuleName(): String =
        project.artifactId.takeIf { isValidName(it) } ?: project.basedir.name

    fun getResultName(): String {
        if (existingName != null) return existingName

        if (number == -1) return originalName
        var result = "$originalName (${number + 1})"
        if (!hasDuplicatedGroup && groupId.isNotEmpty()) {
            result += " ($groupId)"
        }
        return result
    }

    override fun compareTo(other: NameItem): Int {
        val path1 = project.basedir?.absolutePath ?: ""
        val path2 = other.project.basedir?.absolutePath ?: ""
        return path1.compareTo(path2, ignoreCase = true)
    }
}