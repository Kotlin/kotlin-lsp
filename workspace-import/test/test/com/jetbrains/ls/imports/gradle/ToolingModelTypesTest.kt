// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle

import com.jetbrains.ls.imports.gradle.action.ProjectMetadata
import org.junit.jupiter.api.Test
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType

/**
 * The Gradle tooling model is built in the Gradle daemon and deserialized in the IDE, where every plugin has its own
 * `PluginClassLoader`. A type bundled by two plugins exists twice, as two unequal [Class] objects with the same name,
 * and reading such a value throws `ClassCastException` (IJPL-XXXXX: `IdeaKotlinResolvedBinaryDependency` cannot be cast
 * to `IdeaKotlinDependency`).
 *
 * Therefore, the model may only expose types with a single, unambiguous owner: the JDK, the Gradle tooling API, and our
 * own model classes.
 */
class ToolingModelTypesTest {

    @Test
    //https://youtrack.jetbrains.com/issue/LSP-1561
    fun `tooling model exposes only types owned by a single classloader`() {
        val violations = sortedSetOf<String>()
        val visited = mutableSetOf<Class<*>>()
        val queue = ArrayDeque(listOf<Class<*>>(ProjectMetadata::class.java))

        while (queue.isNotEmpty()) {
            val cls = queue.removeFirst()
            if (!visited.add(cls)) continue
            if (cls.isPrimitive || cls.isArray) continue

            if (ALLOWED_PACKAGES.none { cls.name.startsWith(it) }) {
                violations += cls.name
                continue
            }
            if (!cls.name.startsWith(OUR_PACKAGE)) continue // don't walk into the JDK / Gradle API

            val referenced = mutableListOf<Type>()
            cls.declaredFields.filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }.mapTo(referenced) { it.genericType }
            cls.declaredMethods.filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }.mapTo(referenced) { it.genericReturnType }
            cls.permittedSubclasses?.forEach { referenced += it }
            cls.declaredClasses.forEach { referenced += it } // Kotlin sealed hierarchies are usually nested classes
            referenced.forEach { collectClasses(it, queue) }
        }

        if (violations.isNotEmpty()) {
            org.junit.jupiter.api.Assertions.fail<Unit>(
                "Gradle tooling model references types that may be bundled by several IDE plugins:\n" +
                violations.joinToString("\n") { "  $it" } +
                "\nExpose plain data owned by 'com.jetbrains.ls.imports' instead, or the model will fail to " +
                "deserialize with a ClassCastException between two PluginClassLoaders."
            )
        }
    }

    private fun collectClasses(type: Type, out: MutableList<Class<*>>) {
        when (type) {
            is Class<*> -> out += if (type.isArray) type.componentType else type
            is ParameterizedType -> {
                collectClasses(type.rawType, out)
                type.actualTypeArguments.forEach { collectClasses(it, out) }
            }
            is WildcardType -> (type.upperBounds + type.lowerBounds).forEach { collectClasses(it, out) }
            is GenericArrayType -> collectClasses(type.genericComponentType, out)
            is TypeVariable<*> -> type.bounds.forEach { collectClasses(it, out) }
        }
    }
}

private const val OUR_PACKAGE = "com.jetbrains.ls.imports."

private val ALLOWED_PACKAGES = listOf(
    OUR_PACKAGE,
    "java.",
    "kotlin.", // erases to JDK types or is loaded from the single bundled kotlin-stdlib
    "org.gradle.tooling.", // tooling API models are proxies created by the tooling API itself
    "org.jetbrains.annotations.",
)
