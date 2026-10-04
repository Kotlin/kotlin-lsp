// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")

package com.jetbrains.ls.imports.java

import com.jetbrains.ls.api.core.launch.JvmClasspath
import com.jetbrains.ls.imports.api.ModuleRuntime

import org.jetbrains.annotations.VisibleForTesting
import java.io.File
import java.lang.module.ModuleDescriptor
import java.lang.module.ModuleFinder
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * The `java` arguments after the executable that run [entry] on [runtime]: [vmArgs], the module path and the
 * class path, `--patch-module` for a modular launch, then the entry and [programArgs].
 *
 * The JPMS split is read from the compiled outputs with [ModuleFinder], not from the sources: a run follows a
 * build, so `module-info.class` is on disk when the module is modular. The main module is the descriptor among
 * [ModuleRuntime.ownOutputs]; its `requires` closure and the service providers of that closure go on the module
 * path, the rest stays on the class path. [entry] may name the module itself as `module/Class`. Without a
 * descriptor in the outputs, or with an [entry] that names no module and whose outputs hold none, the program
 * runs on the class path.
 *
 * A modular test launch has no main module: the runner is on the class path, and the test module joins through
 * `--add-modules=ALL-MODULE-PATH`.
 *
 * The outputs of the main module that are not the module itself (resources, per-language class directories)
 * are woven into it with `--patch-module`, so the module reads its own resources. Only existing directories are
 * patched: a missing one voids the rest of the patch.
 *
 * [additional] adds entries to the class path and the module path of the module ([JvmClasspath.additional]).
 */
@VisibleForTesting
fun javaArgs(
    runtime: ModuleRuntime,
    entry: String,
    vmArgs: List<String>,
    programArgs: List<String>,
    additional: JvmClasspath? = null,
): List<String> = buildList {
    addAll(vmArgs)
    if (runtime.previewFeatures && "--enable-preview" !in vmArgs) add("--enable-preview")

    val extraClassPath = additional?.classPath.orEmpty()
    val extraModulePath = additional?.modulePath.orEmpty()
    val split = jpmsSplit(runtime, entry.substringBefore('/', ""))
    if (split == null) {
        // Not a modular run: the extra module path entries have nowhere to go but the class path.
        val classPath = runtime.paths + extraClassPath + extraModulePath
        if (classPath.isNotEmpty()) { add("-cp"); add(classPath.joinToString(File.pathSeparator)) }
        add(entry.substringAfter('/'))
    } else {
        add("--module-path"); add((split.modulePath + extraModulePath).joinToString(File.pathSeparator))
        val classPath = split.classPath + extraClassPath
        if (classPath.isNotEmpty()) { add("-cp"); add(classPath.joinToString(File.pathSeparator)) }
        if (split.mainModule == null) {
            add("--add-modules=ALL-MODULE-PATH")
            add(entry.substringAfter('/'))
        } else {
            if (split.patch.isNotEmpty()) { add("--patch-module"); add("${split.mainModule}=${split.patch.joinToString(File.pathSeparator)}") }
            add("-m"); add("${split.mainModule}/${entry.substringAfter('/')}")
        }
    }
    addAll(programArgs)
}

/**
 * The `java` arguments after the executable for a run on the class path and module path the configuration spelled
 * in [classpath]: [vmArgs], `--module-path` and `-cp` as given, then the entry and [programArgs]. An [entry] of the
 * form `module/Class` runs with `-m`; nothing is derived from the outputs.
 */
@VisibleForTesting
fun explicitJavaArgs(classpath: JvmClasspath, entry: String, vmArgs: List<String>, programArgs: List<String>): List<String> = buildList {
    addAll(vmArgs)
    if (classpath.modulePath.isNotEmpty()) { add("--module-path"); add(classpath.modulePath.joinToString(File.pathSeparator)) }
    if (classpath.classPath.isNotEmpty()) { add("-cp"); add(classpath.classPath.joinToString(File.pathSeparator)) }
    if ('/' in entry) {
        val patch = classpath.patchModulePaths.filter { Path.of(it).isDirectory() && !(Path.of(it) / "module-info.class").isRegularFile() }
        if (patch.isNotEmpty()) { add("--patch-module"); add("${entry.substringBefore('/')}=${patch.joinToString(File.pathSeparator)}") }
        add("-m"); add(entry)
    } else add(entry)
    addAll(programArgs)
}

private class JpmsSplit(val mainModule: String?, val modulePath: List<String>, val classPath: List<String>, val patch: List<String>)

/**
 * The JPMS split of [runtime], or `null` for a class-path launch. [entryModule] is the module [javaArgs]'s entry
 * named, or empty. A test run ([ModuleRuntime.includeTests]) is modular when the test outputs hold a descriptor;
 * its main module is `null`.
 */
private fun jpmsSplit(runtime: ModuleRuntime, entryModule: String): JpmsSplit? {
    val byPath = runtime.paths.associateWith { descriptorAt(Path.of(it)) }
    val own = runtime.ownOutputs.firstNotNullOfOrNull { byPath[it] } ?: return null
    val mainModule = if (runtime.includeTests) null else entryModule.ifEmpty { own.name() }
    val finder = ModuleFinder.of(*runtime.paths.map(Path::of).toTypedArray())
    val modular = linkedSetOf<ModuleDescriptor>()
    val queue = ArrayDeque(listOfNotNull(finder.find(mainModule ?: own.name()).orElse(null)?.descriptor()))
    while (queue.isNotEmpty()) {
        val descriptor = queue.removeFirst()
        if (!modular.add(descriptor)) continue
        descriptor.requires().mapNotNull { finder.find(it.name()).orElse(null)?.descriptor() }.forEach(queue::add)
        // A provider is reached through `uses`, not `requires`: without it a ServiceLoader lookup finds nothing.
        descriptor.uses().forEach { service -> finder.findAll().filter { ref -> ref.descriptor().provides().any { it.service() == service } }.forEach { queue.add(it.descriptor()) } }
    }
    val modularNames = modular.map { it.name() }.toSet()
    val modulePath = runtime.paths.filter { byPath[it]?.name() in modularNames }
    if (modulePath.isEmpty()) return null
    val classPath = runtime.paths - modulePath.toSet()
    val patch = if (mainModule == null) emptyList() else runtime.ownOutputs.filter { Path.of(it).isDirectory() && byPath[it] == null }
    return JpmsSplit(mainModule, modulePath, classPath, patch)
}

/**
 * The module descriptor at [path], or `null` when the path holds none: a jar gives its named or automatic
 * descriptor, an exploded module directory its own, a plain output directory nothing. An automatic module joins
 * the module path only when a named module requires it.
 */
private fun descriptorAt(path: Path): ModuleDescriptor? = try {
    ModuleFinder.of(path).findAll().singleOrNull()?.descriptor()
} catch (_: Exception) {
    null
}
