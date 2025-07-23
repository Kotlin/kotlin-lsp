plugins {
    kotlin("jvm") version "2.2.0"
    application
}

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.apache.commons:commons-lang3:3.12.0")
    implementation("com.example.nonexistent:nonexistent-library:1.0.0")
}

application {
    // Specify the main class of the application
    mainClass.set("com.example.MainKt")
}