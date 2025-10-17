// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.core.util

import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.util.io.FileUtilRt
import com.jetbrains.lsp.protocol.URI
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.absolutePathString
import kotlin.io.path.extension
import java.net.URI as JavaUri

/**
 * Converts a URI between the LSP format and the IntelliJ format.
 *
 * Differences:
 * - LSP URIs follow the URI specification, where all path components are encoded. They use Unix file separators and may be case-sensitive.
 * - IntelliJ URIs are not encoded. They use Unix file separators. All JAR/JRT files should use the `jar` or `jrt` protocol and include `!/` in the path. They are case-sensitive, even on Windows.
 *
 * It has the following contract: a certain composition of functions have a fixed point property:
 * ```kotlin
 * val lsp1: String = ...
 * val intellij1 = lspUriToIntellijUri(lsp1)
 *
 * val lsp2: String = intellijUriToLspUri(intellij1)
 * val intellij2 = lspUriToIntellijUri(lsp2)
 *
 * val lsp3 = intellijUriToLspUri(intellij2)
 *
 * assert(lsp2 == lsp3)
 * assert(intellij1 == intellij2)
 * ```
 */
object UriConverter {
    fun lspUriToIntellijUri(uriString: String): String? {
        val uri = JavaUri(uriString).withoutAuthority()
        return when (val scheme = uri.scheme) {
            "file" -> {
                val path = Paths.get(uri)
                if (path.extension == "jar") {
                    "jar://${path.toSystemIndependentString()}!/"
                } else {
                    "file://${path.toSystemIndependentString()}"
                }
            }

            "jar", "jrt" -> {
                val parts = uri.rawSchemeSpecificPart.split("!", limit = 2)
                val nestedUri = JavaUri(parts[0])
                val path = Paths.get(nestedUri.path.removePrefix("/")).toSystemIndependentString()
                val inJarPath = if (parts.size > 1) parts[1].removePrefix("/") else ""
                "${scheme}://$path!/$inJarPath"
            }

            else -> null
        }
    }

    fun localAbsolutePathToIntellijUri(path: String): String {
        val isJar = path.endsWith(".jar")
        val path = FileUtilRt.toSystemIndependentName(path)
        return "${if (isJar) "jar" else "file"}://$path${if (isJar) "!/" else ""}"
    }

    fun localAbsolutePathToLspUri(path: String): String {
        return intellijUriToLspUri(localAbsolutePathToIntellijUri(path))
    }

    fun intellijUriToLspUri(uri: String): String {
        val scheme = uri.substringBefore("://")
        val defaultScheme = if (scheme == "file") "file" else null
        val jarSeparatorIndex = uri.indexOf("!/")
        if (jarSeparatorIndex < 0) {
            return uri.toUriString(defaultScheme)
        }
        val jarPath = uri.take(jarSeparatorIndex).toUriString(defaultScheme)
        val inJarPath = uri.substring(jarSeparatorIndex + 2).toUriString(defaultScheme = null).removePrefix("/")
        return "$jarPath!/$inJarPath"
    }

    private fun JavaUri.withoutAuthority(): JavaUri {
        return JavaUri(scheme, null, path, query, fragment)
    }

    private fun Path.toSystemIndependentString(): String {
        var path = this.toString()
        if (SystemInfoRt.isWindows && path.length > 2 && path[1] == ':') {
            // make the drive letter lowercase, intellij cares about that
            path = path[0].uppercase() + path.substring(1)
        }
        path = FileUtilRt.toSystemIndependentName(path)
        if (!SystemInfoRt.isWindows) {
            path = path.withLeadingSlash()
        }
        return path
    }

    private fun String.toUriString(defaultScheme: String?): String {
        val protocolIndex = indexOf("://")
        val scheme = if (protocolIndex < 0) defaultScheme else substring(0, protocolIndex)
        var path = if (protocolIndex < 0) this else substring(protocolIndex + 3)
        path = path.withLeadingSlash()

        val javaUri = JavaUri(scheme, null, path, null)
        if (scheme == null) {
            return javaUri.rawPath.asPathUri()
        } else {
            return javaUri.scheme + "://" + javaUri.rawPath.asPathUri()
        }
    }

    private fun String.withLeadingSlash(): String {
        if (startsWith("/")) return this
        return "/$this"
    }

    private fun String.asPathUri(): String {
        var path = this
        path = path.removePrefix("/")
        if (SystemInfoRt.isWindows && path.length > 2 && path[1] == ':') {
            // encode `:` with `%3A` as vscode does this to have consistent uris
           path = path[0].uppercase() + "%3A" + path.substring(2)
        }
        path = "/$path"
        return path
    }
}

fun URI.lspUriToIntellijUri(): String? = UriConverter.lspUriToIntellijUri(uri)

fun String.intellijUriToLspUri(): URI = URI(UriConverter.intellijUriToLspUri(this))

fun Path.toLspUri(): URI {
    val path = absolutePathString()
    val uriString = UriConverter.localAbsolutePathToLspUri(path)
    return URI(uriString)
}