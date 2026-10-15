package org.example.project.jvmandandroid

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.example.project.sayHello

expect fun platformName(): String

fun greetings(names: List<String>): String =
    names.map { sayHello(it) }.joinToString(separator = "\n")

fun greetingFlow(name: String): Flow<String> =
    flowOf(name).map { sayHello(it) }

suspend fun firstGreeting(name: String): String = greetingFlow(name).first()
