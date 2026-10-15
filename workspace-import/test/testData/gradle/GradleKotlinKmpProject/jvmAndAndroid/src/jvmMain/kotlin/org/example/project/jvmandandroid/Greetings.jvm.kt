package org.example.project.jvmandandroid

actual fun platformName(): String = "Java ${System.getProperty("java.version")}"
