package com.jetbrains.ls.api.features.utils

import com.intellij.ide.plugins.IdeaPluginDescriptorImpl
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.plugins.loadAndInitForCoreEnv
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.extensions.PluginId
import java.nio.file.Path
import java.nio.file.Paths


fun ijPluginByXml(
    xmlResourcePath: String,
    classForClasspath: Class<*>,
    useFakePluginId: Boolean = false,
): IdeaPluginDescriptorImpl {
    val xmlResourcePath = xmlResourcePath.removePrefix("/")
    val pluginRoot = getPluginRoot(classForClasspath)
    fun createFakePluginId(): PluginId = PluginId.getId(xmlResourcePath)

    return when {
        xmlResourcePath.startsWith(PluginManagerCore.META_INF) -> {
            // normal plugin xml
            loadAndInitForCoreEnv(
                pluginRoot,
                xmlResourcePath, relativeDir = "",
                id = if (useFakePluginId) createFakePluginId() else null
            )
        }

        else -> {
            // v2 plugin xml
            loadAndInitForCoreEnv(pluginRoot, xmlResourcePath, relativeDir = "", id = createFakePluginId())
        }
    }
        ?: error("Failed to load plugin descriptor from $xmlResourcePath")
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
inline fun ijPluginByXml(xmlResourcePath: String): IdeaPluginDescriptorImpl {
    return ijPluginByXml(xmlResourcePath, object : Any() {}::class.java)
}