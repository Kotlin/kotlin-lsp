// Appends a task request whose `rootDir` is not a build root, which makes Gradle task selection fail with
// "Could not find included build with root directory ...". Real plugins do this, e.g. Stonecutter 0.9.7.
gradle.projectsEvaluated {
    startParameter.setTaskRequests(
        startParameter.taskRequests + org.gradle.internal.DefaultTaskExecutionRequest(
            listOf("help"),
            null,
            layout.projectDirectory.dir("not-a-build").asFile
        )
    )
}
