// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.modcommands

import com.intellij.openapi.editor.Document
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.findDocument
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.kotlinLsp.requests.core.ModCommandData
import com.jetbrains.ls.kotlinLsp.requests.core.writtenFileUrls
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * A hash of the text of a document. A command carries it from the listing to the execution, and the execution
 * refuses to run when the text changed in between.
 *
 * The hash is taken from an IntelliJ [Document], whose text always uses `\n` line separators, so the stamp is the
 * same on every OS. It works for open and closed files alike. It is sent as a JSON string, because a JSON number
 * keeps only 53 bits in a JavaScript client.
 *
 * The hash is the first 64 bits of the SHA-256 of the length and the UTF-16 chars of the text. A weak hash lets
 * a real edit pass: `StringHash.buz`, used before, ignores 128 inserted copies of one char, and mixes only
 * `ch ^ (ch >>> 8)` of a char, so `Š` (U+0160) hashes like `a`.
 */
@Serializable(with = DocumentStamp.Serializer::class)
@JvmInline
value class DocumentStamp(val value: Long) {
    companion object {
        fun of(document: Document): DocumentStamp = of(document.immutableCharSequence)

        fun of(text: CharSequence): DocumentStamp {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteBuffer.allocate(8192)
            buffer.putInt(text.length)
            for (i in 0 until text.length) {
                if (buffer.remaining() < Char.SIZE_BYTES) {
                    digest.update(buffer.flip())
                    buffer.clear()
                }
                buffer.putChar(text[i])
            }
            digest.update(buffer.flip())
            return DocumentStamp(ByteBuffer.wrap(digest.digest()).getLong())
        }
    }

    object Serializer : KSerializer<DocumentStamp> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("com.jetbrains.ls.api.features.impl.common.modcommands.DocumentStamp", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: DocumentStamp): Unit = encoder.encodeString(value.value.toString())

        override fun deserialize(decoder: Decoder): DocumentStamp {
            val text = decoder.decodeString()
            // A SerializationException is a bad request (InvalidParams); a NumberFormatException would be an internal error.
            return DocumentStamp(text.toLongOrNull() ?: throw SerializationException("Not a document stamp: '$text'"))
        }
    }
}

/** The stamps of some files, by IntelliJ file url. `null` means the file or its document was not found. */
typealias DocumentStamps = Map<String, DocumentStamp?>

/** The stamp of the document of [fileUrl] (an IntelliJ file url), or `null` when there is none. Must run in a read action. */
context(_: LSAnalysisContext)
fun stampOf(fileUrl: String): DocumentStamp? =
    VirtualFileManager.getInstance().findFileByUrl(fileUrl)?.findDocument()?.let(DocumentStamp::of)

/** The stamps of [fileUrls] (IntelliJ file urls). Must run in a read action. */
context(_: LSAnalysisContext)
fun stampsOf(fileUrls: Iterable<String>): DocumentStamps = fileUrls.associateWith { stampOf(it) }

/**
 * The stamps that a command of [this] carries from the listing to the execution: the files it writes, and
 * [contextFileUrl], the file the command was listed for (IntelliJ file urls). Must run in a read action.
 *
 * A file that has no document at the listing is not stamped. The target of a [ModCommandData.CreateFile] does
 * not exist yet, so the command does not depend on its text, and a `null` stamp would never match. A
 * [ModCommandData.LazyAction] gets no stamps, because it computes its edits only at the execution.
 */
context(_: LSAnalysisContext)
fun ModCommandData.stamps(contextFileUrl: String?): DocumentStamps {
    if (this is ModCommandData.LazyAction) return emptyMap()
    val fileUrls = (writtenFileUrls() + listOfNotNull(contextFileUrl)).distinct().toList()
    return stampsOf(fileUrls).filterValues { it != null }
}

/**
 * Whether every file still has the stamp it had when [this] was taken. A missing document on either side counts
 * as a change. Must run in a read action.
 */
context(_: LSAnalysisContext)
fun DocumentStamps.matchCurrent(): Boolean = all { (fileUrl, stamp) -> stamp != null && stamp == stampOf(fileUrl) }
