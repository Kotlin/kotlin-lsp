// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.model.impl;

import com.jetbrains.ls.imports.gradle.model.ExternalModuleDependency;
import com.jetbrains.ls.imports.gradle.model.ExternalModuleDependencySet;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

public final class ExternalModuleDependencySetImpl implements ExternalModuleDependencySet {

    private final @NotNull Set<@NotNull ExternalModuleDependency> dependencies;

    public ExternalModuleDependencySetImpl(@NotNull Set<@NotNull ExternalModuleDependency> dependencies) {
        this.dependencies = dependencies;
    }

    @Override
    public @NotNull Set<ExternalModuleDependency> getDependencies() {
        return dependencies;
    }
}
