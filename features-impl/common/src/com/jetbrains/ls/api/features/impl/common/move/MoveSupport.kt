package com.jetbrains.ls.api.features.impl.common.move

/**
 * Describes which source elements a move handler can process.
 *
 * A [FULL] handler takes priority over [PARTIAL] handlers.
 *
 * @see com.jetbrains.ls.api.features.impl.common.move.LSMoveHandlerDelegate#analyze
 */
enum class MoveSupport {
    /** The handler supports processing of all source elements. */
    FULL,

    /** The handler supports only some source elements. */
    PARTIAL,

    /** The handler can't move any of these elements */
    UNSUPPORTED,
}