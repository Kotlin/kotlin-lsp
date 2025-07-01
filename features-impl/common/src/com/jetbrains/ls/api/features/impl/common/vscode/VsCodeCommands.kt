// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.vscode

import com.jetbrains.lsp.protocol.Command
import com.jetbrains.lsp.protocol.LSP
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.absoluteValue

// see https://code.visualstudio.com/api/references/commands
object VsCodeCommands {
    fun moveCursorCommand(offset: Int): Command {
        val parameters = CursorMoveCommandParameters.fromOffset(offset)
        return Command(
            title = "Adjust Cursor",
            command = Names.MOVE_CURSOR,
            arguments = listOf(
                LSP.json.encodeToJsonElement(CursorMoveCommandParameters.serializer(), parameters),
            )
        )
    }

    object Names {
        const val MOVE_CURSOR: String = "cursorMove"
    }
}

@Serializable
data class CursorMoveCommandParameters(val to: CursorMoveDirection, val value: Int) {
    init {
        require(value >= 0) { "${::value.name} must be positive but got $value" }
    }

    fun toOffset(): Int = when (to) {
        CursorMoveDirection.RIGHT -> value
        else -> -value
    }

    companion object {
        fun fromOffset(offset: Int): CursorMoveCommandParameters {
            val direction = when {
                offset > 0 -> CursorMoveDirection.RIGHT
                else -> CursorMoveDirection.LEFT
            }
            return CursorMoveCommandParameters(direction, offset.absoluteValue)
        }
    }
}

@Serializable
enum class CursorMoveDirection {
    @SerialName("left")
    LEFT,

    @SerialName("right")
    RIGHT
}