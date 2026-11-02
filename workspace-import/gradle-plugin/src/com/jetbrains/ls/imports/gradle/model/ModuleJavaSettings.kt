// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")

package com.jetbrains.ls.imports.gradle.model;

import java.io.File
import java.io.Serializable

interface ModuleJavaSettings : Serializable {

    val compileOptions: Set<String>

    val toolchainVersion: Int?

    /** The home of the JDK the toolchain compiles this source set with, or `null` without a toolchain. */
    val toolchainJavaHome: File?

    /** The Java version of the JDK at [toolchainJavaHome]; it differs from [toolchainVersion] when the task sets its own compiler. */
    val toolchainJavaVersion: Int?

    val sourceCompatibility: String?

    val targetCompatibility: String?

}
