// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.configuration

import com.intellij.openapi.diagnostic.fileLogger
import com.intellij.openapi.util.JDOMExternalizerUtil
import com.intellij.profile.ProfileEx.PROFILE
import com.intellij.profile.codeInspection.BaseInspectionProfileManager
import com.intellij.profile.codeInspection.InspectionProfileLoadUtil
import com.intellij.profile.codeInspection.PROFILES_SETTINGS
import com.intellij.profile.codeInspection.PROFILE_DIR
import com.intellij.profile.codeInspection.PROJECT_DEFAULT_PROFILE_NAME
import com.jetbrains.analyzer.codeServer.AnalyzerInspectionProfileManager
import com.jetbrains.ls.api.features.impl.common.utils.loadXml
import org.jdom.Element
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private val LOG = fileLogger()

internal class LSInspectionContext private constructor(
    val manager: BaseInspectionProfileManager,
) {
    companion object {
        fun empty(): LSInspectionContext = LSInspectionContext(AnalyzerInspectionProfileManager())

        fun new(settingsDirectory: Path): LSInspectionContext {
            val inspectionDirectory = settingsDirectory.resolve(PROFILE_DIR)

            if (!Files.isDirectory(inspectionDirectory)) return empty()

            val settings = loadXml(inspectionDirectory.resolve(PROFILES_SETTINGS))?.getChild("settings")
            val useProjectProfile = settings?.let { JDOMExternalizerUtil.readField(it, "USE_PROJECT_PROFILE") }?.toBooleanStrictOrNull()
            if (useProjectProfile == false) return empty()
            val selectedName = settings?.let { JDOMExternalizerUtil.readField(it, "PROJECT_PROFILE") } ?: PROJECT_DEFAULT_PROFILE_NAME
            val profile = loadProfile(inspectionDirectory, selectedName) ?: return empty()
            val manager = AnalyzerInspectionProfileManager { inspectionProfile ->
                settings?.let { inspectionProfile.profileManager.severityRegistrar.readExternal(it.clone()) }
                inspectionProfile.readExternal(profile.clone())
                inspectionProfile.isProjectLevel = true
            }
            return LSInspectionContext(manager)
        }

        private fun loadProfile(inspectionDirectory: Path, selectedName: String): Element? {
            val files = try {
                Files.newDirectoryStream(inspectionDirectory, "*.xml").use { stream ->
                    stream.filter { it.fileName.toString() != PROFILES_SETTINGS }.sortedBy { it.fileName.toString() }
                }
            }
            catch (e: IOException) {
                LOG.warn("Cannot list inspection profiles in $inspectionDirectory", e)
                return null
            }

            var firstProfile: Element? = null
            for (file in files) {
                val root = loadXml(file) ?: continue
                val profile = if (root.name == PROFILE) root else root.getChild(PROFILE) ?: continue
                if (firstProfile == null) firstProfile = profile
                if (InspectionProfileLoadUtil.getProfileName(file, profile) == selectedName) {
                    return profile
                }
            }
            return firstProfile
        }
    }
}
