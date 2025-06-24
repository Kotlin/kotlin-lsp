# Testing Guide for LSP Bug Fixes

This document outlines the bug fixes implemented and provides guidance on testing them.

## Bug Fixes Implemented

### 1. Fixed Code Action Ordering (Issue #41)

**Problem**: Fix suggestions were appearing in random order, making it difficult for users to find the most relevant fixes.

**Solution**: Added sorting to code actions by title in `LSKotlinCompilerDiagnosticsFixesCodeActionProvider.kt`:
- Code actions are now sorted alphabetically by title
- Ensures consistent ordering across different invocations
- Improves user experience in editors like Neovim

**Files Modified**:
- `features-impl/kotlin/src/com/jetbrains/ls/api/features/impl/common/kotlin/diagnostics/compiler/LSKotlinCompilerDiagnosticsFixesCodeActionProvider.kt`

**Testing**:
```kotlin
// Test case: Verify code actions are sorted by title
val unsortedActions = listOf("Zebra fix", "Alpha fix", "Beta fix")
val sortedActions = actions.sortedBy { it.title }
// Expected: ["Alpha fix", "Beta fix", "Zebra fix"]
```

### 2. Enhanced Diagnostic Messages (Issue #36)

**Problem**: Neovim users weren't getting enough context in diagnostic messages.

**Solution**: Enhanced diagnostic messages to include factory name for additional context in `LSKotlinCompilerDiagnosticsProvider.kt`:
- Appends factory name in parentheses if not already present in message
- Provides better context for debugging and understanding errors
- Improves Neovim LSP diagnostic display

**Files Modified**:
- `features-impl/kotlin/src/com/jetbrains/ls/api/features/impl/common/kotlin/diagnostics/compiler/LSKotlinCompilerDiagnosticsProvider.kt`

**Testing**:
```kotlin
// Test case: Verify enhanced diagnostic messages
val diagnostic = createDiagnostic("Type mismatch", "TYPE_MISMATCH")
val enhancedMessage = diagnostic.message
// Expected: "Type mismatch (TYPE_MISMATCH)"
```

### 3. Improved Completion Item Ordering

**Problem**: Completion items appeared in inconsistent order, affecting user experience.

**Solution**: Added priority-based sorting to completion items in `CompletionPopupFactory.kt`:
- Keywords have highest priority (0)
- Local variables/properties have medium priority (2) 
- Imported items have lower priority (3)
- Items with same priority are sorted alphabetically

**Files Modified**:
- `features-impl/kotlin/src/com/jetbrains/ls/api/features/impl/common/kotlin/completion/rekot/CompletionPopupFactory.kt`

**Testing**:
```kotlin
// Test case: Verify completion priority ordering
val items = listOf(
    CompletionItem("importedFunction", import = true),
    CompletionItem("if", isKeyword = true),
    CompletionItem("localVar", import = false)
)
val sorted = items.sortedByPriority()
// Expected order: ["if", "localVar", "importedFunction"]
```

## Manual Testing Instructions

### Testing Code Action Ordering
1. Open a Kotlin file with compilation errors
2. Request code actions (quick fixes) for any diagnostic
3. Verify that code actions appear in consistent alphabetical order
4. Repeat the request multiple times to ensure consistency

### Testing Enhanced Diagnostic Messages
1. Create a Kotlin file with type mismatches or other errors
2. Observe diagnostic messages in your LSP client (especially Neovim)
3. Verify that diagnostic messages include factory names in parentheses
4. Check that the additional context helps with understanding the error

### Testing Completion Ordering
1. Open a Kotlin file in your editor
2. Start typing to trigger code completion
3. Verify that keywords appear first, followed by local variables, then imported functions
4. Within each category, items should be alphabetically sorted

## Integration Testing

To run integration tests for these fixes:

1. Set up the Kotlin LSP in your development environment
2. Create test Kotlin files that trigger the fixed scenarios
3. Use LSP client tools to verify the expected behavior
4. Compare behavior before and after the fixes

## Expected Outcomes

- **Consistency**: Code actions and completions appear in predictable order
- **Better UX**: Users can find relevant fixes and completions more easily
- **Enhanced Context**: Diagnostic messages provide more debugging information
- **Cross-Editor Compatibility**: Improvements work across different LSP clients

## Future Improvements

Consider adding:
- Unit tests for the sorting logic
- Integration tests with mock LSP clients
- Performance benchmarks for the sorting operations
- User preference settings for completion ordering 