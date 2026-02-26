// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.model.impl;

import com.jetbrains.ls.imports.gradle.model.ModuleSourceSet;
import org.jspecify.annotations.NonNull;

import java.io.File;
import java.util.Set;

@SuppressWarnings("IO_FILE_USAGE")
public final class ModuleSourceSetImpl implements ModuleSourceSet {

    private final @NonNull String name;
    private final @NonNull Set<@NonNull File> sources;
    private final @NonNull Set<@NonNull File> resources;
    private final @NonNull Set<@NonNull String> excludes;
    private final @NonNull Set<@NonNull File> runtimeClasspath;
    private final @NonNull Set<@NonNull File> compileClasspath;
    private final @NonNull Set<@NonNull File> sourceSetOutput;
    private final boolean hasUnresolvedDependencies;

    public ModuleSourceSetImpl(
            @NonNull String name,
            @NonNull Set<@NonNull File> sources,
            @NonNull Set<@NonNull File> resources,
            @NonNull Set<@NonNull String> excludes,
            @NonNull Set<@NonNull File> runtimeClasspath,
            @NonNull Set<@NonNull File> compileClasspath,
            @NonNull Set<@NonNull File> sourceSetOutput,
            boolean hasUnresolvedDependencies
    ) {
        this.name = name;
        this.sources = sources;
        this.resources = resources;
        this.excludes = excludes;
        this.runtimeClasspath = runtimeClasspath;
        this.compileClasspath = compileClasspath;
        this.sourceSetOutput = sourceSetOutput;
        this.hasUnresolvedDependencies = hasUnresolvedDependencies;
    }

    @Override
    public @NonNull String getName() {
        return name;
    }

    @Override
    public @NonNull Set<@NonNull File> getSources() {
        return sources;
    }

    @Override
    public @NonNull Set<@NonNull File> getResources() {
        return resources;
    }

    @Override
    public @NonNull Set<@NonNull String> getExcludes() {
        return excludes;
    }

    @Override
    public @NonNull Set<@NonNull File> getRuntimeClasspath() {
        return runtimeClasspath;
    }

    @Override
    public @NonNull Set<@NonNull File> getCompileClasspath() {
        return compileClasspath;
    }

    @Override
    public @NonNull Set<@NonNull File> getSourceSetOutput() {
        return sourceSetOutput;
    }

    @Override
    public boolean hasUnresolvedDependencies() {
        return hasUnresolvedDependencies;
    }
}
