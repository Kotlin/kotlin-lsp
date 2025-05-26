// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.core

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.Project
import com.intellij.psi.search.EverythingGlobalScope
import com.intellij.psi.search.PredefinedSearchScopeProvider
import com.intellij.psi.search.SearchScope

internal class PredefinedSearchScopeProviderLSImpl : PredefinedSearchScopeProvider() {
    override fun getPredefinedScopes(
        project: Project,
        dataContext: DataContext?,
        suggestSearchInLibs: Boolean,
        prevSearchFiles: Boolean,
        currentSelection: Boolean,
        usageView: Boolean,
        showEmptyScopes: Boolean,
    ): List<SearchScope> {
        return listOf(
            EverythingGlobalScope(project),
        )
    }
}