// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features

import com.jetbrains.ls.api.features.language.LSLanguage

interface LSConfigurationEntry

interface LSLanguageSpecificConfigurationEntry : LSConfigurationEntry {
    val supportedLanguages: Set<LSLanguage>
}
