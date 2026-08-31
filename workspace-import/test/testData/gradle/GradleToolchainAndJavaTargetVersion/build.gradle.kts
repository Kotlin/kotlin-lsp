plugins {
    id("java")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

java {
    targetCompatibility = JavaVersion.VERSION_1_8
}