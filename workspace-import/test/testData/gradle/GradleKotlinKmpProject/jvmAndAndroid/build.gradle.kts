import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

// Writes a source file into `commonMain`.
val generateBuildInfo by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/buildInfo/kotlin")
    outputs.dir(outputDir)
    doLast {
        val packageDir = outputDir.get().asFile.resolve("org/example/project/jvmandandroid")
        packageDir.mkdirs()
        packageDir.resolve("BuildInfo.kt").writeText(
            """
            |package org.example.project.jvmandandroid
            |
            |const val BUILD_NAME: String = "jvmAndAndroid"
            |
            """.trimMargin()
        )
    }
}

// Only JVM and Android targets.
kotlin {
    jvm()

    android {
        namespace = "org.example.project.jvmandandroid"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateBuildInfo)
        }
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(libs.kotlinx.coroutinesCore)
        }
    }
}
