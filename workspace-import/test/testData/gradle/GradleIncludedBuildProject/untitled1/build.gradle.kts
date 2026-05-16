plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

repositories {
    gradlePluginPortal()
}

gradlePlugin {
    plugins {
        create("kotlinConventionsPlugin") {
            id = "org.example.kotlin-plugin"
            implementationClass = "org.example.KotlinPlugin"
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0")
}
