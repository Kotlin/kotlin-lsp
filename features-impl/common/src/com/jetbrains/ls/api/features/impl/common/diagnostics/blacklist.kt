// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.diagnostics

import kotlin.reflect.KClass

class Blacklist(entries: List<BlacklistEntry>) {
    constructor(vararg entries: BlacklistEntry) : this(entries.toList())

    private val implementationClasses = entries.filterIsInstance<BlacklistEntry.Class>().map { entry -> entry.fqcn }

    private val superClasses = entries.filterIsInstance<BlacklistEntry.SuperClass>().map { entry -> entry.fqcn }

    fun containsImplementation(fqcn: String): Boolean {
        return fqcn in implementationClasses
    }

    fun containsSuperClass(inspectionInstance: Any): Boolean {
        return inspectionInstance::class.supertypes.any { kType ->
            (kType.classifier as? KClass<*>)?.java?.name in superClasses
        }
    }
}

sealed class BlacklistEntry {
    abstract val reason: String

    protected fun ensureReason() {
        require(reason.isNotBlank()) { "reason must not be blank" }
    }

    data class Class(val fqcn: String, override val reason: String) : BlacklistEntry() {
        init {
            ensureReason()
        }
    }

    data class SuperClass(val fqcn: String, override val reason: String) : BlacklistEntry() {
        init {
            ensureReason()
        }
    }
}
