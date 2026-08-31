plugins {
    id("java")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
}