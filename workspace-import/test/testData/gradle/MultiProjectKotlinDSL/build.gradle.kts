plugins {
    kotlin("jvm") version "1.9.10"
}

subprojects {
    repositories {
        mavenCentral()
    }

    plugins.apply("org.jetbrains.kotlin.jvm")

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }
}
