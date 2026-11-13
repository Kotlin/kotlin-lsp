// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.modcommands

import com.intellij.modcommand.ModCommandAction
import com.jetbrains.ls.kotlinLsp.requests.core.ChooseActionMenuEntry

/**
 * The [action] with a machine-readable [kind]. All calls go to [action].
 *
 * When a [com.intellij.modcommand.ModChooseAction] holds this action, its chooser entry gets the [kind] as
 * [ChooseActionMenuEntry.kind]. Thus a client can find an entry without its localized name.
 */
class ModCommandActionWithKind(val kind: String, private val action: ModCommandAction) : ModCommandAction by action {
    override fun toString(): String = "$kind: $action"
}
