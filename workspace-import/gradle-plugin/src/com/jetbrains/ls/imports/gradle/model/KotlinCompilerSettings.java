// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.util.List;

public interface KotlinCompilerSettings extends Serializable {

    @Nullable String getJvmTarget();

    @NotNull List<@NotNull String> getPluginOptions();

    @NotNull List<@NotNull String> getPluginClasspaths();

    @NotNull List<@NotNull String> getCompilerArgs();
}
