// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.processors

import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiCompiledElement
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiInvalidElementAccessException
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.impl.light.LightElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.refactoring.RefactoringBundle
import com.intellij.refactoring.copy.CopyFilesOrDirectoriesHandler
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.refactoring.listeners.RefactoringEventData
import com.intellij.refactoring.listeners.impl.RefactoringTransaction
import com.intellij.refactoring.rename.PsiElementRenameHandler
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.refactoring.rename.RenameUtil
import com.intellij.refactoring.rename.UnresolvableCollisionUsageInfo
import com.intellij.refactoring.rename.naming.AutomaticRenamer
import com.intellij.refactoring.rename.naming.AutomaticRenamerFactory
import com.intellij.refactoring.util.MoveRenameUsageInfo
import com.intellij.refactoring.util.RelatedUsageInfo
import com.intellij.usageView.UsageInfo
import com.intellij.usageView.UsageViewUtil
import com.intellij.util.containers.MultiMap
import com.jetbrains.lsp.implementation.throwLspError
import com.jetbrains.lsp.protocol.ErrorCodes
import com.jetbrains.lsp.protocol.RenameRequestType

/**
 * @see com.intellij.refactoring.rename.RenameProcessor
 */
class Renamer internal constructor(
    private val project: Project,
    target: PsiElement,
    private val newName: String,
    private val searchInComments: Boolean,
    private val searchTextOccurrences: Boolean,
) : RefactoringProcessor {
    private val primaryElement: PsiElement = RenamePsiElementProcessor.forElement(target).substituteElementToRename(target, null) ?: target
    private val allRenames = linkedMapOf<PsiElement, String>()
    private val renamers = mutableListOf<AutomaticRenamer>()
    private val skippedUsages = mutableListOf<UnresolvableCollisionUsageInfo>()
    private val refactoringScope: SearchScope = GlobalSearchScope.projectScope(project)
    private val file: PsiFile
        get() = primaryElement.containingFile ?: throw IllegalStateException("Primary element must have containing file")

    init {
        RenameUtil.assertNonCompileElement(primaryElement)
        allRenames[primaryElement] = newName
        prepareRenaming(primaryElement, newName, allRenames)

    }

    private fun initUsagesAndRenamers(): Array<UsageInfo> {
        val result = mutableListOf<UsageInfo>()
        val usages = RenameUtil.findUsages(
            primaryElement, newName, refactoringScope,
            searchInComments, searchTextOccurrences, allRenames
        )
        val usagesList = listOf(*usages)
        result.addAll(usagesList)

        for (factory in AutomaticRenamerFactory.EP_NAME.extensionList) {
            if (factory.getOptionName() == null && factory.isApplicable(primaryElement)) {
                renamers.add(factory.createRenamer(primaryElement, newName, usagesList))
            }
        }

        return UsageViewUtil.removeDuplicatedUsages(result.toTypedArray<UsageInfo>())
    }

    override fun findUsages(): Array<UsageInfo>? {
        if (!primaryElement.isValid()) return null
        PsiElementRenameHandler.getRenameErrorMessage(project, null, primaryElement)?.also {
            throwLspError(RenameRequestType, it, Unit, ErrorCodes.InvalidParams)
        }

        return initUsagesAndRenamers()
    }

    override fun getFilesToSave(usages: Array<UsageInfo>): List<PsiFile> {
        return usages.mapNotNull { it.file } + allRenames.keys.mapNotNull { it.containingFile }
    }

    override fun processUsages(initialUsages: Array<UsageInfo>): Array<UsageInfo> {
        val variableUsages: MutableList<UsageInfo> = ArrayList()
        if (!renamers.isEmpty()) {
            findRenamedVariables(variableUsages)
            val renames = linkedMapOf<PsiElement, String>()
            for (renamer in renamers) {
                val variables = renamer.elements
                for (variable in variables) {
                    val newName = renamer.getNewName(variable)
                    if (newName != null) {
                        addElement(variable, newName)
                        prepareRenaming(variable, newName, renames)
                    }
                }
            }
            if (!renames.isEmpty()) {
                for (element in renames.keys) {
                    RenameUtil.assertNonCompileElement(element)
                }
                allRenames.putAll(renames)

                for (entry in renames.entries) {
                    val usages = RenameUtil.findUsages(
                        entry.key, entry.value, refactoringScope,
                        searchInComments, searchTextOccurrences, allRenames
                    )
                    variableUsages.addAll(listOf(*usages))
                }
            }
        }

        val choice = if (allRenames.size > 1) intArrayOf(-1) else null

        val iterator = allRenames.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key is PsiFile) {
                val containingDirectory = file.getContainingDirectory()
                if (CopyFilesOrDirectoriesHandler.checkFileExist(
                        containingDirectory, choice, file, entry.value,
                        RefactoringBundle.message("command.name.rename")
                    )
                ) {
                    iterator.remove()
                    continue
                }
            }
            RenameUtil.checkRename(entry.key, entry.value)
        }


        val usagesSet = linkedSetOf(*initialUsages)
        usagesSet.addAll(variableUsages)
        val conflictUsages = RenameUtil.removeConflictUsages(usagesSet)
        if (conflictUsages != null) {
            skippedUsages.addAll(conflictUsages)
        }

        return usagesSet.toTypedArray<UsageInfo>()
    }

    override fun collectConflicts(refUsages: Ref<Array<UsageInfo>>, conflicts: MultiMap<PsiElement, String>) {
        RenameUtil.addConflictDescriptions(refUsages.get(), conflicts)
        RenamePsiElementProcessor.forElement(primaryElement)
            .findExistingNameConflicts(primaryElement, newName, conflicts, allRenames)
    }

    private fun prepareRenaming(
        element: PsiElement,
        newName: String,
        allRenames: MutableMap<PsiElement, String>
    ) {
        val processors = RenamePsiElementProcessor.allForElement(element)
        for (processor in processors) {
            processor.prepareRenaming(element, newName, allRenames)
        }
    }

    private fun findRenamedVariables(variableUsages: MutableList<UsageInfo>?) {
        for (automaticVariableRenamer in renamers) {
            for (element in automaticVariableRenamer.elements) {
                automaticVariableRenamer.setRename(element, automaticVariableRenamer.getNewName(element))
            }
        }

        for (renamer in renamers) {
            renamer.findUsages(variableUsages, searchInComments, searchTextOccurrences, skippedUsages, allRenames)
        }
    }

    override fun createEventData(): RefactoringEventData {
        val data = RefactoringEventData()
        data.addElement(primaryElement)
        return data
    }

    private fun addElement(element: PsiElement, newName: String) {
        RenameUtil.assertNonCompileElement(element)
        allRenames[element] = newName
    }

    override fun performRefactoring(usages: Array<UsageInfo>, transaction: RefactoringTransaction) {
        val postRenameCallbacks = mutableListOf<Runnable>()

        val renameEvents = MultiMap.createLinked<RefactoringElementListener, SmartPsiElementPointer<PsiElement>>()

        val usagesList = listOf(*usages)
        val classified: MultiMap<PsiElement, UsageInfo> = classifyUsages(allRenames.keys, usagesList)

        for ((element, newName) in allRenames) {
            if (!element.isValid()) {
                LOG.error(PsiInvalidElementAccessException(element))
                continue
            }

            val elementListener = transaction.getElementListener(element)
            val infos: Collection<UsageInfo> = classified.get(element)
            val renamePsiElementProcessor = RenamePsiElementProcessor.forElement(element)
            val postRenameCallback: Runnable? = renamePsiElementProcessor.getPostRenameCallback(element, newName, infos, allRenames, elementListener)

            renamePsiElementProcessor.renameElement(
                element, newName, infos.toTypedArray<UsageInfo>(),
                object : RefactoringElementListener {
                    override fun elementMoved(newElement: PsiElement) {
                        throw UnsupportedOperationException()
                    }

                    override fun elementRenamed(newElement: PsiElement) {
                        if (!newElement.isValid()) return
                        renameEvents.putValue(
                            elementListener,
                            SmartPointerManager.createPointer(newElement)
                        )
                    }
                })

            postRenameCallback?.let { postRenameCallbacks.add(it) }
        }

        afterRename(postRenameCallbacks, renameEvents)
    }

    private fun afterRename(
        postRenameCallbacks: List<Runnable>,
        renameEvents: MultiMap<RefactoringElementListener, SmartPsiElementPointer<PsiElement>>
    ) {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        for (entry in renameEvents.entrySet()) {
            for (pointer in entry.value) {
                val element = pointer.getElement()
                if (element != null) {
                    entry.key.elementRenamed(element)
                }
            }
        }

        for (runnable in postRenameCallbacks) {
            runnable.run()
        }
    }

    companion object {
        private val LOG = Logger.getInstance(Renamer::class.java)

        /**
         * Validates [context] and constructs a [Renamer] under a read action.
         *
         * Returns `null` if the target is no longer valid. Throws an LSP error if the target is a
         * [PsiCompiledElement] and therefore cannot be renamed.
         */
        suspend fun create(context: RenameContext): Renamer? = readAction {
            val target = context.target
            if (!target.isValid) return@readAction null
            val primaryElement = RenamePsiElementProcessor.forElement(target).substituteElementToRename(target, null) ?: target
            if (primaryElement is PsiCompiledElement) {
                throwLspError(RenameRequestType, "This element cannot be renamed", Unit, ErrorCodes.InvalidParams)
            }
            Renamer(target.project, target, context.newName, false, false)
        }

        private fun classifyUsages(
            elements: MutableCollection<out PsiElement>,
            usages: Collection<UsageInfo>
        ): MultiMap<PsiElement, UsageInfo> {
            val result = MultiMap<PsiElement, UsageInfo>()
            for (usage in usages) {
                LOG.assertTrue(usage is MoveRenameUsageInfo)
                if (shouldSkip(usage)) {
                    continue
                }
                val usageInfo = usage as MoveRenameUsageInfo
                if (usage is RelatedUsageInfo) {
                    val relatedElement = usage.relatedElement
                    if (relatedElement in elements) {
                        result.putValue(relatedElement, usage)
                    }
                } else {
                    val referenced = usageInfo.referencedElement
                    if (referenced in elements) {
                        result.putValue(referenced, usage)
                    } else if (referenced != null) {
                        val indirect = referenced.getNavigationElement()
                        if (indirect in elements) {
                            result.putValue(indirect, usage)
                        }
                    }
                }
            }
            return result
        }

        //filter out implicit references (e.g. from derived class to super class' default constructor)
        private fun shouldSkip(usage: UsageInfo): Boolean =
            usage.getReference() is LightElement
    }
}

/**
 * Data needed to perform the rename operation in the language server.
 *
 * @param target the element on which the rename was invoked.
 * @param newName the new name to be applied to the target element.
 */
class RenameContext(
    val target: PsiElement,
    val newName: String,
) : RefactoringContext
