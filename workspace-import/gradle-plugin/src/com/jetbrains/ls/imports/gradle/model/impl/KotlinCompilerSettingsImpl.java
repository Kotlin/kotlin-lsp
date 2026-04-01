// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.model.impl;

import com.jetbrains.ls.imports.gradle.model.KotlinCompilerSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class KotlinCompilerSettingsImpl implements KotlinCompilerSettings {

    private final @Nullable String jvmTarget;
    private final @NotNull List<@NotNull String> pluginOptions;
    private final @NotNull List<@NotNull String> pluginClasspath;
    private final @NotNull List<@NotNull String> compilerArgs;

    public KotlinCompilerSettingsImpl(
            @Nullable String target,
            @NotNull List<@NotNull String> pluginOptions,
            @NotNull List<@NotNull String> pluginClasspath,
            @NotNull List<@NotNull String> compilerArgs
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
    public @NotNull List<@NotNull String> getPluginOptions() {
        return pluginOptions;
    }

    @Override
    public @NotNull List<@NotNull String> getPluginClasspaths() {
        return pluginClasspath;
    }

    @Override
    public @NotNull List<@NotNull String> getCompilerArgs() {
        return compilerArgs;
    }
}
