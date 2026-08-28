// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.diagnostics

import com.intellij.codeInspection.InspectionProfileEntry
import com.intellij.openapi.diagnostic.getOrHandleException
import com.intellij.openapi.diagnostic.logger
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

private val LOG = logger<InspectionProfilePatcher>()

/**
 * The changes that the language server makes to the default inspection profile.
 *
 * A patch either turns an inspection off, or it changes the options of one,
 * because default options cause bugs or performance problems in LSP, which aren't addressed yet.
 */
class InspectionProfilePatcher(patches: List<Patch>) {
    constructor(vararg patches: Patch) : this(patches.toList())

    sealed class Patch {
        abstract val reason: String

        protected fun ensureReason() {
            require(reason.isNotBlank()) { "reason must not be blank" }
        }

        /** Turns off the inspection whose implementation class is [fqcn]. */
        data class DisableClass(val fqcn: String, override val reason: String) : Patch() {
            init {
                ensureReason()
            }
        }

        /** Turns off every inspection that has [fqcn] as a direct supertype. */
        data class DisableSuperClass(val fqcn: String, override val reason: String) : Patch() {
            init {
                ensureReason()
            }
        }

        /**
         * Sets [options] on the inspection whose implementation class is [fqcn].
         *
         * A key of [options] is the bind ID of the option, as [InspectionProfileEntry.getOptionsPane] declares it.
         */
        data class SetOptions(val fqcn: String, val options: Map<String, Any>, override val reason: String) : Patch() {
            init {
                ensureReason()
                require(options.isNotEmpty()) { "options must not be empty" }
            }
        }
    }

    private val disabledClasses: Set<String> = patches.filterIsInstance<Patch.DisableClass>().mapTo(hashSetOf()) { it.fqcn }

    private val disabledSuperClasses: Set<String> =
        patches.filterIsInstance<Patch.DisableSuperClass>().mapTo(hashSetOf()) { it.fqcn }

    private val optionsByFqcn: Map<String, Patch.SetOptions> =
        patches.filterIsInstance<Patch.SetOptions>().associateBy { it.fqcn }

    /** The options already reported as not applied, so that one wrong patch does not flood the log. */
    private val reported: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Whether a patch turns off the inspection that [implementationClass] implements.
     *
     * This is the cheap check, because it needs no instance. It sees only a [Patch.DisableClass].
     */
    fun disables(implementationClass: String): Boolean = implementationClass in disabledClasses

    /** Whether a patch turns off [tool], by its own class or by a direct supertype. */
    fun disables(tool: InspectionProfileEntry): Boolean {
        val toolClass = tool::class
        return disables(toolClass.java.name) ||
                toolClass.supertypes.any { type -> (type.classifier as? KClass<*>)?.java?.name in disabledSuperClasses }
    }

    /**
     * Sets on [tool] the options that a [Patch.SetOptions] declares for it.
     *
     * The method reads each value back, because an inspection can tie one option to another and overwrite the value
     * that this method just set. Such a patch is a bug in the language server, so the method reports it once.
     */
    fun patchOptions(tool: InspectionProfileEntry) {
        val patch = optionsByFqcn[tool::class.java.name] ?: return
        val optionController = tool.optionController
        for ((bindId, value) in patch.options) {
            runCatching {
                optionController.setOption(bindId, value)
                val applied = optionController.getOption(bindId)
                if (applied != value && reported.add("${patch.fqcn}.$bindId")) {
                    LOG.error("The option $bindId of ${patch.fqcn} is $applied, but the language server set it to $value")
                }
            }.getOrHandleException {
                LOG.warn("Cannot set the option $bindId of ${patch.fqcn}", it)
            }
        }
    }
}
