plugins {
    kotlin("jvm") version "2.2.0"
}

subprojects {
    repositories {
        mavenCentral()
    }
}

allprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }
}