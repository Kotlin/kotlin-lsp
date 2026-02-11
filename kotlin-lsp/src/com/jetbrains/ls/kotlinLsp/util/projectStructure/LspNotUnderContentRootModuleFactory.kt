// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.kotlinLsp.util.projectStructure

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.projectStructure.KaNotUnderContentRootModule
import org.jetbrains.kotlin.idea.base.fir.projectStructure.KaNotUnderContentRootModuleFactory

class LspNotUnderContentRootModuleFactory : KaNotUnderContentRootModuleFactory {
    @OptIn(KaPlatformInterface::class)
    override fun create(project: Project, file: PsiFile?): KaNotUnderContentRootModule =
        KaNotUnderContentRootModuleWithProjectDeps(project = project, file = file)
}
