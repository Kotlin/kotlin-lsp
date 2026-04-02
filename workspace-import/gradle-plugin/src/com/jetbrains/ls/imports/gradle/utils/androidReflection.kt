// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")

package com.jetbrains.ls.imports.gradle.utils

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import java.io.File

val Project.androidComponents: AndroidComponentsExtensionReflection?
    get() = extensions.findByName("androidComponents")?.let { source ->
        AndroidComponentsExtensionReflection(this, source)
    }

class AndroidComponentsExtensionReflection(private val project: Project, val source: Any) {
    private val reflected = source.reflected

    fun onVariants(action: (AndroidVariantReflection) -> Unit) {
        val selector = reflected.call("selector")?.call("all") ?: run {
            project.logger.error("Failed to find 'selector' in 'androidComponents' extension")
            return
        }

        val onVariantAction = Action<Any> { variant ->
            action(AndroidVariantReflection(project, variant.reflected))
        }

        reflected.call("onVariants", param(selector), param(onVariantAction)) ?: run {
            project.logger.error("Failed to call 'onVariants' in 'androidComponents' extension")
        }
    }

    val sdkComponents: AndroidSdkComponentsReflection? by lazy {
        val reflected = reflected.call("getSdkComponents") ?: run {
            project.logger.error("Failed to call 'getSdkComponents' in 'androidComponents' extension")
            return@lazy null
        }
        AndroidSdkComponentsReflection(project, reflected)
    }
}

class AndroidSdkComponentsReflection(val project: Project, val reflected: Reflected.Instance) {
    val bootClasspath: Provider<List<RegularFile>>? by lazy {
        reflected.call("getBootClasspath")?.unwrapAs<Provider<List<RegularFile>>>() ?: run {
            project.logger.error("Failed to call 'getBootClasspath' in 'sdkComponents'")
            return@lazy null
        }
    }
}

class AndroidVariantReflection(project: Project, reflected: Reflected.Instance) : AndroidComponentReflection(project, reflected) {
    val nestedComponents: List<AndroidComponentReflection>? by lazy {
        reflected.call("getNestedComponents")?.unwrapAs<Collection<*>>()?.mapNotNull { source ->
            if (source == null) return@mapNotNull null

            runCatching {
                val hostTestClass = Class.forName("com.android.build.api.variant.HostTest", true, source.javaClass.classLoader)
                if (hostTestClass.isInstance(source)) {
                    return@mapNotNull AndroidHostTestComponentReflection(project, source.reflected)
                }
            }.onFailure {
                project.logger.error("Failed to resolve Android component", it)
            }

            runCatching {
                val hostTestClass = Class.forName("com.android.build.api.variant.DeviceTest", true, source.javaClass.classLoader)
                if (hostTestClass.isInstance(source)) {
                    return@mapNotNull AndroidDeviceTestComponentReflection(project, source.reflected)
                }
            }.onFailure {
                project.logger.error("Failed to resolve Android component", it)
            }

            null
        }
    }
}

class AndroidSourcesReflection(private val project: Project, val reflected: Reflected.Instance) {

    val kotlin: Provider<Collection<Directory>>? by lazy {
        reflected.call("getKotlin")?.call("getAll")?.unwrapAs<Provider<Collection<Directory>>>() ?: run {
            project.logger.error("Failed to find 'kotlin' in sources")
            return@lazy null
        }
    }

    val java: Provider<Collection<Directory>>? by lazy {
        reflected.call("getJava")?.call("getAll")?.unwrapAs<Provider<Collection<Directory>>>() ?: run {
            project.logger.error("Failed to find 'java' in sources")
            return@lazy null
        }
    }

    val resources: Provider<Collection<Directory>>? by lazy {
        reflected.call("getResources")?.call("getAll")?.unwrapAs<Provider<Collection<Directory>>>() ?: run {
            project.logger.error("Failed to find 'resources' in sources")
            return@lazy null
        }
    }
}

sealed class AndroidComponentReflection(val project: Project, val reflected: Reflected.Instance) {
    val name: String? by lazy {
        reflected.call("getName")?.unwrapAs<String>() ?: run {
            project.logger.error("Failed to find 'name' in Android component")
            return@lazy null
        }
    }

    val sources: AndroidSourcesReflection? by lazy {
        reflected.call("getSources")?.let { source -> AndroidSourcesReflection(project, source) } ?: run {
            project.logger.error("Failed to find 'sources' in Android component")
            return@lazy null
        }
    }

    val compileConfiguration: Configuration? by lazy {
        reflected.call("getCompileConfiguration")?.unwrapAs<Configuration>() ?: run {
            project.logger.error("Failed to find 'compileConfiguration' in Android component")
            return@lazy null
        }
    }

    val compileClasspath: FileCollection? by lazy {
        reflected.call("getCompileClasspath")?.unwrapAs<FileCollection>() ?: run {
            project.logger.error("Failed to find 'compileClasspath' in Android component")
            return@lazy null
        }
    }
}

class AndroidHostTestComponentReflection(project: Project, reflected: Reflected.Instance) : AndroidComponentReflection(project, reflected)
class AndroidDeviceTestComponentReflection(project: Project, reflected: Reflected.Instance) : AndroidComponentReflection(project, reflected)