# SystemProperties Test Project

This is a minimal Gradle project to verify that
Gradle Project Import in LSP passes the expected 
system properties to the Gradle build process.

The `build.gradle.kts` uses `require()` to assert that:
- `idea.sync.active` is `"true"` — imitates how IntelliJ invokes Gradle during sync
- `com.jetbrains.ls.imports.gradle` is `"true"` — identifies the Kotlin LSP server

If either property is missing or has the wrong value, Gradle configuration fails and
the workspace import test fails accordingly.
