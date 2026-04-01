// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.model.impl;

import com.jetbrains.ls.imports.gradle.model.ExternalModuleDependency;
import org.jetbrains.annotations.NotNull;

import java.io.File;

public class ExternalModuleDependencyImpl implements ExternalModuleDependency {

    private final @NotNull String mavenCoordinates;
    private final @NotNull File file;

    public ExternalModuleDependencyImpl(
            @NotNull String mavenCoordinates,
            @NotNull File file
    ) {
        this.mavenCoordinates = mavenCoordinates;
        this.file = file;
    }

    @Override
    public @NotNull String getMavenCoordinates() {
        return mavenCoordinates;
    }

    @Override
    public @NotNull File getFile() {
        return file;
    }
}
