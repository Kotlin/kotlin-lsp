// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.model.impl;

import com.jetbrains.ls.imports.gradle.model.KotlinModule;
import com.jetbrains.ls.imports.gradle.model.ModuleJavaSettings;
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
    private final @NotNull Set<@NotNull File> outputDirs;
    private final @NotNull Set<@NotNull File> producedArchives;
    private final @NotNull Set<@NotNull String> friendSourceSets;
    private final boolean hasUnresolvedDependencies;
    private final @NotNull ModuleJavaSettings javaSettings;
    private final @Nullable KotlinModule kotlinModule;

    public ModuleSourceSetImpl(
            @NotNull String name,
            @NotNull Set<@NotNull File> sources,
            @NotNull Set<@NotNull File> resources,
            @NotNull Set<@NotNull String> excludes,
            @NotNull Set<@NotNull File> runtimeClasspath,
            @NotNull Set<@NotNull File> compileClasspath,
            @NotNull Set<@NotNull File> outputDirs,
            @NotNull Set<@NotNull File> producedArchives,
            @NotNull Set<@NotNull String> friendSourceSets,
            boolean hasUnresolvedDependencies,
            @NotNull ModuleJavaSettings javaSettings,
            @Nullable KotlinModule kotlinModule
    ) {
        this.name = name;
        this.sources = sources;
        this.resources = resources;
        this.excludes = excludes;
        this.runtimeClasspath = runtimeClasspath;
        this.compileClasspath = compileClasspath;
        this.outputDirs = outputDirs;
        this.producedArchives = producedArchives;
        this.friendSourceSets = friendSourceSets;
        this.hasUnresolvedDependencies = hasUnresolvedDependencies;
        this.javaSettings = javaSettings;
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
    public @NotNull Set<@NotNull File> getOutputDirs() {
        return outputDirs;
    }

    @Override
    public @NotNull Set<@NotNull File> getProducedArchives() {
        return producedArchives;
    }

    @Override
    public @NotNull Set<@NotNull String> getFriendSourceSets() {
        return friendSourceSets;
    }

    @Override
    public boolean hasUnresolvedDependencies() {
        return hasUnresolvedDependencies;
    }

    @Override
    public @NotNull ModuleJavaSettings getJavaSettings() {
        return javaSettings;
    }

    @Override
    public @Nullable KotlinModule getKotlinModule() {
        return kotlinModule;
    }
}
