// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.model.impl;

import com.jetbrains.ls.imports.gradle.model.KotlinModule;
import com.jetbrains.ls.imports.gradle.model.ModuleSourceSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Set;

@SuppressWarnings("IO_FILE_USAGE")
public final class ModuleSourceSetImpl implements ModuleSourceSet {

    private final @NotNull String name;
    private final @NotNull Set<@NotNull File> sources;
    private final @NotNull Set<@NotNull File> resources;
    private final @NotNull Set<@NotNull String> excludes;
    private final @NotNull Set<@NotNull File> runtimeClasspath;
    private final @NotNull Set<@NotNull File> compileClasspath;
    private final @NotNull Set<@NotNull File> sourceSetOutput;
    private final boolean hasUnresolvedDependencies;
    private final @Nullable Integer toolchainVersion;
    private final @Nullable String sourceCompatibility;
    private final @Nullable String targetCompatibility;
    private final @Nullable KotlinModule kotlinModule;

    public ModuleSourceSetImpl(
            @NotNull String name,
            @NotNull Set<@NotNull File> sources,
            @NotNull Set<@NotNull File> resources,
            @NotNull Set<@NotNull String> excludes,
            @NotNull Set<@NotNull File> runtimeClasspath,
            @NotNull Set<@NotNull File> compileClasspath,
            @NotNull Set<@NotNull File> sourceSetOutput,
            boolean hasUnresolvedDependencies,
            @Nullable Integer toolchainVersion,
            @Nullable String sourceCompatibility,
            @Nullable String targetCompatibility,
            @Nullable KotlinModule kotlinModule
    ) {
        this.name = name;
        this.sources = sources;
        this.resources = resources;
        this.excludes = excludes;
        this.runtimeClasspath = runtimeClasspath;
        this.compileClasspath = compileClasspath;
        this.sourceSetOutput = sourceSetOutput;
        this.hasUnresolvedDependencies = hasUnresolvedDependencies;
        this.toolchainVersion = toolchainVersion;
        this.sourceCompatibility = sourceCompatibility;
        this.targetCompatibility = targetCompatibility;
        this.kotlinModule = kotlinModule;
    }

    @Override
    public @NotNull String getName() {
        return name;
    }

    @Override
    public @NotNull Set<@NotNull File> getSources() {
        return sources;
    }

    @Override
    public @NotNull Set<@NotNull File> getResources() {
        return resources;
    }

    @Override
    public @NotNull Set<@NotNull String> getExcludes() {
        return excludes;
    }

    @Override
    public @NotNull Set<@NotNull File> getRuntimeClasspath() {
        return runtimeClasspath;
    }

    @Override
    public @NotNull Set<@NotNull File> getCompileClasspath() {
        return compileClasspath;
    }

    @Override
    public @NotNull Set<@NotNull File> getSourceSetOutput() {
        return sourceSetOutput;
    }

    @Override
    public boolean hasUnresolvedDependencies() {
        return hasUnresolvedDependencies;
    }

    @Override
    public @Nullable Integer getToolchainVersion() {
        return toolchainVersion;
    }

    @Override
    public @Nullable String getSourceCompatibility() {
        return sourceCompatibility;
    }

    @Override
    public @Nullable String getTargetCompatibility() {
        return targetCompatibility;
    }

    @Override
    public @Nullable KotlinModule getKotlinModule() {
        return kotlinModule;
    }
}
