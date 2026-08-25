// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.util.Set;

public interface ModuleSourceSets extends Serializable {

    @NotNull Set<@NotNull ModuleSourceSet> getSourceSets();

    /**
     * The project's published Maven coordinate as {@code groupId:artifactId:version}, or {@code null} when the
     * project has no group/version set (and therefore publishes nothing matchable). Used for dependency
     * substitution; mirrors the Maven importer's module coordinate.
     */
    @Nullable String getModuleCoordinate();

}
