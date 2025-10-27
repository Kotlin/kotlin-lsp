// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.vscode

import com.jetbrains.lsp.protocol.Command
import com.jetbrains.lsp.protocol.LSP
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.absoluteValue

// see https://code.visualstudio.com/api/references/commands
object VsCodeCommands {
    fun moveCursorCommand(offset: Int, absoluteOffset: Int?): Command? {
        return if (offset != 0 || absoluteOffset != null) {
            Command(
                title = "Adjust Cursor",
                command = Names.MOVE_CURSOR,
                arguments = listOfNotNull(
                    offset.takeIf { it != 0 }?.let {
                        LSP.json.encodeToJsonElement(
                            CursorMoveCommandParameters.serializer(),
                            CursorMoveCommandParameters.fromRelativeOffset(offset)
                        )
                    },

                    // TODO: Currently we use the command for moving a caret after completion insert handler.
                    //  The challenge is we only know the final text, not how it was generated, making it difficult
                    //  to calculate proper caret movement. Ideally, we would send absolute targret offset to the client
                    //  and let them position the caret.
                    //  Since VSCode doesn't support absolute positioning,
                    //  we include both: relative offset (used by VSCode) and absolute offset (for Fleet).
                    //  Once VSCode-counterpart implements absolute positioning support, we can remove the relative offset parameter.
                    absoluteOffset?.let {
                        LSP.json.encodeToJsonElement(
                            CursorMoveCommandParameters.serializer(),
                            CursorMoveCommandParameters(CursorMoveTarget.OFFSET, it)
                        )
                    }
                )
            )
        } else {
            null
        }
    }

    object Names {
        const val MOVE_CURSOR: String = "cursorMove"
    }
}

@Serializable
data class CursorMoveCommandParameters(val to: CursorMoveTarget, val value: Int) {
    init {
        require(value >= 0) { "${::value.name} must be positive but got $value" }
    }

    companion object {
        fun fromRelativeOffset(offset: Int): CursorMoveCommandParameters {
            val direction = when {
                offset > 0 -> CursorMoveTarget.RIGHT
                else -> CursorMoveTarget.LEFT
            }
            return CursorMoveCommandParameters(direction, offset.absoluteValue)
        }
    }
}

@Serializable
enum class CursorMoveTarget {
    @SerialName("left")
    LEFT,

    @SerialName("right")
    RIGHT,

    @SerialName("offset")
    OFFSET,
}