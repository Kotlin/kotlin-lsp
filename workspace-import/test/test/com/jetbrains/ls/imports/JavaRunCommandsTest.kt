// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

import com.intellij.java.workspace.entities.JavaModuleSettingsEntity
import com.intellij.java.workspace.entities.javaSettings
import com.intellij.platform.workspace.jps.entities.DependencyScope
import com.intellij.platform.workspace.jps.entities.LibraryDependency
import com.intellij.platform.workspace.jps.entities.LibraryEntity
import com.intellij.platform.workspace.jps.entities.LibraryId
import com.intellij.platform.workspace.jps.entities.LibraryRoot
import com.intellij.platform.workspace.jps.entities.LibraryRootTypeId
import com.intellij.platform.workspace.jps.entities.LibraryTableId
import com.intellij.platform.workspace.jps.entities.ModuleDependency
import com.intellij.platform.workspace.jps.entities.ModuleDependencyItem
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.jps.entities.ModuleId
import com.intellij.platform.workspace.jps.entities.SdkDependency
import com.intellij.platform.workspace.jps.entities.SdkEntity
import com.intellij.platform.workspace.jps.entities.SdkId
import com.intellij.platform.workspace.storage.EntitySource
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.impl.url.VirtualFileUrlManagerImpl
import com.jetbrains.ls.api.core.launch.JvmClasspath
import com.jetbrains.ls.imports.java.argFileContent
import com.jetbrains.ls.imports.java.explicitJavaArgs
import com.jetbrains.ls.imports.java.javaArgs
import com.jetbrains.ls.imports.java.javaArgsOrArgFile
import com.jetbrains.ls.imports.api.ModuleRuntime
import com.jetbrains.ls.imports.api.moduleRuntime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.writeText

class JavaRunCommandsTest {
    private val source = object : EntitySource {}
    private val urls = VirtualFileUrlManagerImpl()
    private val sep = File.pathSeparator

    /**
     * `app` depends on `lib` and on `junit` (TEST); `lib` depends on `guava`. Every module has an output and a
     * test output; `app` has the JDK. [previewOn] names the module that compiles at a preview language level.
     */
    private fun storage(previewOn: String? = "app"): MutableEntityStorage {
        val storage = MutableEntityStorage.create()
        storage.addEntity(SdkEntity("17", "JavaSDK", emptyList(), "", source) { homePath = urls.storeAndGet("file:///jdk") })
        fun library(name: String, jar: String) = storage.addEntity(
            LibraryEntity(name, LibraryTableId.ProjectLibraryTableId, listOf(LibraryRoot(urls.storeAndGet("jar://$jar!/"), LibraryRootTypeId.COMPILED)), source)
        )
        library("guava", "/m2/guava.jar")
        library("junit", "/m2/junit.jar")
        fun module(name: String, deps: List<ModuleDependencyItem>, level: String? = null) = storage.addEntity(ModuleEntity(name, deps, source) {
            javaSettings = JavaModuleSettingsEntity(inheritedCompilerOutput = false, excludeOutput = true, entitySource = source) {
                compilerOutput = urls.storeAndGet("file:///out/$name")
                compilerOutputForTests = urls.storeAndGet("file:///out/$name-test")
                languageLevelId = level
            }
        })
        module(
            "lib",
            listOf(LibraryDependency(LibraryId("guava", LibraryTableId.ProjectLibraryTableId), false, DependencyScope.COMPILE)),
            level = "JDK_21_PREVIEW".takeIf { previewOn == "lib" },
        )
        module(
            "app",
            listOf(
                SdkDependency(SdkId("17", "JavaSDK")),
                ModuleDependency(ModuleId("lib"), false, DependencyScope.COMPILE, false),
                LibraryDependency(LibraryId("junit", LibraryTableId.ProjectLibraryTableId), false, DependencyScope.TEST),
            ),
            level = "JDK_21_PREVIEW".takeIf { previewOn == "app" },
        )
        return storage
    }

    @Test
    fun `the production runtime has the outputs of the closure and no test entries`() {
        val storage = storage()
        val runtime = moduleRuntime(storage, storage.resolve(ModuleId("app"))!!, includeTests = false)
        assertEquals(listOf("/out/app", "/out/lib", "/m2/guava.jar"), runtime.paths)
        assertEquals(listOf("/out/app"), runtime.ownOutputs)
        assertEquals(Path.of("/jdk"), runtime.javaHome)
        assertTrue(runtime.previewFeatures)
    }

    @Test
    fun `a preview dependency enables preview for the launch`() {
        val storage = storage(previewOn = "lib")
        val runtime = moduleRuntime(storage, storage.resolve(ModuleId("app"))!!, includeTests = false)
        assertTrue(runtime.previewFeatures) { "the JVM loads lib's classes, so the launch needs --enable-preview" }
    }

    @Test
    fun `no preview code means no preview flag`() {
        val storage = storage(previewOn = null)
        val runtime = moduleRuntime(storage, storage.resolve(ModuleId("app"))!!, includeTests = false)
        assertFalse(runtime.previewFeatures)
        assertFalse("--enable-preview" in javaArgs(runtime, "com.acme.Main", emptyList(), emptyList()))
    }

    @Test
    fun `the test runtime adds the test output of the module itself and the TEST dependencies`() {
        val storage = storage()
        val runtime = moduleRuntime(storage, storage.resolve(ModuleId("app"))!!, includeTests = true)
        assertEquals(listOf("/out/app", "/out/app-test", "/out/lib", "/m2/guava.jar", "/m2/junit.jar"), runtime.paths)
    }

    @Test
    fun `without a module descriptor the program runs on the class path`() {
        val storage = storage()
        val runtime = moduleRuntime(storage, storage.resolve(ModuleId("app"))!!, includeTests = false)
        val args = javaArgs(runtime, "com.acme.Main", vmArgs = listOf("-Xmx1g"), programArgs = listOf("--port", "8080"))
        assertEquals(
            listOf("-Xmx1g", "--enable-preview", "-cp", "/out/app$sep/out/lib$sep/m2/guava.jar", "com.acme.Main", "--port", "8080"),
            args,
        )
        // An entry that names a module falls back to the class too when the outputs hold no descriptor.
        assertTrue("com.acme.Main" in javaArgs(runtime, "acme.app/com.acme.Main", emptyList(), emptyList()))
        assertFalse(javaArgs(runtime, "com.acme.Main", listOf("--enable-preview"), emptyList()).count { it == "--enable-preview" } > 1)
    }

    private fun jpmsRuntime(paths: List<Path>, ownOutputs: List<Path>, includeTests: Boolean = false): ModuleRuntime {
        val storage = storage()
        return ModuleRuntime(
            module = storage.resolve(ModuleId("app"))!!,
            includeTests = includeTests,
            paths = paths.map { it.toString() },
            ownOutputs = ownOutputs.map { it.toString() },
            javaHome = null,
            workingDirectory = null,
            previewFeatures = false,
        )
    }

    @Test
    fun `a module descriptor splits the runtime between the module path and the class path`() {
        val f = JpmsFixture
        val runtime = jpmsRuntime(paths = listOf(f.app, f.lib, f.plain), ownOutputs = listOf(f.app))
        assertEquals(
            listOf("--module-path", "${f.app}$sep${f.lib}", "-cp", "${f.plain}", "-m", "acme.app/com.acme.Main"),
            javaArgs(runtime, "com.acme.Main", emptyList(), emptyList()),
        )
    }

    @Test
    fun `the resources output is patched into the main module`() {
        val f = JpmsFixture
        val runtime = jpmsRuntime(paths = listOf(f.app, f.resources, f.lib), ownOutputs = listOf(f.app, f.resources))
        assertEquals(
            listOf(
                "--module-path", "${f.app}$sep${f.lib}",
                "-cp", "${f.resources}",
                "--patch-module", "acme.app=${f.resources}",
                "-m", "acme.app/com.acme.Main",
            ),
            javaArgs(runtime, "com.acme.Main", emptyList(), emptyList()),
        )
    }

    @Test
    fun `a modular test run has no main module and joins through add-modules`() {
        val f = JpmsFixture
        val runtime = jpmsRuntime(paths = listOf(f.app, f.lib), ownOutputs = listOf(f.app), includeTests = true)
        assertEquals(
            listOf("--module-path", "${f.app}$sep${f.lib}", "--add-modules=ALL-MODULE-PATH", "com.acme.MainTest"),
            javaArgs(runtime, "com.acme.MainTest", emptyList(), emptyList()),
        )
    }

    /** A provider is reached through `uses`, not `requires`: without it a ServiceLoader lookup finds nothing. */
    @Test
    fun `a service provider of the closure joins the module path`() {
        val f = JpmsFixture
        val runtime = jpmsRuntime(paths = listOf(f.app, f.lib, f.impl), ownOutputs = listOf(f.app))
        val args = javaArgs(runtime, "com.acme.Main", emptyList(), emptyList())
        assertEquals("${f.app}$sep${f.lib}$sep${f.impl}", args[args.indexOf("--module-path") + 1])
        assertFalse("-cp" in args)
    }

    @Test
    fun `short arguments stay inline and long ones go into one argfile`() {
        val short = listOf("-cp", "/x/a.jar", "com.acme.Main")
        assertEquals(short, javaArgsOrArgFile(short, argFileOf = { error("not expected") }, windows = false))

        val long = listOf("-cp", (1..5000).joinToString(sep) { "/jars/library-$it.jar" }, "com.acme.Main", "--port")
        var written: String? = null
        val args = javaArgsOrArgFile(long, argFileOf = { written = it; Path.of("/tmp/run.args") }, windows = false)
        assertEquals(listOf("@${Path.of("/tmp/run.args")}"), args)
        assertEquals(argFileContent(long), written)
    }

    @Test
    fun `the argfile quotes what its syntax would misread`() {
        val content = argFileContent(listOf("-cp", "/x/plain.jar", "/with space/a.jar", "C:\\win\\b.jar", "say \"hi\""))
        assertEquals(
            "-cp\n/x/plain.jar\n\"/with space/a.jar\"\n\"C:\\\\win\\\\b.jar\"\n\"say \\\"hi\\\"\"\n",
            content,
        )
    }

    @Test
    fun `an explicit class path is used as spelled`() {
        val explicit = JvmClasspath(classPath = listOf("/x/a.jar", "/x/classes"), modulePath = listOf("/x/mods"))
        assertEquals(
            listOf("-Xmx1g", "--module-path", "/x/mods", "-cp", "/x/a.jar$sep/x/classes", "com.acme.Main", "--port"),
            explicitJavaArgs(explicit, "com.acme.Main", vmArgs = listOf("-Xmx1g"), programArgs = listOf("--port")),
        )
        assertEquals(
            listOf("-cp", "/x/a.jar", "-m", "acme.app/com.acme.Main"),
            explicitJavaArgs(JvmClasspath(classPath = listOf("/x/a.jar")), "acme.app/com.acme.Main", emptyList(), emptyList()),
        )
    }
}

/**
 * Compiled JPMS modules the split reads from disk: `acme.app` requires `acme.lib` and uses `com.acme.Spi`;
 * `acme.impl` provides that service. `plain` and `resources` are directories without a descriptor. Compiled once
 * for the whole class; the sandbox removes the temp directory.
 */
private object JpmsFixture {
    private val dir: Path = Files.createTempDirectory("jpms")
    val lib: Path = dir / "lib"
    val app: Path = dir / "app"
    val impl: Path = dir / "impl"
    val plain: Path = (dir / "plain").createDirectories()
    val resources: Path = (dir / "resources").createDirectories()

    init {
        compile(lib, mapOf("module-info.java" to "module acme.lib {}"))
        compile(
            app,
            mapOf(
                "module-info.java" to "module acme.app { requires acme.lib; exports com.acme; uses com.acme.Spi; }",
                "com/acme/Spi.java" to "package com.acme; public interface Spi {}",
            ),
            modulePath = listOf(lib),
        )
        compile(
            impl,
            mapOf(
                "module-info.java" to "module acme.impl { requires acme.app; provides com.acme.Spi with com.acme.impl.Impl; }",
                "com/acme/impl/Impl.java" to "package com.acme.impl; public class Impl implements com.acme.Spi {}",
            ),
            modulePath = listOf(lib, app),
        )
    }

    private fun compile(out: Path, sources: Map<String, String>, modulePath: List<Path> = emptyList()) {
        val src = Files.createTempDirectory("jpms-src")
        val files = sources.map { (relative, text) -> (src / relative).apply { parent.createDirectories(); writeText(text) } }
        val args = buildList {
            add("-d"); add(out.toString())
            if (modulePath.isNotEmpty()) { add("--module-path"); add(modulePath.joinToString(File.pathSeparator)) }
            files.forEach { add(it.toString()) }
        }
        check(ToolProvider.getSystemJavaCompiler().run(null, null, null, *args.toTypedArray()) == 0) { "javac failed for $out" }
    }
}
