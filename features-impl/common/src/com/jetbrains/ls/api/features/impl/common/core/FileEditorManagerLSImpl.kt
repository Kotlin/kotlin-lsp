package com.jetbrains.ls.api.features.impl.common.core

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.*
import javax.swing.JComponent

class FileEditorManagerLSImpl : FileEditorManager() {
    override fun canOpenFile(file: VirtualFile): Boolean = false

    override fun getComposite(file: VirtualFile): FileEditorComposite? = null

    override fun openFile(file: VirtualFile, focusEditor: Boolean): Array<FileEditor> = throw UnsupportedOperationException()

    override fun openFile(file: VirtualFile): MutableList<FileEditor> = throw UnsupportedOperationException()

    override fun closeFile(file: VirtualFile) {}

    override fun openTextEditor(descriptor: OpenFileDescriptor, focusEditor: Boolean): Editor? = null

    override fun getSelectedTextEditor(): Editor? = null

    override fun isFileOpen(file: VirtualFile): Boolean = false

    override fun getOpenFiles(): Array<VirtualFile> = emptyArray()

    override fun getOpenFilesWithRemotes(): List<VirtualFile> = Collections.emptyList()

    override fun getSelectedFiles(): Array<VirtualFile> = emptyArray()

    override fun getSelectedEditors(): Array<FileEditor> = emptyArray()

    override fun getCurrentFile(): VirtualFile? = null

    override fun getSelectedEditorFlow(): StateFlow<FileEditor?> = MutableStateFlow(null).asStateFlow()

    override fun getSelectedEditor(file: VirtualFile): FileEditor? = null

    override fun getEditors(file: VirtualFile): Array<FileEditor> = emptyArray()

    override fun getAllEditors(file: VirtualFile): Array<FileEditor> = emptyArray()

    override fun getAllEditors(): Array<FileEditor> = emptyArray()

    override fun getAllEditorList(file: VirtualFile): List<FileEditor> = emptyList()

    override fun addTopComponent(editor: FileEditor, component: JComponent) {}

    override fun removeTopComponent(editor: FileEditor, component: JComponent) {}

    override fun addBottomComponent(editor: FileEditor, component: JComponent) {}

    override fun removeBottomComponent(editor: FileEditor, component: JComponent) {}

    override fun openFileEditor(descriptor: FileEditorNavigatable, focusEditor: Boolean): List<FileEditor> = emptyList()

    override fun getProject(): Project = throw UnsupportedOperationException()

    override fun setSelectedEditor(file: VirtualFile, fileEditorProviderId: String) {}

    override fun runWhenLoaded(editor: Editor, runnable: Runnable) {
        runnable.run()
    }
}