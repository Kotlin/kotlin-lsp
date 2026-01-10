plugins {
    kotlin("jvm") version "2.2.0"
    application // Enables creating a runnable application
}

repositories {
    // Maven Central (default source for most libraries)
    mavenCentral()

    // Google Maven repository (e.g., for Android-related or other Google-provided libraries)
    google()

    // JFrog Bintray (common hosting for projects like JCenter-based libraries)
    maven {
        url = uri("https://repo.gradle.org/gradle/libs-releases") // Example for Gradle-provided dependencies
    }

    // Spring Plugins repository for Spring-related dependencies
    maven {
        url = uri("https://repo.spring.io/plugins-release")
    }

    // JitPack repository for GitHub-hosted dependencies
    maven {
        url = uri("https://jitpack.io")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    // Kotlin Standard Library
    implementation(kotlin("stdlib"))

    // Coroutines for asynchronous programming
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")

    // Moshi for JSON serialization/deserialization
    implementation("com.squareup.moshi:moshi:1.15.0")

    // Apache Commons Lang - popular utility library
    implementation("org.apache.commons:commons-lang3:3.12.0")

    // Guava (core libraries for Java by Google)
    implementation("com.google.guava:guava:32.1.1-jre")

    // Logback (logging framework)
    implementation("ch.qos.logback:logback-classic:1.4.11")

    // Custom JitPack dependency hosted on GitHub
    implementation("com.github.User:Repo:Tag") // Replace "User", "Repo", and "Tag" with actual GitHub repo values

    // Compile-only dependencies (e.g., for annotation processors)
    compileOnly("org.projectlombok:lombok:1.18.28")

    // Annotation processor
    annotationProcessor("org.projectlombok:lombok:1.18.28")

    // Runtime-only dependencies (libraries needed only at runtime)
    runtimeOnly("mysql:mysql-connector-java:8.0.34") // MySQL connector for database operations

    // Testing dependencies
    testImplementation(kotlin("test")) // Kotlin test framework
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0") // JUnit 5 for testing
    testImplementation("io.mockk:mockk:1.13.7") // Mocking library for Kotlin
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.0")
}

tasks.test {
    useJUnitPlatform() // Enable JUnit 5 for testing.
}

application {
    // Define the main class for the runnable application
    mainClass.set("com.example.kotlin.MainKt")
}