// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.callHierarchy

import com.intellij.ide.hierarchy.ReferenceAwareNodeDescriptor
import com.jetbrains.ls.api.core.util.uri
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.LSLanguageSpecificConfigurationEntry
import com.jetbrains.lsp.protocol.CallHierarchyItem

interface LSCallHierarchyRenderer: LSLanguageSpecificConfigurationEntry {
    /**
     * Creates a [CallHierarchyItem] specific for the language.
     */
    fun createCallHierarchyItem(descriptor: ReferenceAwareNodeDescriptor): CallHierarchyItem?

    companion object {
        context(configuration: LSConfiguration)
        fun createCallHierarchyItemFor(descriptor: ReferenceAwareNodeDescriptor): CallHierarchyItem? {
            val uri = descriptor.enclosingElement?.containingFile?.virtualFile?.uri ?: return null
            return configuration.entriesFor<LSCallHierarchyRenderer>(uri).map { it.createCallHierarchyItem(descriptor) }.singleOrNull()
        }
    }
}