// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle

import com.intellij.diagnostic.rethrowControlFlowException
import com.jetbrains.ls.imports.json.DependencyData
import com.jetbrains.ls.imports.json.SdkData
import org.jetbrains.annotations.ApiStatus
import java.nio.file.Path

/**
 * The SDKs of the Gradle toolchain JDKs of one import, one per JDK home.
 *
 * Homes are compared by their real path, so a symlink to a JDK shares that JDK's SDK.
 */
@ApiStatus.Internal
class GradleToolchainSdks {
    private val sdksByHome = LinkedHashMap<String, SdkData>()

    /** The toolchain SDKs in the order they were first asked for. */
    val sdks: List<SdkData>
        get() = sdksByHome.values.toList()

    /**
     * The dependency on the SDK of the toolchain JDK at [home] for language [version], or `null` when [home] is the
     * [currentSdkHome] the module already depends on.
     */
    fun dependencyFor(home: String, version: Int, currentSdkHome: String?): DependencyData.Sdk? {
        val key = realPath(home)
        if (currentSdkHome != null && key == realPath(currentSdkHome)) return null
        val sdk = sdksByHome.getOrPut(key) {
            SdkData(
                name = uniqueName("$version (toolchain)"),
                type = JAVA_SDK_TYPE,
                version = "JDK_$version",
                homePath = home,
                additionalData = "",
            )
        }
        return DependencyData.Sdk(sdk.name, sdk.type)
    }

    private fun uniqueName(base: String): String {
        val taken = sdksByHome.values.mapTo(HashSet()) { it.name }
        if (base !in taken) return base
        return generateSequence(2) { it + 1 }.map { "$base ($it)" }.first { it !in taken }
    }

    /** [home] with symlinks resolved; normalized only when it does not exist, and as is when it is not a path. */
    private fun realPath(home: String): String {
        val path = try {
            Path.of(home)
        } catch (e: Throwable) {
            rethrowControlFlowException(e)
            return home
        }
        return try {
            path.toRealPath().toString()
        } catch (e: Throwable) {
            rethrowControlFlowException(e)
            path.toAbsolutePath().normalize().toString()
        }
    }

    private companion object {
        const val JAVA_SDK_TYPE: String = "JavaSDK"
    }
}
