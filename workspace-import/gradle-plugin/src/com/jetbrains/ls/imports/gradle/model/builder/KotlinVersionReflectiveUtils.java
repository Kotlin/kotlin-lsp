// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.model.builder;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Utils for reflective access to {@code org.jetbrains.kotlin.gradle.dsl.KotlinVersion}.
 * <p>
 * {@code org.jetbrains.kotlin.gradle.dsl.KotlinVersion} is loaded from the build's classpath and is not available to this module at compile time,
 * so all access goes through reflection.
 */
final class KotlinVersionReflectiveUtils {

    private static final String KOTLIN_VERSION_CLASS_NAME = "org.jetbrains.kotlin.gradle.dsl.KotlinVersion";
    private static final String GET_DEFAULT_METHOD_NAME = "getDEFAULT";
    private static final String GET_VERSION_METHOD_NAME = "getVersion";

    private KotlinVersionReflectiveUtils() {
    }

    /**
     * Returns the {@code KotlinVersion.DEFAULT} instance via reflection with the provided build classloader,
     * or {@code null} if unavailable.
     */
    static @Nullable Object getDefaultVersion(@Nullable ClassLoader classLoader) {
        try {
            Class<?> kotlinVersionClass = getKotlinVersionClass(classLoader);
            return kotlinVersionClass.getMethod(GET_DEFAULT_METHOD_NAME).invoke(null);
        } catch (Throwable ignore) {
            return null;
        }
    }

    /**
     * Extracts the version string (e.g. {@code "2.2"}) from a {@code KotlinVersion} instance loaded by the provided build classloader.
     */
    static @Nullable String getVersionString(@NotNull Object kotlinVersion, @Nullable ClassLoader classLoader) {
        try {
            Class<?> kotlinVersionClass = getKotlinVersionClass(classLoader);

            if (!kotlinVersionClass.isInstance(kotlinVersion)) {
                return null;
            }

            Object version = kotlinVersionClass.getMethod(GET_VERSION_METHOD_NAME).invoke(kotlinVersion);

            return version != null ? version.toString() : null;
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static @NotNull Class<?> getKotlinVersionClass(@Nullable ClassLoader classLoader) throws ClassNotFoundException {
        return Class.forName(KOTLIN_VERSION_CLASS_NAME, false, classLoader);
    }
}
