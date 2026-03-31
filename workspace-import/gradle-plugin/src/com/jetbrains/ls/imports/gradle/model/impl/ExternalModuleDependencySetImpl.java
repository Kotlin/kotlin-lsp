// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.model.impl;

import com.jetbrains.ls.imports.gradle.model.ExternalModuleDependency;
import com.jetbrains.ls.imports.gradle.model.ExternalModuleDependencySet;
import org.jspecify.annotations.NonNull;

import java.util.Set;

public final class ExternalModuleDependencySetImpl implements ExternalModuleDependencySet {

    private final @NonNull Set<@NonNull ExternalModuleDependency> dependencies;

    public ExternalModuleDependencySetImpl(@NonNull Set<@NonNull ExternalModuleDependency> dependencies) {
        this.dependencies = dependencies;
    }

    @Override
    public @NonNull Set<ExternalModuleDependency> getDependencies() {
        return dependencies;
    }
}
