plugins {
    kotlin("jvm")
}

dependencies {
    implementation(kotlin("stdlib"))
}

// Custom source sets
sourceSets {
    create("integrationTest") {
        kotlin.srcDirs("src/integrationTest/kotlin")
        resources.srcDirs("src/integrationTest/resources")
        compileClasspath += sourceSets["main"].output
        runtimeClasspath += sourceSets["main"].output
    }
}

tasks.register<Test>("integrationTest") {
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
}