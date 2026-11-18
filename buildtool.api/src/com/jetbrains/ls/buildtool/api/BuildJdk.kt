// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.buildtool.api

/**
 * The JDK a build tool hands to the build ([home], a path as the tool got it) and where the choice came from.
 * Not always the JVM the build runs on: a project wrapper may replace it ([BuildJdkSource.WRAPPER]).
 */
data class BuildJdk(val home: String, val source: BuildJdkSource)

enum class BuildJdkSource {
    /** The `java-home` configured for the project. */
    PROJECT_JAVA_HOME,

    /** A JVM property of the server process: `JB_MAVEN_JAVA_HOME`, or the Gradle one. */
    PROPERTY,

    /** `JAVA_HOME` in the `env` configured for the project. */
    PROJECT_ENV,

    /** `JAVA_HOME` of the server process. */
    AMBIENT_ENV,

    /** Found by a search for a JDK the build tool supports. */
    DETECTED,

    /** The server's own JVM: the last fallback, which lives in a versioned server directory. */
    SERVER_JVM,

    /** The project's wrapper starts the build and may replace the JDK it was handed. */
    WRAPPER,
}
