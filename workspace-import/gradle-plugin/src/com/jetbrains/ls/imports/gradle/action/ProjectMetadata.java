// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.action;

import com.jetbrains.ls.imports.gradle.model.KotlinModule;
import com.jetbrains.ls.imports.gradle.model.ModuleSourceSet;
import org.gradle.tooling.model.idea.IdeaProject;
import org.jspecify.annotations.NonNull;

import java.io.Serializable;
import java.util.Map;
import java.util.Set;

public final class ProjectMetadata implements Serializable {

    private final @NonNull IdeaProject ideaProject;
    private final @NonNull Map<String, KotlinModule> kotlinModules;
    private final @NonNull Map<String, Set<@NonNull ModuleSourceSet>> sourceSets;

    public ProjectMetadata(
            @NonNull IdeaProject ideaProject,
            @NonNull Map<@NonNull String, @NonNull KotlinModule> kotlinModules,
            @NonNull Map<@NonNull String, @NonNull Set<@NonNull ModuleSourceSet>> sourceSets
    ) {
        this.ideaProject = ideaProject;
        this.kotlinModules = kotlinModules;
        this.sourceSets = sourceSets;
    }

    public @NonNull IdeaProject getIdeaProject() {
        return ideaProject;
    }

    public @NonNull Map<@NonNull String, @NonNull KotlinModule> getKotlinModules() {
        return kotlinModules;
    }

    public @NonNull Map<@NonNull String, @NonNull Set<@NonNull ModuleSourceSet>> getSourceSets() {
        return sourceSets;
    }
}
