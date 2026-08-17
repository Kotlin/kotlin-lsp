# Broken Task Graph Test Project

A minimal Gradle project whose build script breaks Gradle's task graph: it appends a `TaskExecutionRequest`
with a `rootDir` that is not a build root, so task selection fails with
`Could not find included build with root directory ...`.

Models the project can still be built, so the import must succeed: `GradleWorkspaceImporter` is expected to
log a warning and retry the build action without sync tasks.
