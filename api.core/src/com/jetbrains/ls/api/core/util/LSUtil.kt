// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.core.util

import com.intellij.openapi.editor.Document
import com.intellij.openapi.projectRoots.SdkType
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.platform.workspace.jps.entities.*
import com.intellij.platform.workspace.storage.EntitySource
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.entities
import com.intellij.platform.workspace.storage.url.VirtualFileUrlManager
import com.intellij.psi.PsiFile
import com.intellij.workspaceModel.ide.impl.legacyBridge.sdk.SdkBridgeImpl
import com.intellij.workspaceModel.ide.impl.legacyBridge.sdk.SdkBridgeImpl.Companion.mutableSdkMap
import com.intellij.workspaceModel.ide.impl.legacyBridge.sdk.customName
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.project
import com.jetbrains.lsp.protocol.*
import java.nio.file.Path
import kotlin.io.path.exists

val VirtualFile.uri: URI
    get() = url.intellijUriToLspUri()

/**
 * Calculates the absolute offset in the document text based on the given position (line and character offset).
 *
 * @param position The position in the document, represented by a line number (zero-based) and
 *                 character offset in that line (zero-based).
 * @return The absolute offset in the document, which represents the character index corresponding
 *         to the given position. If the position line is outside the document bounds, returns the document
 *         end offset. If the character offset is outside the line bounds, returns the line end offset.
 */
fun Document.offsetByPosition(position: Position): Int {
    val textLength = textLength
    if (position.line >= lineCount) {
        // lsp position may be outside the document, which means the end of the document
        return textLength
    }
    val lineStart = getLineStartOffset(position.line)
    val lineEnd = getLineEndOffset(position.line)
    if (position.character > lineEnd - lineStart) {
        // lsp position may be outside the line range, which means the end of the line
        return lineEnd
    }
    return lineStart + position.character
}

fun Document.positionByOffset(offset: Int): Position {
    val line = getLineNumber(offset) 
    return Position(line = line, character = offset - getLineStartOffset(line))
}

fun URI.findVirtualFile(): VirtualFile? =
    lspUriToIntellijUri()?.let { VirtualFileManager.getInstance().findFileByUrl(it) }

fun TextRange.toLspRange(document: Document): Range =
    Range(
        document.positionByOffset(startOffset),
        document.positionByOffset(endOffset),
    )

fun Range.toTextRange(document: Document): TextRange =
    TextRange(
        document.offsetByPosition(start),
        document.offsetByPosition(end),
    )

fun TextDocumentIdentifier.findVirtualFile(): VirtualFile? = uri.uri.findVirtualFile()

fun DocumentUri.findVirtualFile(): VirtualFile? = uri.findVirtualFile()

fun TextDocumentPositionParams.findVirtualFile(): VirtualFile? = textDocument.findVirtualFile()

context(_: LSAnalysisContext)
fun VirtualFile.findPsiFile(): PsiFile? {
    return findPsiFile(project)
}

fun VirtualFile.isFromLibrary(): Boolean {
    val scheme = uri.scheme
    return scheme == "jrt" || scheme == "jar" || uri.uri.contains("!")
}

fun addSdk(
    name: String,
    type: SdkType,
    roots: List<URI>,
    urlManager: VirtualFileUrlManager,
    source: EntitySource,
    storage: MutableEntityStorage
) {
    val sdkEntity = SdkEntity(
        name = name,
        type = type.name,
        roots = if (roots.isEmpty()) emptyList() else buildList {
            roots.mapTo(this) { root ->
                SdkRoot(
                    urlManager.getOrCreateFromUrl(root.lspUriToIntellijUri()!!),
                    SdkRootTypeId(OrderRootType.CLASSES.customName),
                )
            }

            val javaHome = roots.first().lspUriToIntellijUri()!!.substringAfter("://").substringBeforeLast("!/")
            val srcZip = Path.of(FileUtilRt.toSystemDependentName(javaHome), "lib", "src.zip")
            if (srcZip.exists()) {
                val prefix = "jar://${FileUtilRt.toSystemIndependentName(srcZip.toString())}!/"
                roots.mapTo(this) { root ->
                    SdkRoot(
                        urlManager.getOrCreateFromUrl("$prefix${root.uri.substringAfterLast("/")}"),
                        SdkRootTypeId(OrderRootType.SOURCES.customName),
                    )
                }
            }
        },
        additionalData = "",
        entitySource = source
    )
    val jdk = storage addEntity sdkEntity

    storage.mutableSdkMap.addMapping(jdk, SdkBridgeImpl(sdkEntity))

    storage.entities<ModuleEntity>().forEach { module ->
        storage.modifyModuleEntity(module) {
            dependencies = (
                    dependencies.filterNot { it is SdkDependency || it is InheritedSdkDependency }
                            + SdkDependency(jdk.symbolicId)
                    ).toMutableList()
        }
    }
}