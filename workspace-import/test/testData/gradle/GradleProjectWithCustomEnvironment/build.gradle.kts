plugins {
    id("java")
}

if (System.getenv("CUSTOM_ENVIRONMENT_VARIABLE") != "hello_world") {
    throw IllegalStateException("Environment variable is not set")
}

if (System.getProperty("intellij.lsp.custom.property")  != "world_hello") {
    throw IllegalStateException("VM property is not set")
}