// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.configuration

import com.intellij.codeInspection.ex.DisableInspectionToolModCommandAction
import com.intellij.configurationStore.OLD_NAME_CONVERTER
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

/**
 * @param profileFile the file that holds the profile of [manager], or the file to create for it. It is `null` when the
 *   project settings select a profile which the server does not read.
 */
internal class LSInspectionContext private constructor(
    val manager: BaseInspectionProfileManager,
    val profileFile: DisableInspectionToolModCommandAction.ProfileFile?,
) {
    companion object {
        fun empty(): LSInspectionContext = LSInspectionContext(AnalyzerInspectionProfileManager(), profileFile = null)

        fun new(settingsDirectory: Path): LSInspectionContext {
            val inspectionDirectory = settingsDirectory.resolve(PROFILE_DIR)
            val defaultProfileFile = newProfileFile(inspectionDirectory, PROJECT_DEFAULT_PROFILE_NAME)

            if (!Files.isDirectory(inspectionDirectory)) return LSInspectionContext(AnalyzerInspectionProfileManager(), defaultProfileFile)

            val settings = loadXml(inspectionDirectory.resolve(PROFILES_SETTINGS))?.getChild("settings")
            val useProjectProfile = settings?.let { JDOMExternalizerUtil.readField(it, "USE_PROJECT_PROFILE") }?.toBooleanStrictOrNull()
            if (useProjectProfile == false) return empty()
            val selectedName = settings?.let { JDOMExternalizerUtil.readField(it, "PROJECT_PROFILE") } ?: PROJECT_DEFAULT_PROFILE_NAME
            val (file, profile) = loadProfile(inspectionDirectory, selectedName)
                                  ?: return LSInspectionContext(AnalyzerInspectionProfileManager(), newProfileFile(inspectionDirectory, selectedName))
            val manager = AnalyzerInspectionProfileManager { inspectionProfile ->
                settings?.let { inspectionProfile.profileManager.severityRegistrar.readExternal(it.clone()) }
                inspectionProfile.readExternal(profile.clone())
                inspectionProfile.isProjectLevel = true
            }
            val profileName = InspectionProfileLoadUtil.getProfileName(file, profile)
            return LSInspectionContext(manager, DisableInspectionToolModCommandAction.ProfileFile(file, profileName))
        }

        /** The file that the IDE creates for a new project profile named [profileName]. */
        private fun newProfileFile(inspectionDirectory: Path, profileName: String): DisableInspectionToolModCommandAction.ProfileFile =
            DisableInspectionToolModCommandAction.ProfileFile(
                inspectionDirectory.resolve(OLD_NAME_CONVERTER(profileName) + ".xml"),
                profileName,
            )

        /** The file of the profile named [selectedName], or of the first profile, and its `profile` element. */
        private fun loadProfile(inspectionDirectory: Path, selectedName: String): Pair<Path, Element>? {
            val files = try {
                Files.newDirectoryStream(inspectionDirectory, "*.xml").use { stream ->
                    stream.filter { it.fileName.toString() != PROFILES_SETTINGS }.sortedBy { it.fileName.toString() }
                }
            }
            catch (e: IOException) {
                LOG.warn("Cannot list inspection profiles in $inspectionDirectory", e)
                return null
            }

            var firstProfile: Pair<Path, Element>? = null
            for (file in files) {
                val root = loadXml(file) ?: continue
                val profile = if (root.name == PROFILE) root else root.getChild(PROFILE) ?: continue
                if (firstProfile == null) firstProfile = file to profile
                if (InspectionProfileLoadUtil.getProfileName(file, profile) == selectedName) {
                    return file to profile
                }
            }
            return firstProfile
        }
    }
}
