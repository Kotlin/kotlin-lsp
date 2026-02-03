// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.model.impl;

import com.jetbrains.ls.imports.gradle.model.ModuleSourceSet;
import com.jetbrains.ls.imports.gradle.model.ModuleSourceSets;
import org.jspecify.annotations.NonNull;

import java.util.Set;

public final class ModuleSourceSetsImpl implements ModuleSourceSets {

    private final @NonNull Set<@NonNull ModuleSourceSet> sourceSets;

    public ModuleSourceSetsImpl(@NonNull Set<@NonNull ModuleSourceSet> sourceSets) {
        this.sourceSets = sourceSets;
    }

    @Override
    public @NonNull Set<@NonNull ModuleSourceSet> getSourceSets() {
        return sourceSets;
    }
}
