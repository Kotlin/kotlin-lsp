// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.processors

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.Ref.create
import com.intellij.openapi.util.text.StringUtil.removeHtmlTags
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.RefactoringHelper
import com.intellij.refactoring.listeners.RefactoringEventData
import com.intellij.refactoring.listeners.RefactoringEventData.CONFLICTS_KEY
import com.intellij.refactoring.listeners.RefactoringListenerManager
import com.intellij.refactoring.listeners.impl.RefactoringListenerManagerImpl
import com.intellij.refactoring.listeners.impl.RefactoringTransaction
import com.intellij.refactoring.suggested.SuggestedRefactoringProvider
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap
import com.jetbrains.analyzer.api.FileUrl
import com.jetbrains.analyzer.api.fileUrl
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.project

/**
 * A re-implementation of some [com.intellij.refactoring.BaseRefactoringProcessor] methods without UI dependencies*.
 *
 * It doesn't show any dialogs and always chooses some preferred path in situations
 * where [BaseRefactoringProcessor] would show a dialog and ask the user to choose something.
 *
 * @see com.intellij.refactoring.BaseRefactoringProcessor
 * @see <a href="https://youtrack.jetbrains.com/issue/LSP-343/We-have-a-reimplementation-of-BaseRefactoringProcessor">LSP-343</a>
 */
interface RefactoringProcessor {
    /**
     * Collects the conflicts for the given usages and adds them to the conflict map.
     */
    fun collectConflicts(refUsages: Ref<Array<UsageInfo>>, conflicts: MultiMap<PsiElement, String>)

    /**
     * @return usages potentially affected by refactoring
     * @see com.intellij.refactoring.BaseRefactoringProcessor.findUsages
     */
    fun findUsages(): Array<UsageInfo>?

    /**
     * Performs additional check on usages from [findUsages], possibly adding or deleting some of them
     * @return final set of usages that should be used to perform the refactoring
     * @see BaseRefactoringProcessor.preprocessUsages
     */
    fun processUsages(initialUsages: Array<UsageInfo>): Array<UsageInfo>

    /**
     * Finds all files whose text should be saved before refactoring
     */
    fun getFilesToSave(usages: Array<UsageInfo>): List<PsiFile>

    /**
     * Performs the refactoring based on the given [usages]
     * @param usages that are related to the refactoring
     * @param transaction provides listeners related to the psi element updates
     */
    fun performRefactoring(usages: Array<UsageInfo>, transaction: RefactoringTransaction)

    /**
     * Creates the holder with [PsiElement] that will be used in refactoring.
     * @see com.intellij.refactoring.BaseRefactoringProcessor.getBeforeData
     * @see com.intellij.refactoring.BaseRefactoringProcessor.doRefactoring
     */
    fun createEventData(): RefactoringEventData
}

/**
 * Executes logic of [BaseRefactoringProcessor] in simplified way without showing UI.
 */
context(_: LSAnalysisContext)
internal fun execute(processor: RefactoringProcessor) : Map<FileUrl, Pair<PsiFile, String>> {
    if (!PsiDocumentManager.getInstance(project).commitAllDocumentsUnderProgress()) return emptyMap()
    DumbService.getInstance(project).completeJustSubmittedTasks()

    val usages = findUsages(processor) ?: return emptyMap()

    val originals = startRefactoring(processor, usages, project)
    return originals
}

context(_: LSAnalysisContext)
private fun findUsages(processor: RefactoringProcessor): Array<UsageInfo>? {
    val initialUsages = runReadActionInBgt(project, processor::findUsages) ?: return null

    val conflicts = MultiMap<PsiElement, String>()
    val refUsages = create(initialUsages)
    processor.collectConflicts(refUsages, conflicts)
    if (!conflicts.isEmpty()) {
        val conflictData = RefactoringEventData()
        conflictData.putUserData(CONFLICTS_KEY, conflicts.values())
        throw IllegalStateException(
            conflicts.values().filterNotNull().sortedBy { it }.joinToString(separator = "\n") { removeHtmlTags(it, true) }
        )
    }

    return processor.processUsages(initialUsages)
}

context(_: LSAnalysisContext)
private fun startRefactoring(
    processor: RefactoringProcessor,
    usages: Array<UsageInfo>,
    project: Project
): Map<FileUrl, Pair<PsiFile, String>> {
    val originals = saveFileTexts(processor, usages)
    doRefactoring(processor, usages)
    SuggestedRefactoringProvider.getInstance(project).reset()
    return originals
}

context(_: LSAnalysisContext)
private fun doRefactoring(processor: RefactoringProcessor, usages: Array<UsageInfo>) {
    val writableUsageInfos = removeNonWritableUsages(usages)
    val data = processor.createEventData()
    data.addUsages(writableUsageInfos.toList())

    PsiDocumentManager.getInstance(project).commitAllDocuments()
    val listenerManager =
        RefactoringListenerManager.getInstance(project) as RefactoringListenerManagerImpl
    val transaction = listenerManager.startTransaction()
    val preparedData = linkedMapOf<RefactoringHelper<*>?, Any?>()

    val elements = data.getUserData(RefactoringEventData.PSI_ELEMENT_ARRAY_KEY)
    val primaryElement = data.getUserData(RefactoringEventData.PSI_ELEMENT_KEY)
    val allElements = when (elements) {
        null -> arrayOf(primaryElement)
        else -> elements + primaryElement
    }

    for (helper in RefactoringHelper.EP_NAME.extensionList) {
        val operation: Any? = helper.prepareOperation(writableUsageInfos, allElements.filterNotNull())
        preparedData[helper] = operation
    }

    CommandProcessor.getInstance().executeCommand(project, Runnable {
        WriteAction.run<Throwable> {
            processor.performRefactoring(writableUsageInfos, transaction)

            DumbService.getInstance(project).completeJustSubmittedTasks()

            for ((key, operation) in preparedData) {
                @Suppress("UNCHECKED_CAST")
                val refactoringHelper = key as RefactoringHelper<Any>
                refactoringHelper.performOperation(project, operation)
            }
            transaction.commit()
        }
    }, null, null)

}

private fun removeNonWritableUsages(usages: Array<UsageInfo>): Array<UsageInfo> {
    return usages.filter { it.element != null && it.isWritable }.toTypedArray()
}

private fun saveFileTexts(processor: RefactoringProcessor, usages: Array<UsageInfo>): Map<FileUrl, Pair<PsiFile, String>> {
    val fileList = processor.getFilesToSave(usages)
    return fileList.mapNotNull {  file  ->
        val virtualFile = file.virtualFile ?: return@mapNotNull null
        file to virtualFile.fileUrl
    }.distinctBy { it.second }.associate { it.second to (it.first to it.first.text) }
}
