import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
}

repositories {
    mavenCentral()
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
            freeCompilerArgs.add("-Xreturn-value-checker=check")
            freeCompilerArgs.add("-Xcontext-parameters")
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}