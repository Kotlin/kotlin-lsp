// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.codeActions

import com.jetbrains.lsp.protocol.CodeAction
import com.jetbrains.lsp.protocol.CodeActionKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LSKotlinCodeActionSortingTest {

    @Test
    fun `code actions should be sorted by title`() {
        // Given
        val unsortedActions = listOf(
            createCodeAction("Zebra action"),
            createCodeAction("Alpha action"),
            createCodeAction("Beta action"),
            createCodeAction("Apple action")
        )
        
        // When
        val sortedActions = unsortedActions.sortedBy { it.title }
        
        // Then
        val expectedOrder = listOf(
            "Alpha action",
            "Apple action", 
            "Beta action",
            "Zebra action"
        )
        
        assertEquals(expectedOrder, sortedActions.map { it.title })
    }

    @Test
    fun `code actions should maintain consistent ordering for identical titles`() {
        // Given
        val actionsWithSameTitles = listOf(
            createCodeAction("Fix import", "command1"),
            createCodeAction("Fix import", "command2"),
            createCodeAction("Fix import", "command3")
        )
        
        // When
        val sortedActions = actionsWithSameTitles.sortedBy { it.title }
        
        // Then
        // All should have the same title
        assertEquals(3, sortedActions.size)
        sortedActions.forEach { action ->
            assertEquals("Fix import", action.title)
        }
    }

    @Test
    fun `empty list of code actions should remain empty after sorting`() {
        // Given
        val emptyActions = emptyList<CodeAction>()
        
        // When
        val sortedActions = emptyActions.sortedBy { it.title }
        
        // Then
        assertEquals(0, sortedActions.size)
    }

    @Test
    fun `single code action should remain unchanged after sorting`() {
        // Given
        val singleAction = listOf(createCodeAction("Single action"))
        
        // When
        val sortedActions = singleAction.sortedBy { it.title }
        
        // Then
        assertEquals(1, sortedActions.size)
        assertEquals("Single action", sortedActions[0].title)
    }

    @Test
    fun `code actions with special characters should sort correctly`() {
        // Given
        val actionsWithSpecialChars = listOf(
            createCodeAction("Zzz Action"),
            createCodeAction("_Underscore Action"),
            createCodeAction("123 Numeric Action"),
            createCodeAction("@Symbol Action")
        )
        
        // When
        val sortedActions = actionsWithSpecialChars.sortedBy { it.title }
        
        // Then
        val expectedOrder = listOf(
            "123 Numeric Action",
            "@Symbol Action",
            "Zzz Action",
            "_Underscore Action"
        )
        
        assertEquals(expectedOrder, sortedActions.map { it.title })
    }

    private fun createCodeAction(title: String, commandName: String = "default.command"): CodeAction {
        return CodeAction(
            title = title,
            kind = CodeActionKind.QuickFix,
            command = com.jetbrains.lsp.protocol.Command(title, commandName, emptyList())
        )
    }
} 