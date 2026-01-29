// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.model;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.Serializable;
import java.util.List;

public interface KotlinCompilerSettings extends Serializable {

    @Nullable String getJvmTarget();

    @NonNull List<@NonNull String> getPluginOptions();

    @NonNull List<@NonNull String> getPluginClasspath();

    @NonNull List<@NonNull String> getCompilerArgs();
}
