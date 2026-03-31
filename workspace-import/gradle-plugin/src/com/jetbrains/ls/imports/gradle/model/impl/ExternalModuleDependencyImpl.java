// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.model.impl;

import com.jetbrains.ls.imports.gradle.model.ExternalModuleDependency;
import org.jspecify.annotations.NonNull;

import java.io.File;

public class ExternalModuleDependencyImpl implements ExternalModuleDependency {

    private final @NonNull String mavenCoordinates;
    private final @NonNull File file;

    public ExternalModuleDependencyImpl(
            @NonNull String mavenCoordinates,
            @NonNull File file
    ) {
        this.mavenCoordinates = mavenCoordinates;
        this.file = file;
    }

    @Override
    public @NonNull String getMavenCoordinates() {
        return mavenCoordinates;
    }

    @Override
    public @NonNull File getFile() {
        return file;
    }
}
