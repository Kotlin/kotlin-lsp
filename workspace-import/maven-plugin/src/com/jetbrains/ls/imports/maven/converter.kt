package com.jetbrains.ls.imports.maven

import kotlinx.serialization.json.Json
import org.apache.maven.project.MavenProject
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.collection.CollectRequest
import org.eclipse.aether.graph.Dependency
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.resolution.DependencyRequest
import org.eclipse.aether.resolution.DependencyResolutionException
import org.eclipse.aether.resolution.DependencyResult
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.stream.Collectors

private val KOTLIN_COMPILER_PLUGIN_JAR_PATTERN = Regex(
    ".*-compiler-plugin.*\\.jar"
)

object JavaScopes {
    const val COMPILE: String = "compile"
    const val PROVIDED: String = "provided"
    const val RUNTIME: String = "runtime"
    const val TEST: String = "test"
}

fun MavenProject.toWorkspaceData(
    repositorySystem: RepositorySystem,
    repositorySystemSession: RepositorySystemSession
): WorkspaceData {
    val libraries = ConcurrentHashMap<String, Artifact>()
    val kotlinSettings = Collections.synchronizedList(mutableListOf<KotlinSettingsData>())

    val mavenProjects = listOf(this) + (collectedProjects ?: emptyList())
    val projectMap = mavenProjects.associateBy {
        "${it.groupId}:${it.artifactId}"
    }
    val modules = mavenProjects
        .parallelStream()
        .map {
            it.toModuleData(
                projectMap,
                libraries,
                repositorySystem,
                repositorySystemSession,
                kotlinSettings
            )
        }
        .collect(Collectors.toList())

    return WorkspaceData(
        modules = modules,
        libraries = extractLibraries(libraries),
        sdks = emptyList(),
        kotlinSettings = kotlinSettings
    )
}

private fun MavenProject.toModuleData(
    projectMap: Map<String, MavenProject>,
    libraries: MutableMap<String, Artifact>,
    repositorySystem: RepositorySystem,
    repositorySystemSession: RepositorySystemSession,
    kotlinSettings: MutableList<KotlinSettingsData>
): ModuleData {
    val sourceRoots = buildList {
        compileSourceRoots?.forEach { sourceRoot ->
            add(SourceRootData(sourceRoot, "java-source"))
        }
        testCompileSourceRoots?.forEach { testRoot ->
            add(SourceRootData(testRoot, "java-test"))
        }
        resources?.forEach { resource ->
            add(SourceRootData(resource.directory, "java-resource"))
        }
        testResources?.forEach { resource ->
            add(SourceRootData(resource.directory, "java-test-resource"))
        }
    }

    val dependencies = buildList {
        add(DependencyData.InheritedSdk)
        add(DependencyData.ModuleSource)

        // Module dependencies
        dependencies?.asSequence()
            ?.filter {
                "${it.groupId}:${it.artifactId}" in projectMap
            }
            ?.forEach { dependency ->
                add(
                    DependencyData.Module(
                        dependency.artifactId,
                        toDependencyDataScope(dependency.scope),
                        false
                    )
                )
            }

        // Library dependencies
        dependencies?.asSequence()
            ?.filter {
                "${it.groupId}:${it.artifactId}" !in projectMap
            }
            ?.resolveDependencies(
                "Project: $name",
                remoteProjectRepositories,
                repositorySystem,
                repositorySystemSession
            )?.let { result ->
                result.artifactResults.forEach { artifactResult ->
                    val artifact = artifactResult.artifact
                    val depNode = artifactResult.request.dependencyNode
                    val scope = toDependencyDataScope(depNode?.dependency?.scope)
                    val libName = artifact.run {
                        "Maven: $groupId:$artifactId${if (version.isNotEmpty()) ":$version" else ""}"
                    }
                    add(DependencyData.Library(libName, scope, false))
                    libraries.putIfAbsent(libName, artifact)
                }
            }
    }

    extractKotlinSettings(kotlinSettings, repositorySystem, repositorySystemSession)

    return ModuleData(
        name = artifactId ?: "unknown",
        type = "JAVA_MODULE",
        dependencies = dependencies,
        contentRoots = listOf(
            ContentRootData(
                path = basedir?.absolutePath ?: "",
                sourceRoots = sourceRoots
            )
        ),
        facets = emptyList(),
    )
}

private fun toDependencyDataScope(scope: String?): DependencyDataScope {
    val scopeEnum = when ((scope ?: JavaScopes.COMPILE).lowercase()) {
        "test" -> DependencyDataScope.TEST
        "runtime" -> DependencyDataScope.RUNTIME
        "provided" -> DependencyDataScope.PROVIDED
        else -> DependencyDataScope.COMPILE
    }
    return scopeEnum
}

private fun Sequence<org.apache.maven.model.Dependency>.resolveDependencies(
    name: String,
    remoteRepositories: List<RemoteRepository>,
    repositorySystem: RepositorySystem,
    repositorySystemSession: RepositorySystemSession
): DependencyResult? {
    val collectRequest = CollectRequest()
    forEach { dep ->
        val dependency = Dependency(
            DefaultArtifact(
                dep.groupId,
                dep.artifactId,
                dep.classifier ?: "",
                dep.type ?: "jar",
                dep.version
            ),
            dep.scope ?: JavaScopes.COMPILE
        )
        collectRequest.addDependency(dependency)
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

        null
    }
}

private fun extractLibraries(
    libraries: Map<String, Artifact>
): List<LibraryData> {
    return libraries.values.map { artifact ->
        val libName = "Maven: ${artifact.groupId}:${artifact.artifactId}:${artifact.version}"

        val roots = mutableListOf<LibraryRootData>()
        // Use the file from the artifact directly (already resolved)
        if (artifact.file != null && artifact.file.exists()) {
            roots.add(
                LibraryRootData(
                    path = artifact.file.absolutePath,
                    type = "CLASSES",
                    inclusionOptions = InclusionOptions.ROOT_ITSELF
                )
            )
        }

        LibraryData(
            name = libName,
            level = "project",
            module = null,
            type = "repository",
            roots = roots,
            properties = XmlElement(
                tag = "properties",
                attributes = linkedMapOf(
                    "groupId" to artifact.groupId,
                    "artifactId" to artifact.artifactId,
                    "version" to artifact.version,
                    "baseVersion" to artifact.version,
                ),
                children = emptyList(),
                text = null
            )

        )
    }
}

private fun MavenProject.extractKotlinSettings(
    kotlinSettings: MutableList<KotlinSettingsData>,
    repositorySystem: RepositorySystem,
    repositorySystemSession: RepositorySystemSession
) {
    val kotlinPlugin = this.buildPlugins?.find { plugin ->
        plugin.groupId == "org.jetbrains.kotlin" && plugin.artifactId.startsWith("kotlin-maven-plugin")
    } ?: return

    println("Found Kotlin plugin: ${kotlinPlugin.groupId}:${kotlinPlugin.artifactId}:${kotlinPlugin.version}")

    val pluginClasspath = buildList {
        kotlinPlugin.dependencies?.asSequence()
            ?.resolveDependencies(
                "Plugin: ${kotlinPlugin.artifactId}",
                remoteProjectRepositories,
                repositorySystem,
                repositorySystemSession
            )?.let { result ->
                result.artifactResults.forEach { artifactResult ->
                    val artifact = artifactResult.artifact
                    if (artifact.file.name.matches(KOTLIN_COMPILER_PLUGIN_JAR_PATTERN)) {
                        add(artifact.file.absolutePath)
                    }
                }
            }
    }


    val moduleName = this.artifactId ?: "unknown"
    val config = kotlinPlugin.configuration as? org.codehaus.plexus.util.xml.Xpp3Dom

    val sourceRoots = buildList {
        compileSourceRoots?.forEach { add(it) }
        testCompileSourceRoots?.forEach { add(it) }
    }

    val jvmTarget = config?.getChild("jvmTarget")?.value
    val compilerArgs = config?.getChild("args")?.children?.map { it.value } ?: emptyList()
//    val compilerPlugins = config?.getChild("compilerPlugins")?.children?.map { it.value } ?: emptyList()
    val pluginOptions = config?.getChild("pluginOptions")?.children?.map { "plugin:${it.value}" } ?: emptyList()

    val compilerArguments = KotlinJvmCompilerArguments(
        jvmTarget = jvmTarget,
        pluginOptions = pluginOptions,
        pluginClasspaths = pluginClasspath
    )

    val kotlinSettingsData = KotlinSettingsData(
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

    kotlinSettings.add(kotlinSettingsData)
}