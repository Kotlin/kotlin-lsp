// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.model.impl;

import com.jetbrains.ls.imports.gradle.model.KotlinCompilerSettings;
import com.jetbrains.ls.imports.gradle.model.KotlinModule;
import org.jetbrains.annotations.NotNull;

public class KotlinModuleImpl implements KotlinModule {

    private final @NotNull KotlinCompilerSettings compilerSettings;

    public KotlinModuleImpl(@NotNull KotlinCompilerSettings compilerSettings) {
        this.compilerSettings = compilerSettings;
    }

    @Override
    public @NotNull KotlinCompilerSettings getCompilerSettings() {
        return compilerSettings;
    }
}
