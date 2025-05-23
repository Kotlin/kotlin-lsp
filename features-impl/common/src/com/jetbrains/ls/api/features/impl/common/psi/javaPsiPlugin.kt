package com.jetbrains.ls.api.features.impl.common.psi

import com.intellij.pom.java.JavaRelease
import com.jetbrains.ls.api.features.utils.ijPluginByXml

internal val javaPsiPlugin = ijPluginByXml("intellij.java.psi.xml", JavaRelease::class.java)