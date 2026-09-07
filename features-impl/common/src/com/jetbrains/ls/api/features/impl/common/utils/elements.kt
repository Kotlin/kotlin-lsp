// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.utils

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement

/**
 * Searches the element under the caret in the same way as [com.intellij.openapi.fileEditor.impl.text.TextEditorPsiDataRule.uiDataSnapshot] does
 * @see com.intellij.openapi.fileEditor.impl.text.TextEditorPsiDataRule.uiDataSnapshot
 */
fun findElementUnderCaret(editor: Editor, offset: Int): PsiElement? {
    val elementSearcher = TargetElementUtil.getInstance()
    val flags = elementSearcher.referenceSearchFlags and TargetElementUtil.LOOKUP_ITEM_ACCEPTED.inv()
    return elementSearcher.findTargetElement(editor, flags, offset)
}
