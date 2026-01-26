// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.maven

import kotlinx.serialization.json.Json
import org.apache.maven.artifact.Artifact
import org.apache.maven.model.Plugin
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
import java.nio.file.Path
import java.util.*


private val KOTLIN_COMPILER_PLUGIN_JAR_PATTERN = Regex(".*-compiler-plugin.*\\.jar")
private val IMPORTED_CLASSIFIERS = setOf("client")

fun MavenProject.toWorkspaceData(
    repositorySystem: RepositorySystem,
    repositorySystemSession: RepositorySystemSession
): WorkspaceData {
    val modules = getAllModules(this)

    val kotlinSettings = modules.mapNotNull {
        it.mavenProject.extractKotlinSettings(it.moduleData, repositorySystem, repositorySystemSession)
    }
    val modulesData = modules.map { it.toModuleData(kotlinSettings) }

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
        val mavenModuleImportDataList = convertToModules(importData, moduleImportDataByMavenId)
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

internal fun MavenTreeModuleImportData.toModuleData(kotlinSettingsList: List<KotlinSettingsData>): ModuleData {
    val project = this.mavenProject
    val module = this.moduleData
    val kotlinSettings = kotlinSettingsList.firstOrNull { it.module == module.moduleName }

    val sourceRoots = sourceRootData(module, project, kotlinSettings)

    val dependencies = dependencyData(this.dependencies)

    return ModuleData(
        name = module.moduleName,
        type = "JAVA_MODULE",
        dependencies = dependencies,
        contentRoots =
            contentRootData(project, module, sourceRoots),
        facets = emptyList(),
    )
}

private fun dependencyData(importDependencies: List<MavenImportDependency>): List<DependencyData> = buildList {
    add(DependencyData.InheritedSdk)
    add(DependencyData.ModuleSource)

    importDependencies.forEach { dep ->
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
                val libName = createLibName(artifact)
                add(DependencyData.Library(libName, dep.scope, false))
            }

            is MavenImportDependency.AttachedJar -> {
                add(DependencyData.Library(dep.name, dep.scope, false))
            }
        }
    }
}

private fun sourceRootData(
    module: MavenModuleData,
    project: MavenProject,
    kotlinSettings: KotlinSettingsData?
): List<SourceRootData> = buildSet {
    if (module.type.containsMain) {
        project.compileSourceRoots?.forEach { sourceRoot ->
            add(SourceRootData(sourceRoot, "java-source"))
        }
        project.resources?.forEach { resource ->
            if (resource.directory != project.basedir?.absolutePath) {
                add(SourceRootData(resource.directory, "java-resource"))
            }
        }
        kotlinSettings?.sourceRoots?.forEach {
            add(SourceRootData(it, "java-source"))
        }
    }
    if (module.type.containsTest) {
        project.testCompileSourceRoots?.forEach { testRoot ->
            add(SourceRootData(testRoot, "java-test"))
        }
        project.testResources?.forEach { resource ->
            if (resource.directory != project.basedir?.absolutePath) {
                add(SourceRootData(resource.directory, "java-test-resource"))
            }
        }
        kotlinSettings?.sourceRoots?.forEach {
            add(SourceRootData(it, "java-test"))
        }
    }
}.toList()

private fun contentRootData(
    project: MavenProject,
    moduleData: MavenModuleData,
    sourceRoots: List<SourceRootData>
): List<ContentRootData> {

    if (moduleData.type == StandardMavenModuleType.MAIN_ONLY || moduleData.type == StandardMavenModuleType.MAIN_ONLY_ADDITIONAL) {
        return sourceRoots.filter { it.type in listOf("java-source", "java-resource") }.map {
            ContentRootData(
                path = it.path,
                sourceRoots = listOf(it)
            )
        }
    } else if (moduleData.type == StandardMavenModuleType.TEST_ONLY) {
        return sourceRoots.filter { it.type in listOf("java-test", "java-test-resource") }.map {
            ContentRootData(
                path = it.path,
                sourceRoots = listOf(it)
            )
        }
    }
    return listOf(
        ContentRootData(
            path = project.basedir?.absolutePath ?: "",
            sourceRoots = sourceRoots
        )
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

private fun convertToModules(
    importData: MavenProjectImportData,
    mavenIdToModuleMapping: Map<String, MavenProjectImportData>
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
    for (artifact in project.artifacts) {
        for (dependency in convertDependencies(artifact, mavenIdToModuleMapping, project)) {
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


fun getArtifactPath(artifact: Artifact, newClassifier: String?): Path {
    if (newClassifier.isNullOrEmpty()) return artifact.file.toPath()
    val currentPath = artifact.file.toPath()
    val file = currentPath.fileName.toString()
    val newFileName = fileNameWithNewClassifier(file, artifact.classifier, newClassifier)
    return currentPath.parent.resolve(newFileName)
}

fun fileNameWithNewClassifier(
    fileName: String,
    currentClassifier: String?,
    newClassifier: String
): String {
    val dot = fileName.lastIndexOf('.')
    require(dot >= 0)

    val base = fileName.substring(0, dot)
    val ext = fileName.substring(dot)

    val cur = currentClassifier?.trim().orEmpty()

    return if (cur.isEmpty()) {
        "$base-$newClassifier$ext"
    } else {
        val suffix = "-$cur"
        if (base.endsWith(suffix)) {
            base.removeSuffix(suffix) + "-$newClassifier$ext"
        } else {
            "$base-$newClassifier$ext"
        }
    }
}

private fun MavenProject.collectLibraries(
    modulesData: List<MavenTreeModuleImportData>,
    repositorySystem: RepositorySystem,
    repositorySystemSession: RepositorySystemSession
): List<LibraryData> {

    val allArtifacts = modulesData
        .flatMap { it.dependencies }
        .filterIsInstance<MavenImportDependencyWithArtifact>()
        .map { it.artifact }
        .distinct()


    val libraries = allArtifacts.map { artifact ->
        val libName = createLibName(artifact)

        LibraryData(
            name = libName,
            level = "project",
            module = null,
            type = "repository",
            roots = listOf(
                LibraryRootData(
                    path = getArtifactPath(artifact, "javadoc").toAbsolutePath().toString(),
                    type = "JAVADOC"
                ),
                LibraryRootData(
                    path = getArtifactPath(artifact, "sources").toAbsolutePath().toString(),
                    type = "SOURCES"
                ),

                LibraryRootData(
                    path = artifact.file.absolutePath,
                    type = artifact.classifier?.takeIf { it.isNotEmpty() }?.uppercase() ?: "CLASSES",
                )
            )
        )
    }
    return libraries
}

private fun createLibName(artifact: Artifact): String {
    return "Maven: " + listOfNotNull(

        artifact.groupId,
        artifact.artifactId,
        artifact.baseVersion,
        artifact.classifier,
        artifact.type.takeIf { it != "jar" }
    ).joinToString(":")
}

private fun convertDependencies(
    artifact: Artifact,
    moduleImportDataByMavenId: Map<String, MavenProjectImportData>,
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
        return listOf(MavenImportDependency.Library(artifact, scope))
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
    artifact: Artifact
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

private fun getAttachedJarsLibName(artifact: Artifact): String {
    val id = "${artifact.groupId}:${artifact.artifactId}:${artifact.version}"
    return "Maven: ATTACHED-JAR: $id"
}

private fun getLanguageLevels(project: MavenProject): LanguageLevels {
    // Replicate the IDEA Maven importer behavior from MavenImportUtil and MavenProjectImportContextProvider
    val setUpSourceLevel = getSourceLanguageLevel(project)
    val setUpTestSourceLevel = getTestSourceLanguageLevel(project)
    val setUpTargetLevel = getTargetLanguageLevel(project)
    val setUpTestTargetLevel = getTestTargetLanguageLevel(project)

    val sourceLevel = getLanguageLevel(project) { setUpSourceLevel }
    val testSourceLevel = getLanguageLevel(project) { setUpTestSourceLevel }
    val targetLevel = getLanguageLevel(project) { setUpTargetLevel }
    val testTargetLevel = getLanguageLevel(project) { setUpTestTargetLevel }

    return LanguageLevels(sourceLevel, testSourceLevel, targetLevel, testTargetLevel)
}

private fun getLanguageLevel(mavenProject: MavenProject, supplier: () -> String?): String {
    var level: String? = null

    val cfg = getPluginGoalConfiguration(mavenProject, "com.googlecode", "maven-idea-plugin", "idea")
    if (cfg != null) {
        level = cfg.getChild("jdkLevel")?.value?.trim()
        if (level != null) {
            level = when (level) {
                "JDK_1_3" -> "1.3"
                "JDK_1_4" -> "1.4"
                "JDK_1_5" -> "1.5"
                "JDK_1_6" -> "1.6"
                "JDK_1_7" -> "1.7"
                else -> level
            }
        }
    }

    if (level == null) {
        level = supplier()
    }

    if (level == null) {
        level = getDefaultLevel(mavenProject)
    }

    val feature = parseJavaFeatureNumber(level)
    if (feature != null && feature >= 11) {
        level = adjustPreviewLanguageLevel(mavenProject, level)
    }

    return level
}

private fun getDefaultLevel(mavenProject: MavenProject): String {
    val plugin = mavenProject.buildPlugins.find { it.groupId == "org.apache.maven.plugins" && it.artifactId == "maven-compiler-plugin" }
    if (plugin != null && plugin.version != null) {
        if (compareVersions("3.11.0", plugin.version) <= 0) {
            return "1.8"
        }
        if (compareVersions("3.9.0", plugin.version) <= 0) {
            return "1.7"
        }
        if (compareVersions("3.8.0", plugin.version) <= 0) {
            return "1.6"
        }
    }
    return "1.5"
}

private fun compareVersions(v1: String, v2: String): Int {
    val components1 = v1.split('.').mapNotNull { it.toIntOrNull() }
    val components2 = v2.split('.').mapNotNull { it.toIntOrNull() }
    for (i in 0 until maxOf(components1.size, components2.size)) {
        val c1 = components1.getOrElse(i) { 0 }
        val c2 = components2.getOrElse(i) { 0 }
        if (c1 != c2) return c1.compareTo(c2)
    }
    return 0
}

private fun adjustPreviewLanguageLevel(mavenProject: MavenProject, level: String): String {
    val enablePreviewProperty = mavenProject.properties.getProperty("maven.compiler.enablePreview")
    if (enablePreviewProperty.toBoolean()) {
        return "$level-preview"
    }

    val compilerPlugin =
        mavenProject.buildPlugins.find { it.groupId == "org.apache.maven.plugins" && it.artifactId == "maven-compiler-plugin" }
    val compilerConfiguration = compilerPlugin?.configuration as? Xpp3Dom
    if (compilerConfiguration != null) {
        val enablePreviewParameter = compilerConfiguration.getChild("enablePreview")?.value?.trim()
        if (enablePreviewParameter.toBoolean()) {
            return "$level-preview"
        }

        val compilerArgs = compilerConfiguration.getChild("compilerArgs")
        if (compilerArgs != null) {
            if (isPreviewText(compilerArgs) ||
                compilerArgs.children.any { isPreviewText(it) }
            ) {
                return "$level-preview"
            }
        }
    }

    return level
}

private fun isPreviewText(child: Xpp3Dom): Boolean {
    return "--enable-preview" == child.value?.trim()
}

private fun parseJavaFeatureNumber(level: String?): Int? {
    if (level.isNullOrBlank()) return null
    val trimmed = level.trim()
    // Accept common forms:
    // - "1.8" / "1.7" etc
    // - "8", "11", "17"
    // - "17.0" / "17.0.1" (take the feature component)
    val parts = trimmed.split('.', '-', '_')
    if (parts.isEmpty()) return null
    return if (parts[0] == "1" && parts.size >= 2) {
        parts[1].toIntOrNull()
    } else {
        parts[0].toIntOrNull()
    }
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
    if ("release" == prop || isReleaseCompilerProp(project)) {
        val release = doGetCompilerProp(project, "release", executionId)
        if (release != null) return release
    }
    return doGetCompilerProp(project, prop, executionId)
}

private fun isReleaseCompilerProp(project: MavenProject): Boolean {
    val plugin = project.buildPlugins.find { it.groupId == "org.apache.maven.plugins" && it.artifactId == "maven-compiler-plugin" }
    val version = plugin?.version ?: return false
    return compareVersions("3.6", version) >= 0
}

private fun doGetCompilerProp(project: MavenProject, prop: String, executionId: String? = null): String? {
    val plugin = project.buildPlugins.find { it.groupId == "org.apache.maven.plugins" && it.artifactId == "maven-compiler-plugin" }
        ?: return project.properties.getProperty("maven.compiler.$prop")

    if (executionId != null) {
        val execution = plugin.executions.find { it.id == executionId }
        val config = execution?.configuration as? Xpp3Dom
        if (config != null) {
            config.getChild(prop)?.value?.let { return it }
            if (prop == "source" || prop == "target") {
                config.getChild("compilerArgument")?.value?.let { arg ->
                    if (arg.startsWith("-source ") && prop == "source") return arg.substring("-source ".length).trim()
                    if (arg.startsWith("-target ") && prop == "target") return arg.substring("-target ".length).trim()
                }
            }
        }
    }

    val pluginConfig = plugin.configuration as? Xpp3Dom
    if (pluginConfig != null) {
        pluginConfig.getChild(prop)?.value?.let { return it }
        if (prop == "source" || prop == "target") {
            pluginConfig.getChild("compilerArgument")?.value?.let { arg ->
                if (arg.startsWith("-source ") && prop == "source") return arg.substring("-source ".length).trim()
                if (arg.startsWith("-target ") && prop == "target") return arg.substring("-target ".length).trim()
            }
        }
    }

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
    moduleData: MavenModuleData,
    repositorySystem: RepositorySystem,
    repositorySystemSession: RepositorySystemSession
): KotlinSettingsData? {

    if (moduleData.type == StandardMavenModuleType.COMPOUND_MODULE) return null
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

    val kotlinCompileSourceRoots = kotlinPlugin.kotlinSourceDirs("compile")
    val kotlinCompileTestRoots = kotlinPlugin.kotlinSourceDirs("test-compile")


    val sourceRoots =
        if (moduleData.type == StandardMavenModuleType.MAIN_ONLY || moduleData.type == StandardMavenModuleType.MAIN_ONLY_ADDITIONAL) {
            kotlinCompileSourceRoots
        } else if (moduleData.type == StandardMavenModuleType.TEST_ONLY) {
            kotlinCompileTestRoots
        } else {
            kotlinCompileSourceRoots + kotlinCompileTestRoots
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
        sourceRoots = sourceRoots.toList(),
        configFileItems = emptyList(),
        module = moduleData.moduleName,
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

private fun Plugin.kotlinSourceDirs(execution: String): Set<String> {
    return (executions.firstOrNull { it.id == execution }?.configuration as? Xpp3Dom)
        ?.getChild("sourceDirs")
        ?.getChildren("sourceDir")
        ?.mapNotNull { it.value }
        ?.toSet()
        ?: emptySet()
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

interface MavenImportDependencyWithArtifact {
    val artifact: Artifact
    val scope: DependencyDataScope
}

internal sealed class MavenImportDependency(val scope: DependencyDataScope) {
    class Module(val moduleName: String, scope: DependencyDataScope, val isTestJar: Boolean) : MavenImportDependency(scope)
    class Library(override val artifact: Artifact, scope: DependencyDataScope) : MavenImportDependency(scope),
        MavenImportDependencyWithArtifact

    class System(override val artifact: Artifact, scope: DependencyDataScope) : MavenImportDependency(scope),
        MavenImportDependencyWithArtifact

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
            val isPreview = level?.endsWith("-preview") == true
            val rawLevel = level?.removeSuffix("-preview")
            val feature = parseJavaFeatureNumber(rawLevel)
            val jdkLevel = when {
                feature == null -> null
                feature <= 8 -> "JDK_1_$feature"
                else -> "JDK_$feature"
            }
            val finalJdkLevel = if (isPreview) {
                jdkLevel?.let { "${it}_PREVIEW" }
            } else {
                jdkLevel
            }
            return JavaSettingsData(
                module = moduleData.moduleName,
                inheritedCompilerOutput = false,
                excludeOutput = false,
                compilerOutput = null,
                compilerOutputForTests = null,
                languageLevelId = finalJdkLevel,
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