// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.model;

import org.jspecify.annotations.NonNull;

import java.io.File;
import java.io.Serializable;
import java.util.Set;

@SuppressWarnings("IO_FILE_USAGE")
public interface ModuleSourceSet extends Serializable {

    @NonNull String getName();

    @NonNull Set<@NonNull File> getSources();

    @NonNull Set<@NonNull File> getResources();

    @NonNull Set<@NonNull String> getExcludes();

    @NonNull Set<@NonNull File> getRuntimeClasspath();

    @NonNull Set<@NonNull File> getCompileClasspath();

    @NonNull Set<@NonNull File> getSourceSetOutput();

    boolean hasUnresolvedDependencies();
}
