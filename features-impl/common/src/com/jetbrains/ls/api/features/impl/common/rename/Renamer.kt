// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.rename

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.ex.ApplicationManagerEx
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.psi.*
import com.intellij.psi.impl.light.LightElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.refactoring.RefactoringBundle
import com.intellij.refactoring.RefactoringHelper
import com.intellij.refactoring.copy.CopyFilesOrDirectoriesHandler
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.refactoring.listeners.RefactoringEventData
import com.intellij.refactoring.listeners.RefactoringListenerManager
import com.intellij.refactoring.listeners.impl.RefactoringListenerManagerImpl
import com.intellij.refactoring.listeners.impl.RefactoringTransaction
import com.intellij.refactoring.rename.PsiElementRenameHandler
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.refactoring.rename.RenameUtil
import com.intellij.refactoring.rename.UnresolvableCollisionUsageInfo
import com.intellij.refactoring.rename.naming.AutomaticRenamer
import com.intellij.refactoring.rename.naming.AutomaticRenamerFactory
import com.intellij.refactoring.suggested.SuggestedRefactoringProvider
import com.intellij.refactoring.util.MoveRenameUsageInfo
import com.intellij.refactoring.util.NonCodeUsageInfo
import com.intellij.refactoring.util.RelatedUsageInfo
import com.intellij.usageView.UsageInfo
import com.intellij.usageView.UsageViewUtil
import com.intellij.util.containers.MultiMap
import org.jetbrains.annotations.ApiStatus

internal class Renamer(
    private val project: Project,
    private val primaryElement: PsiElement,
    private val newName: String,
    private val searchInComments: Boolean,
    private val searchTextOccurrences: Boolean
) {
    private val allRenames = linkedMapOf<PsiElement, String>()
    private var nonCodeUsages = emptyArray<NonCodeUsageInfo>()
    private val renamerFactories = ArrayList<AutomaticRenamerFactory>()
    private val renamers = mutableListOf<AutomaticRenamer>()
    private val skippedUsages = mutableListOf<UnresolvableCollisionUsageInfo>()
    private val refactoringScope: SearchScope = GlobalSearchScope.projectScope(project)
    private var transaction: RefactoringTransaction? = null
    private val file: PsiFile
        get() = primaryElement.containingFile ?: throw IllegalStateException("Primary element must have containing file")

    init {
        RenameUtil.assertNonCompileElement(primaryElement)

        allRenames.put(primaryElement, newName)

        for (factory in AutomaticRenamerFactory.EP_NAME.extensionList) {
            if (factory.isApplicable(primaryElement) && factory.optionName != null && factory.isEnabled) {
                renamerFactories.add(factory)
            }
        }
    }

    internal fun rename() {
        if (!primaryElement.isValid()) return
        prepareRenaming(primaryElement, newName, allRenames)

        if (!PsiDocumentManager.getInstance(project).commitAllDocumentsUnderProgress()) {
            return
        }

        DumbService.getInstance(project).completeJustSubmittedTasks()

        var usagesIn = foundUsages
        val conflicts = MultiMap<PsiElement?, String?>()

        RenameUtil.addConflictDescriptions(usagesIn, conflicts)
        RenamePsiElementProcessor.forElement(primaryElement)
            .findExistingNameConflicts(primaryElement, newName, conflicts, allRenames)
        if (!conflicts.isEmpty()) {
            val conflictData = RefactoringEventData()
            conflictData.putUserData(RefactoringEventData.CONFLICTS_KEY, conflicts.values())
            throw IllegalStateException(conflicts.values().joinToString(separator = "\n"))
        }

        val variableUsages: MutableList<UsageInfo> = ArrayList<UsageInfo>()
        if (!renamers.isEmpty()) {
            findRenamedVariables(variableUsages)
            val renames = linkedMapOf<PsiElement, String>()
            for (renamer in renamers) {
                val variables = renamer.getElements()
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
                    val usages =
                        ReadAction.compute<Array<UsageInfo>, RuntimeException>(ThrowableComputable {
                            RenameUtil.findUsages(
                                entry.key, entry.value, refactoringScope,
                                searchInComments, searchTextOccurrences, allRenames
                            )
                        })
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


        val usagesSet = linkedSetOf(*usagesIn)
        usagesSet.addAll(variableUsages)
        val conflictUsages = RenameUtil.removeConflictUsages(usagesSet)
        if (conflictUsages != null) {
            skippedUsages.addAll(conflictUsages)
        }
        usagesIn = usagesSet.toTypedArray<UsageInfo>()

        if (!PsiElementRenameHandler.canRename(project, null, primaryElement)) {
            return
        }

        execute(usagesIn)
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
            for (element in automaticVariableRenamer.getElements()) {
                automaticVariableRenamer.setRename(element, automaticVariableRenamer.getNewName(element))
            }
        }

        for (renamer in renamers) {
            renamer.findUsages(variableUsages, searchInComments, searchTextOccurrences, skippedUsages, allRenames)
        }
    }

    private fun addElement(element: PsiElement, newName: String) {
        RenameUtil.assertNonCompileElement(element)
        allRenames.put(element, newName)
    }

    val foundUsages: Array<UsageInfo> by lazy {
        runReadAction {
            renamers.clear()
            val result = mutableListOf<UsageInfo>()

            for (element in ArrayList(allRenames.keys)) {
                if (element == null) {
                    LOG.error("primary: $primaryElement; renamers: $renamers")
                    continue
                }

                val newName = allRenames.get(element)
                val usages = RenameUtil.findUsages(
                    element, newName, refactoringScope,
                    searchInComments, searchTextOccurrences, allRenames
                )
                val usagesList = listOf(*usages)
                result.addAll(usagesList)

                for (factory in renamerFactories) {
                    if (factory.isApplicable(element)) {
                        renamers.add(factory.createRenamer(element, newName, usagesList))
                    }
                }

                for (factory in AutomaticRenamerFactory.EP_NAME.extensionList) {
                    if (factory.getOptionName() == null && factory.isApplicable(element)) {
                        renamers.add(factory.createRenamer(element, newName, usagesList))
                    }
                }
            }

            UsageViewUtil.removeDuplicatedUsages(result.toTypedArray<UsageInfo>())
        }
    }

    private val beforeData: RefactoringEventData?
        get() {
            val data = RefactoringEventData()
            data.addElement(primaryElement)
            return data
        }

    private fun performRefactoring(usages: Array<UsageInfo>) {
        val postRenameCallbacks = mutableListOf<() -> Unit>()

        val renameEvents = MultiMap.createLinked<RefactoringElementListener?, SmartPsiElementPointer<PsiElement?>?>()

        val usagesList = listOf(*usages)
        val classified: MultiMap<PsiElement, UsageInfo> = classifyUsages(allRenames.keys, usagesList)

        for (element in allRenames.keys) {
            if (!element!!.isValid()) {
                LOG.error(PsiInvalidElementAccessException(element))
                continue
            }
            val newName: String = allRenames.get(element)!!

            val elementListener = transaction!!.getElementListener(element)
            val infos: Collection<UsageInfo> = classified.get(element)
            val renamePsiElementProcessor = RenamePsiElementProcessor.forElement(element)
            val postRenameCallback: (() -> Unit)? =
                renamePsiElementProcessor.getPostRenameCallback(element, newName, infos, allRenames, elementListener)
                    ?.let {
                        { it.run() }
                    }

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
                            SmartPointerManager.createPointer<PsiElement?>(newElement)
                        )
                    }
                })

            if (postRenameCallback != null) {
                postRenameCallbacks.add(postRenameCallback)
            }
        }

        nonCodeUsages = usagesList.filterIsInstance<NonCodeUsageInfo>()
            .toTypedArray()

        afterRename(postRenameCallbacks, renameEvents)
    }

    private fun afterRename(
        postRenameCallbacks: List<() -> Unit>,
        renameEvents: MultiMap<RefactoringElementListener?, SmartPsiElementPointer<PsiElement?>?>
    ) {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        for (entry in renameEvents.entrySet()) {
            for (pointer in entry.value) {
                val element = pointer!!.getElement()
                if (element != null) {
                    entry.key!!.elementRenamed(element)
                }
            }
        }

        for (runnable in postRenameCallbacks) {
            runnable()
        }
    }

    private fun performPsiSpoilingRefactoring() {
        RenameUtil.renameNonCodeUsages(project, nonCodeUsages)
    }

    @ApiStatus.OverrideOnly
    private fun execute(usages: Array<UsageInfo>) {
        CommandProcessor.getInstance().executeCommand(project, Runnable {
            val usageInfos: MutableCollection<UsageInfo> = linkedSetOf(*usages)
            doRefactoring(usageInfos)
            SuggestedRefactoringProvider.getInstance(project).reset()
        }, "Rename", null)
    }

    private fun doRefactoring(usageInfoSet: MutableCollection<UsageInfo>) {
        val iterator: MutableIterator<UsageInfo> = usageInfoSet.iterator()
        while (iterator.hasNext()) {
            val usageInfo = iterator.next()
            val element = usageInfo.element
            if (element == null || !usageInfo.isWritable) {
                iterator.remove()
            }
        }


        val writableUsageInfos: Array<UsageInfo> = usageInfoSet.toTypedArray<UsageInfo>()
        val eventData = this.beforeData
        eventData?.addUsages(listOf(*writableUsageInfos))

        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val listenerManager =
            RefactoringListenerManager.getInstance(project) as RefactoringListenerManagerImpl
        transaction = listenerManager.startTransaction()
        val preparedData = linkedMapOf<RefactoringHelper<*>?, Any?>()

        val data = ReadAction.compute<RefactoringEventData?, RuntimeException?>(ThrowableComputable { this.beforeData })
        val elements = data?.getUserData(RefactoringEventData.PSI_ELEMENT_ARRAY_KEY)
        val primaryElement = data?.getUserData(RefactoringEventData.PSI_ELEMENT_KEY)
        val allElements = when (elements) {
                null -> arrayOf(primaryElement)
                else -> elements + primaryElement
            }

        for (helper in RefactoringHelper.EP_NAME.extensionList) {
            val operation: Any? = ReadAction.compute<Any?, Throwable> {
                helper.prepareOperation(
                    writableUsageInfos, allElements.filterNotNull()
                )
            }
            preparedData.put(helper, operation)
        }


        val app = ApplicationManagerEx.getApplicationEx()
        if (!app.runWriteActionWithCancellableProgressInDispatchThread(
                "Renaming", project, null) {
                    performRefactoring(writableUsageInfos)
                }
        ) {
            return
        }

        DumbService.getInstance(project).completeJustSubmittedTasks()

        for (e in preparedData.entries) {
            @Suppress("UNCHECKED_CAST")
            val refactoringHelper = e.key as RefactoringHelper<Any>
            val operation = e.value
            refactoringHelper.performOperation(project, operation)
        }
        transaction?.commit()
        app.runWriteActionWithCancellableProgressInDispatchThread(
            "rename", project, null) {
            performPsiSpoilingRefactoring()
        }
    }

    companion object {
        private val LOG = Logger.getInstance(Renamer::class.java)

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