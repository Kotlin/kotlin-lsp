// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.Serializable;
import java.util.Set;

@SuppressWarnings("IO_FILE_USAGE")
public interface ModuleSourceSet extends Serializable {

    @NotNull String getName();

    @NotNull Set<@NotNull File> getSources();

    @NotNull Set<@NotNull File> getResources();

    @NotNull Set<@NotNull String> getExcludes();

    @NotNull Set<@NotNull File> getRuntimeClasspath();

    @NotNull Set<@NotNull File> getCompileClasspath();

    @NotNull Set<@NotNull File> getSourceSetOutput();

    /**
     * @return names of source sets (compilations) which are considered friends
     * 'friends' are allowed to use 'internal' declarations from other models.
     * Kotlin defines such friends using an 'associateWith' declaration between compilations.
     */
    @NotNull Set<@NotNull String> getFriendSourceSets();

    boolean hasUnresolvedDependencies();

    @Nullable Integer getToolchainVersion();

    @Nullable String getSourceCompatibility();

    @Nullable String getTargetCompatibility();

    /**
     * @return A dedicated module if directly associated with the source set.
     * Note: This might return null, relying on a 'project level' KotlinModule to be provided
     */
    @Nullable KotlinModule getKotlinModule();
}
