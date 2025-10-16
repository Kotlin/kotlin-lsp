// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.kotlinLsp.util

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.projectRoots.DefaultJdkConfigurator
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.impl.JavaSdkImpl
import com.intellij.openapi.util.io.toNioPathOrNull
import com.intellij.util.PathUtil
import com.jetbrains.ls.api.core.util.*
import com.jetbrains.ls.snapshot.api.impl.core.InitializeParamsEntity
import com.jetbrains.lsp.protocol.URI
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.kotlin.idea.base.plugin.artifacts.KotlinArtifacts
import org.jetbrains.kotlin.idea.compiler.configuration.isRunningFromSources
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isDirectory

fun WorkspaceModelBuilder.addKotlinStdlib() {
    addLibrary(
        LSLibrary(
            binaryRoots = listOf(getKotlinStdlibPath().toLspUri()),
            sourceRoots = listOfNotNull(getKotlinStdlibSourcesPath()?.toLspUri()),
            name = "stdlib"
        )
    )
}

fun getKotlinStdlibPath(): Path {
    return when {
        isRunningFromSources -> {
            // get kotlin stdlib used for monorepo compilation
            KotlinArtifacts.kotlinStdlib.toPath()
        }

        else -> {
            // get kotlin stdlib from the current classpath
            val classFromStdlibButNotInBuiltins = Sequence::class.java
            val stdlibJar = PathUtil.getJarPathForClass(classFromStdlibButNotInBuiltins)
            stdlibJar.toNioPathOrNull()
                ?: error("Failed to find kotlin stdlib jar in $stdlibJar")
        }
    }
}

fun getKotlinStdlibSourcesPath(): Path? {
    return when {
        isRunningFromSources -> {
            // get kotlin stdlib used for monorepo compilation
            KotlinArtifacts.kotlinStdlibSources.toPath()
        }

        else -> {
           null // LSP-224 TODO we should bundle the sources jar
        }
    }
}


fun WorkspaceModelBuilder.registerStdlibAndJdk() {
    addKotlinStdlib()
    addSdk( LSSdk(roots = jdkRoots(), name = "Java SDK", type = JavaSdk.getInstance()))
}

@OptIn(ExperimentalCoroutinesApi::class)
fun javaHome(): String {
    val initializeParams = InitializeParamsEntity.single().initializeParams.takeIf { it.isCompleted }?.getCompleted()
    val defaultJdkPath = initializeParams?.initializationOptions
        ?.let { it as? JsonObject }
        ?.get("defaultJdk")
        ?.let { it as? JsonPrimitive }
        ?.takeIf { it.isString }
        ?.content
        ?.takeIf { it.isNotBlank()}

    if (defaultJdkPath != null) {
        if (!Path.of(defaultJdkPath).isDirectory()) {
            error("Configured Java home does not exist or is not a directory: $defaultJdkPath")
        }
        return defaultJdkPath
    }

    val jdkConfigurator = ApplicationManager.getApplication().getService(DefaultJdkConfigurator::class.java)
    return jdkConfigurator.guessJavaHome() ?: System.getProperty("java.home")
}

fun jdkRoots(): List<URI> {
    val path = Paths.get(javaHome())
    require(path.isDirectory()) { "Expected a directory, got $path" }
    return JavaSdkImpl.findClasses(path, false).map { (it.replace("!/", "!/modules/").intellijUriToLspUri()) }
}