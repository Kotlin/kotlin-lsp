// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.model.impl;

import com.jetbrains.ls.imports.gradle.model.ModuleSourceSet;
import com.jetbrains.ls.imports.gradle.model.ModuleSourceSets;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

public final class ModuleSourceSetsImpl implements ModuleSourceSets {

    private final @NotNull Set<@NotNull ModuleSourceSet> sourceSets;
    private final @Nullable String moduleCoordinate;

    public ModuleSourceSetsImpl(@NotNull Set<@NotNull ModuleSourceSet> sourceSets, @Nullable String moduleCoordinate) {
        this.sourceSets = sourceSets;
        this.moduleCoordinate = moduleCoordinate;
    }

    @Override
    public @NotNull Set<@NotNull ModuleSourceSet> getSourceSets() {
        return sourceSets;
    }

    @Override
    public @Nullable String getModuleCoordinate() {
        return moduleCoordinate;
    }
}
