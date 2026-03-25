@file:JvmName("ProxyUtil")

// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.utils

import org.gradle.tooling.internal.adapter.ProtocolToModelAdapter
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.ObjectStreamClass
import java.lang.reflect.Proxy

private val modelAdapter: ProtocolToModelAdapter = ProtocolToModelAdapter()

/**
 * Unwraps the provided [value] if it is a [Proxy] instance.
 * This method will also ensure that the instance lives within the target [loader], by
 * potentially copying the value into a new instance into the [loader].
 */
fun <T> unpackProxy(loader: ClassLoader, value: T): T {
    if (value !is Proxy) return value
    val raw = modelAdapter.unpack(value)
    val targetClass = loader.loadClass(raw.javaClass.name)
    if (targetClass == raw.javaClass) return value

    val baos = ByteArrayOutputStream()
    ObjectOutputStream(baos).use { it.writeObject(raw) }

    val obj = object : ObjectInputStream(baos.toByteArray().inputStream()) {
        override fun resolveClass(desc: ObjectStreamClass): Class<*> {
            return loader.loadClass(desc.name)
        }
    }.readObject()

    @Suppress("UNCHECKED_CAST")
    return obj as T
}