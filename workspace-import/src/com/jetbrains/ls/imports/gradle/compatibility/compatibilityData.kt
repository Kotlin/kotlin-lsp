// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.compatibility

internal class VersionMapping(
    val java: String,
    val gradle: String
)

internal val DEFAULT_DATA = listOf(
    VersionMapping(java = "6-8", gradle = "INF-5.0"),
    VersionMapping(java = "8-9", gradle = "INF-9.0.0"),
    VersionMapping(java = "9-10", gradle = "4.3-9.0.0"),
    VersionMapping(java = "10-11", gradle = "4.7-9.0.0"),
    VersionMapping(java = "11-12", gradle = "5.0-9.0.0"),
    VersionMapping(java = "12-13", gradle = "5.4-9.0.0"),
    VersionMapping(java = "13-14", gradle = "6.0-9.0.0"),
    VersionMapping(java = "14-15", gradle = "6.3-9.0.0"),
    VersionMapping(java = "15-16", gradle = "6.7-9.0.0"),
    VersionMapping(java = "16-17", gradle = "7.0-9.0.0"),
    VersionMapping(java = "17-18", gradle = "7.2-INF"),
    VersionMapping(java = "18-19", gradle = "7.5-INF"),
    VersionMapping(java = "19-20", gradle = "7.6-INF"),
    VersionMapping(java = "20-21", gradle = "8.3-INF"),
    VersionMapping(java = "21-22", gradle = "8.5-INF"),
    VersionMapping(java = "22-23", gradle = "8.8-INF"),
    VersionMapping(java = "23-24", gradle = "8.10-INF"),
    VersionMapping(java = "24-25", gradle = "8.14-INF"),
    VersionMapping(java = "25-26", gradle = "9.1.0-INF")
)
