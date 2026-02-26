// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.utils

import com.intellij.ide.plugins.PluginMainDescriptor
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.plugins.loadForCoreEnv
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.extensions.PluginId
import java.nio.file.Path
import java.nio.file.Paths


fun ijPluginByXml(
    xmlResourcePath: String,
    classForClasspath: Class<*>,
    useFakePluginId: Boolean = false,
): PluginMainDescriptor {
    val xmlResourcePath = xmlResourcePath.removePrefix("/")
    val pluginRoot = PathManager.getResourceRoot(classForClasspath, "/$xmlResourcePath")
        ?.let { Paths.get(it) }
        ?: error("Resource not found: $xmlResourcePath")

    fun createFakePluginId(): PluginId = PluginId.getId(xmlResourcePath)

    return requireNotNull(when {
        xmlResourcePath.startsWith(PluginManagerCore.META_INF) -> {
            // normal plugin xml
            loadForCoreEnv(
                pluginRoot = pluginRoot,
                fileName = xmlResourcePath,
                relativeDir = "",
                id = if (useFakePluginId) createFakePluginId() else null,
            )
        }

        else -> {
            // v2 plugin xml
            loadForCoreEnv(
                pluginRoot = pluginRoot,
                fileName = xmlResourcePath,
                relativeDir = "",
                id = createFakePluginId(),
            )
        }
    }) { "Failed to load plugin descriptor from $xmlResourcePath" }.also {
        it.pluginClassLoader = classForClasspath.classLoader
    }
}

private fun getPluginRoot(classForClasspath: Class<*>): Path {
    val path = "/" + classForClasspath.name.replace('.', '/') + ".class"
    val resourceRoot = PathManager.getResourceRoot(classForClasspath, path)
        ?: error("Resource not found: $path")
    return Paths.get(resourceRoot)
}

/**
 * Takes a classpath of the current file facade (current file module)
 *
 * The function is inline to have a correct classloader of the call-side
 */
@Suppress("NOTHING_TO_INLINE")
inline fun ijPluginByXml(xmlResourcePath: String, useFakePluginId: Boolean = false): PluginMainDescriptor {
    return ijPluginByXml(xmlResourcePath, object : Any() {}::class.java, useFakePluginId = useFakePluginId)
}