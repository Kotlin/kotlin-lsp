// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.buildtool.api

import com.intellij.openapi.util.NlsSafe
import com.intellij.util.system.OS
import org.jetbrains.annotations.Nls
import java.nio.file.Path
import kotlin.io.path.isRegularFile

/**
 * Inputs describing what to import, bundled into one type so new fields can be added later without
 * changing every `WorkspaceImporter` implementation's signature.
 *
 * @property projectFileOrDirectory the workspace/project root to import, or the build file to import from
 *   (importers that cannot build from a file use [projectDirectory])
 * @property defaultSdkPath SDK to use for a project that does not configure its own
 * @property options per-project overrides from a configured project (env / system properties / java home / project path)
 *
 */
data class WorkspaceImportParameters(
    val projectFileOrDirectory: Path,
    val defaultSdkPath: Path?,
    val options: WorkspaceImportOptions = WorkspaceImportOptions.EMPTY,
) {
    /** The project directory: [projectFileOrDirectory] itself, or its parent when it is a build file. */
    val projectDirectory: Path
        get() = if (projectFileOrDirectory.isRegularFile()) (projectFileOrDirectory.parent ?: projectFileOrDirectory)
                else projectFileOrDirectory
}

/**
 * Per-project overrides taken from an externally configured project (see `InitializeOptions.projects`).
 * Empty for auto-detected workspace folders. `environment`/`systemProperties` are honored by the Maven importer;
 * `javaHome` is honored by both the Maven and Gradle importers; other importers ignore these.
 *
 * @property environment extra environment variables for the import process
 * @property systemProperties JVM/`-D` system properties for the import process
 * @property javaHome JDK home used to run the import (overrides the ambient/default JDK)
 * @property projectPath Bazel only: path to the project view file, relative to
 *   [WorkspaceImportParameters.projectDirectory]
 * @property profiles Maven only: build profiles to activate during the import, passed as `-P`
 * @property downloadAdditionalArtifacts whether the import may download the sources and the documentation of the
 *   dependencies: Maven resolves the `sources` and `javadoc` classifiers, and Gradle asks for the full dependency
 *   model. The other importers ignore it: Bazel and Go get their sources from the build itself, and JPS downloads
 *   only what a library root points at.
 * @property offline whether the import uses only the already downloaded artifacts: Maven runs with `-o`,
 *   Gradle with `--offline`, Go with `GOPROXY=off`, Bazel with `--nofetch`, and JPS skips the Maven download
 *   of a missing library. The JSON importer reads a local file only, so it ignores the flag.
 * @property skipGenerateSources Maven only: whether the import leaves out the second Maven run, the one that executes
 *   the code generators (`model-process-sources`) and writes into the `target/` directories of the project. `null`
 *   means the entry says nothing, and the import cycle fills in the session's `skipMavenGenerateSources`. The other
 *   importers ignore it.
 */
data class WorkspaceImportOptions(
    val environment: Map<String, String> = emptyMap(),
    val systemProperties: Map<String, String> = emptyMap(),
    val javaHome: Path? = null,
    val projectPath: String? = null,
    val profiles: List<String> = emptyList(),
    val downloadAdditionalArtifacts: Boolean = true,
    val offline: Boolean = false,
    val skipGenerateSources: Boolean? = null,
) {
    companion object {
        val EMPTY: WorkspaceImportOptions = WorkspaceImportOptions()

        /**
         * Default environment layered under every import, expressed with `${'$'}{env.VAR}` macros so the value is
         * taken from the analyzer's own environment. Importers that start the build with a cleared environment
         * (e.g. Maven) rely on this for anything the child still needs to behave like a normal process.
         *
         * The list is a superset across OSes: variables not present in the current environment resolve to empty
         * and are dropped in [withResolvedEnvironment], so the Windows-only and Unix-only entries coexist here.
         * Kept minimal on purpose - only what a build tool / its wrapper script realistically needs:
         * `PATH` to find tools, the temp dir (Maven wrapper's `New-TemporaryFile`/`GetTempFileName` failed without
         * it), the home dir (caches, `~/.m2`, `~/.gradle`), locale, and the core Windows system vars that the
         * PowerShell/.NET runtime itself reads.
         */
        val DEFAULT_ENVIRONMENT: Map<String, String> = listOf(
            // Common / Unix
            "PATH",
            "HOME",
            "USER",
            "LOGNAME",
            "LANG",
            "LC_ALL",
            "LC_CTYPE",
            "TMPDIR",
            // Windows
            "SystemRoot",
            "SystemDrive",
            "windir",
            "ComSpec",
            "PATHEXT",
            "TEMP",
            "TMP",
            "USERPROFILE",
            "HOMEDRIVE",
            "HOMEPATH",
            "APPDATA",
            "LOCALAPPDATA",
            "PROCESSOR_ARCHITECTURE",
            "NUMBER_OF_PROCESSORS",
        ).associateWith { "\${env.$it}" }

        private val ENV_MACRO = Regex("""\$\{env\.([^}]+)}""")
    }

    /**
     * Layers [DEFAULT_ENVIRONMENT] under the client-provided [environment] (client wins) and expands `${'$'}{env.VAR}`
     * macros from [systemEnvironment] (missing vars expand to empty). Applied at a common level so every importer
     * gets the same treatment. [systemEnvironment] is injectable for testing; production passes the real env.
     */
    fun withResolvedEnvironment(systemEnvironment: Map<String, String> = System.getenv()): WorkspaceImportOptions {
        // Windows env var names are case-insensitive (`PATH` is stored as `Path`), so both the macro lookup and the
        // default-vs-client merge must ignore case, or `${'$'}{env.PATH}` resolves to empty and the child loses its PATH.
        fun lookup(name: String): String =
            systemEnvironment[name] ?: systemEnvironment.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value ?: ""

        fun expand(value: String) = ENV_MACRO.replace(value) { m -> lookup(m.groupValues[1]) }

        // Defaults absent from the current OS resolve to empty (e.g. `TMPDIR` on Windows, `SystemRoot` on Unix);
        // drop those so we never inject a blank var. Client-provided values are kept as-is, even if empty.
        val defaultsToAdd = DEFAULT_ENVIRONMENT
            .filterKeys { def -> environment.keys.none { it.equals(def, ignoreCase = true) } }
            .mapValues { (_, value) -> expand(value) }
            .filterValues { it.isNotEmpty() }
        val resolvedClient = environment.mapValues { (_, value) -> expand(value) }
        return copy(environment = defaultsToAdd + resolvedClient)
    }
}

/**
 * The value of the environment variable [name] in this environment. On Windows variable names are case-insensitive,
 * so a key that differs only in case (`Java_Home` for `JAVA_HOME`) matches too. On other systems it is a different
 * variable. [WorkspaceImportOptions.withResolvedEnvironment] keeps the client's spelling, so importers read the
 * environment through this function.
 */
fun Map<String, String>.environmentVariable(name: String, os: OS = OS.CURRENT): String? {
    this[name]?.let { return it }
    if (os != OS.Windows) return null
    return entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/**
 * Puts [overrides] into a process environment. On Windows a name that differs only in case is replaced, not added:
 * the child process sees one value per name there, and the JDK environment map does not merge the spellings.
 */
fun MutableMap<String, String>.putEnvironment(overrides: Map<String, String>, os: OS = OS.CURRENT) {
    for ((name, value) in overrides) {
        if (os == OS.Windows) keys.removeAll { it.equals(name, ignoreCase = true) }
        this[name] = value
    }
}

/**
 * The import failed because of the user's project or environment. We cannot prevent it: a broken
 * buildscript, a missing JDK, a build that returns a non-zero exit code.
 */
class WorkspaceImportException(
    displayMessage: @Nls String,
    val logMessage: @NlsSafe String?,
    cause: Throwable? = null
) : Exception(displayMessage, cause)

/**
 * The import failed because of our own code or action during the import: a parse of a file we produce,
 * an unsupported case we did not handle. We must report and fix these.
 */
class WorkspaceException(
    displayMessage: @NlsSafe String,
    val logMessage: @NlsSafe String?,
    cause: Throwable? = null
) : Exception(displayMessage, cause)

