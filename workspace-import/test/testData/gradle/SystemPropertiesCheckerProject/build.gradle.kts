require(System.getProperty("idea.sync.active") == "true") {
    "Expected idea.sync.active=true, got: ${System.getProperty("idea.sync.active")}"
}

require(System.getProperty("com.jetbrains.ls.imports.gradle") == "true") {
    "Expected com.jetbrains.ls.imports.gradle=true, got: ${System.getProperty("com.jetbrains.ls.imports.gradle")}"
}
