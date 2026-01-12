// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.gradle.api.Plugin
import org.gradle.api.invocation.Gradle
import java.io.FileOutputStream
import java.io.PrintStream

internal class InfoPlugin : Plugin<Gradle> {

    override fun apply(gradle: Gradle) {
        gradle.projectsEvaluated {
            val print = System.getProperty("workspace.output.file")?.takeIf { it.isNotEmpty() }?.let {
                // dump several JSON objects (JSONL), because
                // special `buildSrc` project is applied separately
                PrintStream(FileOutputStream(it, true))
            } ?: System.out
            try {
                @OptIn(ExperimentalSerializationApi::class)
                val json = Json {
                    prettyPrint = true
                    prettyPrintIndent = " "
                    encodeDefaults = false
                }
                val jsonString = json.encodeToString(
                    gradle.rootProject.toWorkspaceData()
                )
                print.println(jsonString)
                print.flush()
            } catch (e: Throwable) {
                e.printStackTrace(System.out)
            } finally {
                print?.close()
            }
        }
    }
}
