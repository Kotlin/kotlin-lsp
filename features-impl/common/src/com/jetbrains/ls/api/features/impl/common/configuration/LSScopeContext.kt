// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.configuration

import com.intellij.openapi.diagnostic.fileLogger
import com.intellij.openapi.vfs.VirtualFileManager
import com.jetbrains.analyzer.codeServer.ScopeStorage
import com.jetbrains.ls.api.features.impl.common.utils.loadXml
import com.jetbrains.ls.snapshot.api.impl.core.toFileUrl
import java.io.IOException
import java.nio.file.Path

private val LOG = fileLogger()

internal class LSScopeContext private constructor(val storage: ScopeStorage) {
    companion object {
        fun empty(): LSScopeContext = LSScopeContext(ScopeStorage { emptyMap() })

        fun new(settingsDirectory: Path): LSScopeContext =
            LSScopeContext(ScopeStorage { loadPatterns(settingsDirectory) })

        private fun loadPatterns(settingsDirectory: Path): Map<String, String> {
            val scopeDirectory = settingsDirectory.resolve("scopes")
            val directory = VirtualFileManager.getInstance().findFileByUrl(scopeDirectory.toFileUrl().url)
            if (directory?.isDirectory != true) return emptyMap()

            val files = try {
                directory.children.orEmpty()
                    .filter { it.name.endsWith(".xml") && !it.isDirectory }
                    .sortedBy { it.name }
                    .map { scopeDirectory.resolve(it.name) }
            } catch (e: IOException) {
                LOG.warn("Cannot list scopes in $scopeDirectory", e)
                return emptyMap()
            }

            val patterns = mutableMapOf<String, String>()
            for (file in files) {
                val root = loadXml(file) ?: continue
                for (scope in root.getChildren("scope")) {
                    val name = scope.getAttributeValue("name") ?: continue
                    val pattern = scope.getAttributeValue("pattern") ?: continue
                    patterns.putIfAbsent(name, pattern)
                }
            }
            return patterns
        }
    }
}