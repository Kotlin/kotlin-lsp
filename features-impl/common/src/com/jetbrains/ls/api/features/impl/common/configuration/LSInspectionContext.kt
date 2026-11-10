// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.configuration

import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInspection.ex.ProfileFile
import com.intellij.configurationStore.OLD_NAME_CONVERTER
import com.intellij.openapi.diagnostic.fileLogger
import com.intellij.openapi.util.JDOMExternalizerUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.profile.ProfileEx.PROFILE
import com.intellij.profile.codeInspection.BaseInspectionProfileManager
import com.intellij.profile.codeInspection.InspectionProfileLoadUtil
import com.intellij.profile.codeInspection.PROFILES_SETTINGS
import com.intellij.profile.codeInspection.PROFILE_DIR
import com.intellij.profile.codeInspection.PROJECT_DEFAULT_PROFILE_NAME
import com.jetbrains.analyzer.codeServer.AnalyzerInspectionProfileManager
import com.jetbrains.ls.api.features.impl.common.utils.loadXml
import com.jetbrains.ls.snapshot.api.impl.core.toFileUrl
import org.jdom.Element
import java.io.IOException
import java.nio.file.Path

private val LOG = fileLogger()

/**
 * [profileFile] holds the profile of [manager], or is the file to create for it. It is `null` when the
 *   project settings select a profile which the server does not read.
 */
internal class LSInspectionContext private constructor(load: () -> ProfileData) {
    private val data: ProfileData by lazy(load)
    val manager: BaseInspectionProfileManager = AnalyzerInspectionProfileManager { data.configure(it) }
    val profileFile: ProfileFile? get() = data.profileFile

    private class ProfileData(
        val profileFile: ProfileFile?,
        val configure: (InspectionProfileImpl) -> Unit = {},
    )

    companion object {
        fun empty(): LSInspectionContext = LSInspectionContext { ProfileData(null) }

        fun new(settingsDirectory: Path): LSInspectionContext =
            LSInspectionContext { loadProfileData(settingsDirectory) }

        private fun loadProfileData(settingsDirectory: Path): ProfileData {
            val inspectionDirectory = settingsDirectory.resolve(PROFILE_DIR)
            val defaultProfileFile = newProfileFile(inspectionDirectory, PROJECT_DEFAULT_PROFILE_NAME)

            val directory = VirtualFileManager.getInstance().findFileByUrl(inspectionDirectory.toFileUrl().url)
            if (directory?.isDirectory != true) {
                return ProfileData(defaultProfileFile)
            }

            val settings = loadXml(inspectionDirectory.resolve(PROFILES_SETTINGS))?.getChild("settings")
            val useProjectProfile = settings?.let { JDOMExternalizerUtil.readField(it, "USE_PROJECT_PROFILE") }?.toBooleanStrictOrNull()
            if (useProjectProfile == false) return ProfileData(null)
            val selectedName = settings?.let { JDOMExternalizerUtil.readField(it, "PROJECT_PROFILE") } ?: PROJECT_DEFAULT_PROFILE_NAME
            val [file, profile] = loadProfile(inspectionDirectory, selectedName, directory)
                                  ?: return ProfileData(newProfileFile(inspectionDirectory, selectedName))
            val profileName = InspectionProfileLoadUtil.getProfileName(file, profile)
            return ProfileData(ProfileFile(file, profileName)) { inspectionProfile ->
                settings?.let { inspectionProfile.profileManager.severityRegistrar.readExternal(it.clone()) }
                inspectionProfile.readExternal(profile.clone())
                inspectionProfile.isProjectLevel = true
            }
        }

        /** The file that the IDE creates for a new project profile named [profileName]. */
        private fun newProfileFile(inspectionDirectory: Path, profileName: String): ProfileFile =
            ProfileFile(
                inspectionDirectory.resolve(OLD_NAME_CONVERTER(profileName) + ".xml"),
                profileName,
            )

        /** The file and the `profile` element for [selectedName], or `null` if the profile cannot be loaded. */
        private fun loadProfile(inspectionDirectory: Path, selectedName: String, directory: VirtualFile): Pair<Path, Element>? {
            val files = try {
                directory.children.orEmpty()
                    .filter { it.name.endsWith(".xml") && it.name != PROFILES_SETTINGS && !it.isDirectory }
                    .sortedBy { it.name }
                    .map { inspectionDirectory.resolve(it.name) }
            } catch (e: IOException) {
                LOG.warn("Cannot list inspection profiles in $inspectionDirectory", e)
                return null
            }

            for (file in files) {
                val root = loadXml(file) ?: continue
                val profile = if (root.name == PROFILE) root else root.getChild(PROFILE) ?: continue
                if (InspectionProfileLoadUtil.getProfileName(file, profile) == selectedName) {
                    return file to profile
                }
            }
            return null
        }
    }
}
