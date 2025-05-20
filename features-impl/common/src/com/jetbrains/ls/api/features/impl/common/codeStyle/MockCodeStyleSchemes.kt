package com.jetbrains.ls.api.features.impl.common.codeStyle

import com.intellij.psi.codeStyle.CodeStyleScheme
import com.intellij.psi.codeStyle.CodeStyleSchemes
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CodeStyleSettingsManager
import org.jetbrains.annotations.Nls

class MockCodeStyleSchemes : CodeStyleSchemes() {
    private val scheme: CodeStyleScheme = object : CodeStyleScheme {
        private val settings = CodeStyleSettingsManager.getInstance().createSettings()
        override fun getName(): @Nls String = CodeStyleScheme.DEFAULT_SCHEME_NAME
        override fun isDefault(): Boolean = true
        override fun getCodeStyleSettings(): CodeStyleSettings = settings
    }

    override fun getCurrentScheme(): CodeStyleScheme = scheme
    override fun setCurrentScheme(scheme: CodeStyleScheme?) {}
    override fun createNewScheme(preferredName: String?, parentScheme: CodeStyleScheme?): CodeStyleScheme? = null
    override fun deleteScheme(scheme: CodeStyleScheme) {}
    override fun findSchemeByName(name: String): CodeStyleScheme? = scheme
    override fun getDefaultScheme(): CodeStyleScheme = scheme
    override fun addScheme(currentScheme: CodeStyleScheme) {}
}
