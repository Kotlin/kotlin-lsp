// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.fileTemplates

import com.intellij.ide.fileTemplates.FileTemplate
import com.intellij.ide.fileTemplates.FileTemplateManager
import com.intellij.ide.fileTemplates.FileTemplateUtil
import com.intellij.ide.fileTemplates.impl.CustomFileTemplate
import com.intellij.openapi.application.readAction
import com.intellij.openapi.util.ClassLoaderUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.jps.entities.sourceRoots
import com.intellij.platform.workspace.storage.entities
import com.intellij.workspaceModel.ide.toPath
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.features.commands.LSCommandDescriptor
import com.jetbrains.ls.api.features.commands.LSCommandDescriptorProvider
import com.jetbrains.ls.imports.api.WorkspaceEntitySource
import com.jetbrains.ls.snapshot.api.impl.core.WorkspaceModelEntity
import com.jetbrains.lsp.implementation.throwLspError
import com.jetbrains.lsp.protocol.Commands.ExecuteCommand
import com.jetbrains.lsp.protocol.DocumentUri
import com.jetbrains.lsp.protocol.ErrorCodes
import com.jetbrains.lsp.protocol.LSP
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.io.IOException
import java.util.Properties

object LSInterpolateFileTemplateCommandDescriptorProvider : LSCommandDescriptorProvider {
    override val commandDescriptors: List<LSCommandDescriptor> get() = listOf(commandDescriptor)

    private val commandDescriptor = LSCommandDescriptor(
        title = "Interpolate file template",
        name = "interpolateFileTemplate",
        executor = { arguments ->
            if (arguments.size != 2) {
                throwLspError(ExecuteCommand, "Expected 2 argument, got: ${arguments.size}", Unit, ErrorCodes.InvalidParams, null)
            }
            val documentUri = LSP.json.decodeFromJsonElement<DocumentUri>(arguments[0])
            val text = LSP.json.decodeFromJsonElement<String>(arguments[1])
            val content = interpolateTemplate(documentUri, text)
            LSP.json.encodeToJsonElement(content)
        }
    )

    context(context: LSServer)
    private suspend fun interpolateTemplate(documentUri: DocumentUri, text: String): String? {
        val content: String? = contextOf<LSServer>().withAnalysisContext {
            readAction {
                val virtualFile = documentUri.findVirtualFile() ?: return@readAction null
                val psiFile = virtualFile.findPsiFile(project) ?: return@readAction null
                val psiDirectory = psiFile.containingDirectory
                    ?: return@readAction null

                val template = CustomFileTemplate(virtualFile.nameWithoutExtension, virtualFile.extension ?: "")
                template.text = text

                val propsMap = FileTemplateManager.getInstance(project).defaultContextMap

                val props = Properties()
                FileTemplateUtil.fillDefaultProperties(props, psiDirectory)
                FileTemplateUtil.putAll(propsMap, props)

                propsMap[FileTemplate.ATTRIBUTE_NAME] = virtualFile.nameWithoutExtension
                propsMap[FileTemplate.ATTRIBUTE_FILE_NAME] = psiFile.name
                propsMap[FileTemplate.ATTRIBUTE_FILE_PATH] = virtualFile.path
                propsMap[FileTemplate.ATTRIBUTE_DIR_PATH] = psiDirectory.virtualFile.path

                val nioPath = virtualFile.toNioPath()
                val module = WorkspaceModelEntity.single()
                    .workspaceModelSnapshot
                    .entityStore
                    .entities<ModuleEntity>()
                    .singleOrNull { module -> module.sourceRoots.any { nioPath.startsWith(it.url.toPath()) } }
                val projectName = (module?.entitySource as? WorkspaceEntitySource)?.virtualFileUrl?.fileName
                propsMap[FileTemplateManager.PROJECT_NAME_VARIABLE] = projectName

                //Set escaped references to dummy values to remove leading "\" (if not already explicitly set)
                val dummyRefs: Array<String?> =
                    FileTemplateUtil.calculateAttributes(template.getText(), propsMap, true, project)
                for (dummyRef in dummyRefs) {
                    propsMap[dummyRef] = ""
                }

                val handler = FileTemplateUtil.findHandler(template)
                handler.prepareProperties(propsMap, psiFile.name, template, project)
                handler.prepareProperties(propsMap)
                val mergedText = ClassLoaderUtil.computeWithClassLoader<String, IOException?>(
                    FileTemplateUtil::class.java.getClassLoader()
                ) {
                     template.getText(propsMap)
                }
                StringUtil.convertLineSeparators(mergedText)
            }
        }
        return content
    }
}
