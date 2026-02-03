// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.model.impl;

import com.jetbrains.ls.imports.gradle.model.ModuleSourceSet;
import org.jspecify.annotations.NonNull;

import java.io.File;
import java.util.Set;

public final class ModuleSourceSetImpl implements ModuleSourceSet {

    private final @NonNull String name;
    private final @NonNull Set<@NonNull File> sources;
    private final @NonNull Set<@NonNull File> resources;
    private final @NonNull Set<@NonNull String> excludes;

    public ModuleSourceSetImpl(
            @NonNull String name,
            @NonNull Set<@NonNull File> sources,
            @NonNull Set<@NonNull File> resources,
            @NonNull Set<@NonNull String> excludes
    ) {
        this.name = name;
        this.sources = sources;
        this.resources = resources;
        this.excludes = excludes;
    }

    @Override
    public @NonNull String getName() {
        return name;
    }

    @Override
    public @NonNull Set<File> getSources() {
        return sources;
    }

    @Override
    public @NonNull Set<File> getResources() {
        return resources;
    }

    @Override
    public @NonNull Set<String> getExcludes() {
        return excludes;
    }
}
