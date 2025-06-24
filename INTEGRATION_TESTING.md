# Integration Testing for LSP Bug Fixes

This document describes the integration tests created for the Kotlin LSP bug fixes and how to run them.

## Overview

We have implemented comprehensive integration tests to verify the bug fixes for:

1. **Code Action Sorting (Issue #41)** - Ensures fix suggestions appear in consistent order
2. **Completion Item Ordering** - Priority-based sorting for better completion consistency  
3. **Diagnostic Message Enhancement (Issue #36)** - Improved context for Neovim LSP

## Test Structure

### Code Action Sorting Tests
**File**: `features-impl/kotlin/test/com/jetbrains/ls/api/features/impl/kotlin/codeActions/CodeActionSortingIntegrationTest.kt`

Tests that verify:
- Code actions are sorted alphabetically by title
- Sorting is stable across multiple invocations
- Edge cases (empty lists, single items, special characters)
- Concurrent environment stability
- Property preservation during sorting

### Completion Item Sorting Tests  
**File**: `features-impl/kotlin/test/com/jetbrains/ls/api/features/impl/kotlin/completion/CompletionSortingIntegrationTest.kt`

Tests that verify:
- Priority-based sorting: Keywords → Local items → Imported items
- Alphabetical ordering within each priority group
- Stability with large datasets
- Mixed case handling
- Edge cases and performance

### Diagnostic Message Enhancement Tests
**File**: `features-impl/kotlin/test/com/jetbrains/ls/api/features/impl/kotlin/diagnostics/DiagnosticMessageEnhancementTest.kt`

Tests that verify:
- Factory name is appended to diagnostic messages for context
- Duplicate factory names are not added
- Edge cases (empty names, special characters)
- Message structure preservation
- Neovim compatibility improvements

## Running the Tests

### Individual Test Files
```bash
# Run code action sorting tests
./gradlew :features-impl:kotlin:test --tests "CodeActionSortingIntegrationTest"

# Run completion sorting tests  
./gradlew :features-impl:kotlin:test --tests "CompletionSortingIntegrationTest"

# Run diagnostic enhancement tests
./gradlew :features-impl:kotlin:test --tests "DiagnosticMessageEnhancementTest"
```

### All Integration Tests
```bash
# Run all tests in the features-impl module
./gradlew :features-impl:kotlin:test
```

## Test Design Principles

### Mocking Strategy
- **Lightweight Mocks**: Used simple data classes to mock complex dependencies
- **Focused Testing**: Tests focus on the specific logic being fixed rather than integration with heavy IntelliJ components
- **Self-Contained**: Tests don't require external services or complex setup

### Test Coverage
- **Happy Path**: Normal scenarios that should work correctly
- **Edge Cases**: Empty inputs, null values, special characters
- **Performance**: Large datasets to ensure scalability
- **Stability**: Multiple runs to ensure consistency

### Naming Convention
- Test methods use descriptive names with backticks for readability
- Tests are grouped by functionality and clearly document expected behavior

## Integration with CI/CD

These tests can be integrated into continuous integration pipelines:

```yaml
# Example GitHub Actions step
- name: Run LSP Integration Tests
  run: |
    ./gradlew :features-impl:kotlin:test
    ./gradlew :api.features:test
```

## Bug Fix Validation

Each test suite validates the specific fixes implemented:

1. **Code Action Sorting**: Verifies that `sortedBy { it.title }` produces consistent ordering
2. **Completion Priority**: Validates the priority system (keywords=0, locals=2, imports=3)  
3. **Diagnostic Enhancement**: Confirms factory names are appended correctly

## Future Test Enhancements

Consider adding:
- Performance benchmarks for large completion lists
- Integration tests with real Kotlin files
- End-to-end tests with LSP protocol simulation
- Regression tests for reported issues

## Troubleshooting

If tests fail:

1. **Check Dependencies**: Ensure JUnit 5 and required libraries are available
2. **Verify Imports**: Make sure all necessary imports are resolved
3. **Review Changes**: Confirm the actual implementation matches test expectations
4. **Check Build Configuration**: Ensure test dependencies are properly configured in `BUILD.bazel`

## Manual Testing

In addition to automated tests, manual testing can be performed:

1. **Code Actions**: Trigger quick fixes in IDE and verify alphabetical ordering
2. **Completion**: Type partial symbols and check completion order
3. **Diagnostics**: Introduce errors and verify enhanced messages in LSP clients

The integration tests provide confidence that the bug fixes work correctly and will prevent regressions in future releases. 