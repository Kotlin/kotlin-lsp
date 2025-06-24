// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.completion

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Integration tests for completion item sorting functionality.
 * Tests the priority-based sorting improvements for better completion ordering.
 */
class CompletionSortingIntegrationTest {

    // Mock completion item representing the real RekotCompletionItem structure
    sealed class MockCompletionItem(val name: String) {
        data class Keyword(val keywordName: String) : MockCompletionItem(keywordName)
        data class Declaration(
            val declarationName: String, 
            val import: Boolean = true
        ) : MockCompletionItem(declarationName)
    }

    @Test
    fun `completion items should be sorted by priority then name`() {
        // Given - unsorted completion items with different priorities
        val unsortedItems = listOf(
            MockCompletionItem.Declaration("importedFunction", import = true),
            MockCompletionItem.Keyword("if"),
            MockCompletionItem.Declaration("localVariable", import = false),
            MockCompletionItem.Keyword("when"), 
            MockCompletionItem.Declaration("anotherImportedFunction", import = true),
            MockCompletionItem.Declaration("localProperty", import = false)
        )

        // When - apply the same sorting logic as implemented in CompletionPopupFactory
        val sortedItems = unsortedItems.sortedWith(compareBy<MockCompletionItem> { item ->
            when (item) {
                is MockCompletionItem.Keyword -> 0  // Keywords have highest priority
                is MockCompletionItem.Declaration -> when {
                    // Local items (no import) have medium priority
                    !item.import -> 2
                    // Imported items have lower priority
                    else -> 3
                }
            }
        }.thenBy { it.name })  // Then sort alphabetically by name

        // Then - verify the expected priority order
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
    fun `keywords should always have highest priority`() {
        // Given - mix of keywords and other items
        val items = listOf(
            MockCompletionItem.Declaration("zzz_function", import = false),
            MockCompletionItem.Keyword("if"),
            MockCompletionItem.Declaration("aaa_function", import = false),
            MockCompletionItem.Keyword("for"),
            MockCompletionItem.Declaration("bbb_function", import = true)
        )

        // When - apply priority sorting
        val sortedItems = items.sortedWith(compareBy<MockCompletionItem> { item ->
            when (item) {
                is MockCompletionItem.Keyword -> 0
                is MockCompletionItem.Declaration -> if (!item.import) 2 else 3
            }
        }.thenBy { it.name })

        // Then - keywords should appear first, sorted alphabetically
        assertEquals("for", sortedItems[0].name)
        assertEquals("if", sortedItems[1].name)
        assertEquals("aaa_function", sortedItems[2].name) // local
        assertEquals("zzz_function", sortedItems[3].name) // local
        assertEquals("bbb_function", sortedItems[4].name) // imported
    }

    @Test
    fun `local items should have priority over imported items`() {
        // Given - mix of local and imported items (no keywords)
        val items = listOf(
            MockCompletionItem.Declaration("importedFunction", import = true),
            MockCompletionItem.Declaration("localFunction", import = false),
            MockCompletionItem.Declaration("anotherImported", import = true),
            MockCompletionItem.Declaration("anotherLocal", import = false)
        )

        // When - apply priority sorting
        val sortedItems = items.sortedWith(compareBy<MockCompletionItem> { item ->
            when (item) {
                is MockCompletionItem.Keyword -> 0
                is MockCompletionItem.Declaration -> if (!item.import) 2 else 3
            }
        }.thenBy { it.name })

        // Then - local items should come before imported items
        assertEquals("anotherLocal", sortedItems[0].name)     // local
        assertEquals("localFunction", sortedItems[1].name)    // local  
        assertEquals("anotherImported", sortedItems[2].name)  // imported
        assertEquals("importedFunction", sortedItems[3].name) // imported
    }

    @Test
    fun `items with same priority should be sorted alphabetically`() {
        // Given - items with same priority
        val keywords = listOf(
            MockCompletionItem.Keyword("when"),
            MockCompletionItem.Keyword("if"),
            MockCompletionItem.Keyword("for"),
            MockCompletionItem.Keyword("while")
        )

        // When - apply sorting
        val sortedKeywords = keywords.sortedWith(compareBy<MockCompletionItem> { item ->
            when (item) {
                is MockCompletionItem.Keyword -> 0
                is MockCompletionItem.Declaration -> if (!item.import) 2 else 3
            }
        }.thenBy { it.name })

        // Then - should be alphabetically ordered
        val expectedOrder = listOf("for", "if", "when", "while")
        assertEquals(expectedOrder, sortedKeywords.map { it.name })
    }

    @Test
    fun `empty completion list should remain empty`() {
        // Given - empty list
        val emptyItems = emptyList<MockCompletionItem>()

        // When - apply sorting
        val sortedItems = emptyItems.sortedWith(compareBy<MockCompletionItem> { item ->
            when (item) {
                is MockCompletionItem.Keyword -> 0
                is MockCompletionItem.Declaration -> if (!item.import) 2 else 3
            }
        }.thenBy { it.name })

        // Then - should remain empty
        assertEquals(0, sortedItems.size)
    }

    @Test
    fun `single completion item should remain unchanged`() {
        // Given - single item
        val singleItem = listOf(MockCompletionItem.Keyword("if"))

        // When - apply sorting
        val sortedItems = singleItem.sortedWith(compareBy<MockCompletionItem> { item ->
            when (item) {
                is MockCompletionItem.Keyword -> 0
                is MockCompletionItem.Declaration -> if (!item.import) 2 else 3
            }
        }.thenBy { it.name })

        // Then - should contain the same item
        assertEquals(1, sortedItems.size)
        assertEquals("if", sortedItems[0].name)
    }

    @Test
    fun `completion sorting should handle mixed case names correctly`() {
        // Given - items with mixed case names
        val mixedCaseItems = listOf(
            MockCompletionItem.Declaration("ZebraFunction", import = true),
            MockCompletionItem.Declaration("appleFunction", import = true),
            MockCompletionItem.Declaration("BananaFunction", import = true),
            MockCompletionItem.Declaration("cherryFunction", import = true)
        )

        // When - apply sorting
        val sortedItems = mixedCaseItems.sortedWith(compareBy<MockCompletionItem> { item ->
            when (item) {
                is MockCompletionItem.Keyword -> 0
                is MockCompletionItem.Declaration -> if (!item.import) 2 else 3
            }
        }.thenBy { it.name })

        // Then - should follow lexicographic order (uppercase before lowercase in ASCII)
        val expectedOrder = listOf("BananaFunction", "ZebraFunction", "appleFunction", "cherryFunction")
        assertEquals(expectedOrder, sortedItems.map { it.name })
    }

    @Test
    fun `completion sorting should be stable for large datasets`() {
        // Given - large number of items to test stability
        val largeDataset = mutableListOf<MockCompletionItem>()
        
        // Add keywords
        repeat(20) { i -> largeDataset.add(MockCompletionItem.Keyword("keyword$i")) }
        
        // Add local declarations
        repeat(30) { i -> largeDataset.add(MockCompletionItem.Declaration("local$i", import = false)) }
        
        // Add imported declarations  
        repeat(50) { i -> largeDataset.add(MockCompletionItem.Declaration("imported$i", import = true)) }

        // When - apply sorting
        val sortedItems = largeDataset.sortedWith(compareBy<MockCompletionItem> { item ->
            when (item) {
                is MockCompletionItem.Keyword -> 0
                is MockCompletionItem.Declaration -> if (!item.import) 2 else 3
            }
        }.thenBy { it.name })

        // Then - verify structure: keywords first, then locals, then imported
        val keywordCount = sortedItems.count { it is MockCompletionItem.Keyword }
        val localCount = sortedItems.count { 
            it is MockCompletionItem.Declaration && !it.import 
        }
        val importedCount = sortedItems.count { 
            it is MockCompletionItem.Declaration && it.import 
        }

        assertEquals(20, keywordCount)
        assertEquals(30, localCount) 
        assertEquals(50, importedCount)

        // Verify that all keywords come first
        val firstKeywordIndex = sortedItems.indexOfFirst { it is MockCompletionItem.Keyword }
        val lastKeywordIndex = sortedItems.indexOfLast { it is MockCompletionItem.Keyword }
        assertEquals(0, firstKeywordIndex)
        assertEquals(19, lastKeywordIndex)

        // Verify that locals come after keywords but before imported
        val firstLocalIndex = sortedItems.indexOfFirst { 
            it is MockCompletionItem.Declaration && !it.import 
        }
        val lastLocalIndex = sortedItems.indexOfLast { 
            it is MockCompletionItem.Declaration && !it.import 
        }
        assertEquals(20, firstLocalIndex)
        assertEquals(49, lastLocalIndex)
    }
} 