// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.utils

import java.lang.reflect.Method


sealed class Reflected {
    data class Static(val clazz: Class<*>) : Reflected()

    data class Instance(val instance: Any) : Reflected() {
        val static: Static get() = Static(this.javaClass)

        inline fun <reified T> unwrapAs(): T? {
            if (T::class.java.isInstance(instance)) {
                return this.instance as T
            }

            val cast = T::class.java.cast(instance)
            if (cast != null) return cast

            throw IllegalArgumentException(
                "Cannot unwrap ${this.javaClass} as ${T::class.java}"
            )
        }
    }

    data class Param<T>(val clazz: Class<T>, val value: T?)

    /**
     * Dispatches and invokes a call on the current reflection target.
     * @param params all params; non-static methods do **not** have to include the receiver object (as available in [Instance.instance])
     * Types provided by [Param.clazz] will have precedence in resolving the correct method.
     * If no method matches the exact signature provided by the params 'clazz'es, then a matching method will be found,
     * by dynamically trying to find a method which _can_ be invoked provided the params types.
     */
    fun call(name: String, vararg params: Param<*>): Instance? {
        val targetClass = when (this) {
            is Static -> clazz
            is Instance -> instance.javaClass
        }

        val method = targetClass.resolveMethod(name, params.toList()) ?: return null

        val result = method.invoke(
            when (this) {
                is Static -> null
                is Instance -> instance
            }, *params.map { param -> param.value }.toTypedArray()
        )

        if (result == null) return null
        return Instance(result)
    }

}


internal val Any.reflected get() = Reflected.Instance(this)

fun staticReflected(clazz: Class<*>): Reflected {
    return Reflected.Static(clazz)
}

inline fun <reified T> staticReflected(): Reflected {
    return staticReflected(T::class.java)
}

/**
 * Overload to avoid accidentally calling 'reflected' on already wrapped objects
 */
@Suppress("UNUSED", "UnusedReceiverParameter")
internal val Reflected.reflected: Nothing
    get() = throw UnsupportedOperationException("Cannot call 'reflected' on reflected object")

internal inline fun <reified T> param(
    value: T?, type: Class<T> = T::class.java
) = Reflected.Param(type, value)

fun param(value: Any): Reflected.Param<*> {
    if (value is Reflected.Instance) {
        return param(value.instance)
    }

    return Reflected.Param(value.javaClass, value)
}

fun param(value: Int): Reflected.Param<*> {
    return Reflected.Param(Int::class.javaPrimitiveType!!, value)
}


private fun Class<*>.resolveMethod(name: String, params: List<Reflected.Param<*>>): Method? {
    /* Fast path: Match perfectly */
    try {
        return getMethod(name, *params.map { it.clazz }.toTypedArray())
    } catch (_: NoSuchMethodException) {

    }

    /* Slow path: Match by suitable params */
    return methods.filter { method -> method.name == name && method.parameterCount == params.size }.firstOrNull { method ->
        method.parameterTypes.zip(params.map { it.clazz }).all { (methodType, paramType) ->
            methodType.isAssignableFrom(paramType)
        }
    }
}