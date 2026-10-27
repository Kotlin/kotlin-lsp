// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.references

import com.intellij.codeInsight.highlighting.ReadWriteAccessDetector
import com.jetbrains.ls.api.core.features.LSUsage
import com.jetbrains.lsp.protocol.DocumentUri
import com.jetbrains.lsp.protocol.Location
import com.jetbrains.lsp.protocol.Range
import com.jetbrains.lsp.protocol.ReferenceParams
import com.jetbrains.lsp.protocol.RequestType
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer

/**
 * A location of `textDocument/references`, in the full answer and in each partial result.
 *
 * A client that sets `initializationOptions.referenceKinds` gets [access] and [isImport] too. For other clients
 * both stay unset, and the location encodes exactly as a plain [Location]: `{uri, range}`.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ReferenceLocation(
    val uri: DocumentUri,
    val range: Range,
    /** How the reference reads or writes the target; absent when it does neither, such as a call, and for the declaration. */
    val access: ReferenceAccess? = null,
    /** The reference is in an import; omitted when false. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val isImport: Boolean = false,
)

@Serializable
enum class ReferenceAccess {
    @SerialName("read")
    READ,

    @SerialName("write")
    WRITE,

    @SerialName("readWrite")
    READ_WRITE,
}

/** `textDocument/references` answered with [ReferenceLocation]s; on the wire it is the standard request. */
val ReferenceLocationsRequestType: RequestType<ReferenceParams, List<ReferenceLocation>?, Unit> =
    RequestType(
        "textDocument/references",
        ReferenceParams.serializer(), ListSerializer(ReferenceLocation.serializer()).nullable, Unit.serializer(),
    )

internal fun LSUsage.toReferenceLocation(): ReferenceLocation =
    ReferenceLocation(
        uri = location.uri,
        range = location.range,
        access = when (access) {
            ReadWriteAccessDetector.Access.Read -> ReferenceAccess.READ
            ReadWriteAccessDetector.Access.Write -> ReferenceAccess.WRITE
            ReadWriteAccessDetector.Access.ReadWrite -> ReferenceAccess.READ_WRITE
            null -> null
        },
        isImport = isImport,
    )
