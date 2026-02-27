// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.model.impl;

import com.jetbrains.ls.imports.gradle.model.KotlinCompilerSettings;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;

public final class KotlinCompilerSettingsImpl implements KotlinCompilerSettings {

    private final @Nullable String jvmTarget;
    private final @NonNull List<@NonNull String> pluginOptions;
    private final @NonNull List<@NonNull String> pluginClasspath;
    private final @NonNull List<@NonNull String> compilerArgs;

    public KotlinCompilerSettingsImpl(
            @Nullable String target,
            @NonNull List<@NonNull String> pluginOptions,
            @NonNull List<@NonNull String> pluginClasspath,
            @NonNull List<@NonNull String> compilerArgs
    ) {
        this.jvmTarget = target;
        this.pluginOptions = pluginOptions;
        this.pluginClasspath = pluginClasspath;
        this.compilerArgs = compilerArgs;
    }

    @Override
    public @Nullable String getJvmTarget() {
        return jvmTarget;
    }

    @Override
    public @NonNull List<@NonNull String> getPluginOptions() {
        return pluginOptions;
    }

    @Override
    public @NonNull List<@NonNull String> getPluginClasspaths() {
        return pluginClasspath;
    }

    @Override
    public @NonNull List<@NonNull String> getCompilerArgs() {
        return compilerArgs;
    }
}
