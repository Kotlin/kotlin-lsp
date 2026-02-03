// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.jps

import com.intellij.util.xmlb.XmlSerializer
import org.jdom.Element
import org.jetbrains.jps.model.JpsElement
import org.jetbrains.jps.model.JpsProject
import org.jetbrains.jps.model.ex.JpsElementBase
import org.jetbrains.jps.model.ex.JpsElementChildRoleBase
import org.jetbrains.jps.model.module.JpsModule
import org.jetbrains.jps.model.serialization.JpsProjectExtensionSerializer
import org.jetbrains.jps.model.serialization.facet.JpsFacetConfigurationSerializer
import org.jetbrains.kotlin.cli.common.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.K2JSCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.K2MetadataCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.setApiVersionToLanguageVersionIfNeeded
import org.jetbrains.kotlin.config.CompilerSettings
import org.jetbrains.kotlin.config.JpsPluginSettings
import org.jetbrains.kotlin.config.KotlinCommonJpsModelSerializerExtension
import org.jetbrains.kotlin.config.KotlinFacetSettings
import org.jetbrains.kotlin.config.SettingConstants
import org.jetbrains.kotlin.config.deserializeFacetSettings

// A copy-paste from the obsolete and incompatible
// kotlin-jps-plugin-classpath-2.1.21.jar library
internal class KotlinModelSerializerService : KotlinCommonJpsModelSerializerExtension() {
    override fun getProjectExtensionSerializers() = listOf(
        KotlinCommonCompilerArgumentsSerializer(),
        Kotlin2JvmCompilerArgumentsSerializer(),
        Kotlin2JsCompilerArgumentsSerializer(),
        KotlinCompilerSettingsSerializer(),
        KotlinJpsPluginSettingsSerializer()
    )

    override fun getFacetConfigurationSerializers() = listOf(JpsKotlinFacetConfigurationSerializer)
}

internal object JpsKotlinFacetConfigurationSerializer : JpsFacetConfigurationSerializer<JpsKotlinFacetModuleExtension>(
    JpsKotlinFacetModuleExtension.KIND,
    JpsKotlinFacetModuleExtension.FACET_TYPE_ID,
    JpsKotlinFacetModuleExtension.FACET_NAME
) {
    override fun loadExtension(
        facetConfigurationElement: Element,
        name: String,
        parent: JpsElement?,
        module: JpsModule
    ): JpsKotlinFacetModuleExtension {
        return JpsKotlinFacetModuleExtension(deserializeFacetSettings(facetConfigurationElement))
    }
}

internal abstract class BaseJpsCompilerSettingsSerializer<in T : Any>(
    componentName: String,
    private val settingsFactory: () -> T
) : JpsProjectExtensionSerializer(SettingConstants.KOTLIN_COMPILER_SETTINGS_FILE, componentName) {
    protected abstract fun onLoad(project: JpsProject, settings: T)

    override fun loadExtension(project: JpsProject, componentTag: Element) {
        val settings = settingsFactory().apply {
            if (this is CommonCompilerArguments) {
                freeArgs = ArrayList()
            }
        }
        XmlSerializer.deserializeInto(settings, componentTag)
        onLoad(project, settings)
    }
}

internal class KotlinCompilerSettingsSerializer : BaseJpsCompilerSettingsSerializer<CompilerSettings>(
    SettingConstants.KOTLIN_COMPILER_SETTINGS_SECTION, ::CompilerSettings
) {
    override fun onLoad(project: JpsProject, settings: CompilerSettings) {
        project.kotlinCompilerSettings = settings
    }
}

internal class KotlinJpsPluginSettingsSerializer : BaseJpsCompilerSettingsSerializer<JpsPluginSettings>(
    SettingConstants.KOTLIN_JPS_PLUGIN_SETTINGS_SECTION, ::JpsPluginSettings
) {
    override fun onLoad(project: JpsProject, settings: JpsPluginSettings) {
        project.kotlinJpsPluginSettings = settings
    }
}

internal class KotlinCommonCompilerArgumentsSerializer : BaseJpsCompilerSettingsSerializer<CommonCompilerArguments.DummyImpl>(
    SettingConstants.KOTLIN_COMMON_COMPILER_ARGUMENTS_SECTION, CommonCompilerArguments::DummyImpl
) {
    override fun onLoad(project: JpsProject, settings: CommonCompilerArguments.DummyImpl) {
        settings.setApiVersionToLanguageVersionIfNeeded()
        project.kotlinCommonCompilerArguments = settings
    }
}

internal class Kotlin2JsCompilerArgumentsSerializer : BaseJpsCompilerSettingsSerializer<K2JSCompilerArguments>(
    SettingConstants.KOTLIN_TO_JS_COMPILER_ARGUMENTS_SECTION, ::K2JSCompilerArguments
) {
    override fun onLoad(project: JpsProject, settings: K2JSCompilerArguments) {
        project.k2JsCompilerArguments = settings
    }
}

internal class Kotlin2JvmCompilerArgumentsSerializer : BaseJpsCompilerSettingsSerializer<K2JVMCompilerArguments>(
    SettingConstants.KOTLIN_TO_JVM_COMPILER_ARGUMENTS_SECTION, ::K2JVMCompilerArguments
) {
    override fun onLoad(project: JpsProject, settings: K2JVMCompilerArguments) {
        project.k2JvmCompilerArguments = settings
    }
}

internal class JpsKotlinFacetModuleExtension(settings: KotlinFacetSettings) : JpsElementBase<JpsKotlinFacetModuleExtension>() {
    var settings = settings
        private set

    companion object {
        val KIND: JpsElementChildRoleBase<JpsKotlinFacetModuleExtension> = JpsElementChildRoleBase.create("kotlin facet extension")

        // These must be changed in sync with KotlinFacetType.TYPE_ID and KotlinFacetType.NAME
        const val FACET_TYPE_ID = "kotlin-language"
        const val FACET_NAME = "Kotlin"
    }
}

internal var JpsProject.kotlinCompilerSettings
    get() = kotlinCompilerSettingsContainer.compilerSettings
    set(value) {
        getOrCreateSettings().compilerSettings = value
    }

internal var JpsProject.kotlinJpsPluginSettings
    get() = kotlinCompilerSettingsContainer.jpsPluginSettings
    set(value) {
        getOrCreateSettings().jpsPluginSettings = value
    }

internal var JpsProject.kotlinCommonCompilerArguments
    get() = kotlinCompilerSettingsContainer.commonCompilerArguments
    set(value) {
        getOrCreateSettings().commonCompilerArguments = value
    }

internal var JpsProject.k2MetadataCompilerArguments
    get() = kotlinCompilerSettingsContainer.k2MetadataCompilerArguments
    set(value) {
        getOrCreateSettings().k2MetadataCompilerArguments = value
    }

internal var JpsProject.k2JsCompilerArguments
    get() = kotlinCompilerSettingsContainer.k2JsCompilerArguments
    set(value) {
        getOrCreateSettings().k2JsCompilerArguments = value
    }

internal var JpsProject.k2JvmCompilerArguments
    get() = kotlinCompilerSettingsContainer.k2JvmCompilerArguments
    set(value) {
        getOrCreateSettings().k2JvmCompilerArguments = value
    }

internal val JpsProject.kotlinCompilerSettingsContainer
    get() = container.getChild(JpsKotlinCompilerSettings.ROLE) ?: JpsKotlinCompilerSettings()

private fun JpsProject.getOrCreateSettings(): JpsKotlinCompilerSettings {
    var settings = container.getChild(JpsKotlinCompilerSettings.ROLE)
    if (settings == null) {
        settings = JpsKotlinCompilerSettings()
        container.setChild(JpsKotlinCompilerSettings.ROLE, settings)
    }
    return settings
}

internal class JpsKotlinCompilerSettings : JpsElementBase<JpsKotlinCompilerSettings>() {
    internal var commonCompilerArguments: CommonCompilerArguments = CommonCompilerArguments.DummyImpl()
    internal var k2MetadataCompilerArguments = K2MetadataCompilerArguments()
    internal var k2JvmCompilerArguments = K2JVMCompilerArguments()
    internal var k2JsCompilerArguments = K2JSCompilerArguments()
    internal var compilerSettings = CompilerSettings()
    internal var jpsPluginSettings = JpsPluginSettings()

    @Suppress("UNCHECKED_CAST")
    internal operator fun <T : CommonCompilerArguments> get(compilerArgumentsClass: Class<T>): T = when (compilerArgumentsClass) {
        K2MetadataCompilerArguments::class.java -> k2MetadataCompilerArguments as T
        K2JVMCompilerArguments::class.java -> k2JvmCompilerArguments as T
        K2JSCompilerArguments::class.java -> k2JsCompilerArguments as T
        else -> commonCompilerArguments as T
    }

    companion object {
        val ROLE: JpsElementChildRoleBase<JpsKotlinCompilerSettings> = JpsElementChildRoleBase.create("Kotlin Compiler Settings")
    }
}