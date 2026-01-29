// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.model.impl;

import com.jetbrains.ls.imports.gradle.model.KotlinCompilerSettings;
import com.jetbrains.ls.imports.gradle.model.KotlinModule;
import org.jspecify.annotations.NonNull;

public class KotlinModuleImpl implements KotlinModule {

    private final @NonNull KotlinCompilerSettings compilerSettings;

    public KotlinModuleImpl(@NonNull KotlinCompilerSettings compilerSettings) {
        this.compilerSettings = compilerSettings;
    }

    @Override
    public @NonNull KotlinCompilerSettings getCompilerSettings() {
        return compilerSettings;
    }
}
