// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.configuration

import com.intellij.openapi.diagnostic.fileLogger
import com.jetbrains.ls.api.features.impl.common.utils.loadXml
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private val LOG = fileLogger()

internal class LSScopeContext private constructor(val storage: LSScopeStorage) {
    companion object {
        fun empty(): LSScopeContext = LSScopeContext(LSScopeStorage(emptyMap()))

        fun new(settingsDirectory: Path): LSScopeContext {
            val scopeDirectory = settingsDirectory.resolve("scopes")
            if (!Files.isDirectory(scopeDirectory)) return empty()

            val files = try {
                Files.newDirectoryStream(scopeDirectory, "*.xml").use { stream ->
                    stream.sortedBy { it.fileName.toString() }
                }
            }
            catch (e: IOException) {
                LOG.warn("Cannot list scopes in $scopeDirectory", e)
                return empty()
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
            return LSScopeContext(LSScopeStorage(patterns))
        }
    }
}