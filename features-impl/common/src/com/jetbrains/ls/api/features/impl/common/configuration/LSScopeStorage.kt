// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.configuration

import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.psi.search.scope.packageSet.InvalidPackageSet
import com.intellij.psi.search.scope.packageSet.NamedScope
import com.intellij.psi.search.scope.packageSet.PackageSetFactory
import com.intellij.psi.search.scope.packageSet.ParsingException

class LSScopeStorage internal constructor(patterns: Map<String, String>) {
    val scopes: Map<String, NamedScope> by lazy {
        patterns.mapValues { (name, pattern) ->
            val packageSet = try {
                PackageSetFactory.getInstance().compile(pattern)
            }
            catch (_: ParsingException) {
                InvalidPackageSet(pattern)
            }
            NamedScope(name, AllIcons.Ide.SharedScope, packageSet)
        }
    }

    companion object {
        @Suppress("IncorrectServiceRetrieving")
        fun getInstance(): LSScopeStorage = service()
    }
}