// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.utils

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.impl.ImaginaryEditor
import com.jetbrains.ls.api.core.LSAnalysisContext

context(LSAnalysisContext)
fun createEditorWithCaret(document: Document, caretOffset: Int): ImaginaryEditor {
    val editor = ImaginaryEditor(project, document)
    editor.caretModel.primaryCaret.moveToOffset(caretOffset)
    return editor
}