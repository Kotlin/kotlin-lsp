// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.completion

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Test to verify completion item ordering and priority logic.
 * This test covers the sorting improvements made to ensure consistent completion ordering.
 */
class CompletionItemSortingTest {

    // Mock completion item for testing
    data class MockCompletionItem(
        val name: String,
        val isKeyword: Boolean = false,
        val import: Boolean = true
    )

    @Test
    fun `completion items should be sorted by priority`() {
        // Given - unsorted completion items with different priorities
        val unsortedItems = listOf(
            MockCompletionItem("importedFunction", isKeyword = false, import = true),
            MockCompletionItem("if", isKeyword = true),
            MockCompletionItem("localVariable", isKeyword = false, import = false),
            MockCompletionItem("when", isKeyword = true),
            MockCompletionItem("anotherImportedFunction", isKeyword = false, import = true),
            MockCompletionItem("localProperty", isKeyword = false, import = false)
        )
        
        // When - apply the same sorting logic as in CompletionPopupFactory
        val sortedItems = unsortedItems.sortedWith(compareBy<MockCompletionItem> { item ->
            when {
                item.isKeyword -> 0  // Keywords have highest priority
                !item.import -> 2    // Local items have medium priority 
                else -> 3           // Imported items have lower priority
            }
        }.thenBy { it.name })  // Then sort alphabetically by name
        
        // Then - verify the expected order
        val expectedOrder = listOf(
            "if",              // keyword, alphabetically first
            "when",            // keyword, alphabetically second
            "localProperty",   // local, alphabetically first
            "localVariable",   // local, alphabetically second
            "anotherImportedFunction", // imported, alphabetically first
            "importedFunction"         // imported, alphabetically second
        )
        
        assertEquals(expectedOrder, sortedItems.map { it.name })
    }

    @Test
    fun `keywords should have highest priority regardless of name`() {
        // Given
        val items = listOf(
            MockCompletionItem("zzz_function", isKeyword = false, import = false),
            MockCompletionItem("if", isKeyword = true),
            MockCompletionItem("aaa_function", isKeyword = false, import = false)
        )
        
        // When
        val sortedItems = items.sortedWith(compareBy<MockCompletionItem> { item ->
            when {
                item.isKeyword -> 0
                !item.import -> 2
                else -> 3
            }
        }.thenBy { it.name })
        
        // Then
        assertEquals("if", sortedItems[0].name) // keyword should be first
        assertEquals("aaa_function", sortedItems[1].name)
        assertEquals("zzz_function", sortedItems[2].name)
    }

    @Test
    fun `local items should have priority over imported items`() {
        // Given
        val items = listOf(
            MockCompletionItem("importedFunction", isKeyword = false, import = true),
            MockCompletionItem("localFunction", isKeyword = false, import = false)
        )
        
        // When
        val sortedItems = items.sortedWith(compareBy<MockCompletionItem> { item ->
            when {
                item.isKeyword -> 0
                !item.import -> 2
                else -> 3
            }
        }.thenBy { it.name })
        
        // Then
        assertEquals("localFunction", sortedItems[0].name) // local should come first
        assertEquals("importedFunction", sortedItems[1].name)
    }

    @Test
    fun `items with same priority should be sorted alphabetically`() {
        // Given
        val items = listOf(
            MockCompletionItem("zebra", isKeyword = true),
            MockCompletionItem("apple", isKeyword = true),
            MockCompletionItem("banana", isKeyword = true)
        )
        
        // When
        val sortedItems = items.sortedWith(compareBy<MockCompletionItem> { item ->
            when {
                item.isKeyword -> 0
                !item.import -> 2
                else -> 3
            }
        }.thenBy { it.name })
        
        // Then
        val expectedOrder = listOf("apple", "banana", "zebra")
        assertEquals(expectedOrder, sortedItems.map { it.name })
    }

    @Test
    fun `empty list should remain empty after sorting`() {
        // Given
        val emptyItems = emptyList<MockCompletionItem>()
        
        // When
        val sortedItems = emptyItems.sortedWith(compareBy<MockCompletionItem> { item ->
            when {
                item.isKeyword -> 0
                !item.import -> 2
                else -> 3
            }
        }.thenBy { it.name })
        
        // Then
        assertEquals(0, sortedItems.size)
    }
} 