// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.psi

import com.intellij.pom.java.JavaRelease
import com.jetbrains.ls.api.features.utils.ijPluginByXml

internal val javaPsiPlugin = ijPluginByXml("intellij.java.psi.xml", JavaRelease::class.java)