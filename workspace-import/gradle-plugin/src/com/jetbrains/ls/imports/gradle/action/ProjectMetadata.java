// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.action;

import com.jetbrains.ls.imports.gradle.model.KotlinModule;
import org.gradle.tooling.model.idea.IdeaProject;
import org.jspecify.annotations.NonNull;

import java.io.Serializable;
import java.util.Map;

public final class ProjectMetadata implements Serializable {

    private final @NonNull IdeaProject ideaProject;
    private final @NonNull Map<String, KotlinModule> kotlinModules;

    public ProjectMetadata(
            @NonNull IdeaProject ideaProject,
            @NonNull Map<@NonNull String, @NonNull KotlinModule> kotlinModules
    ) {
        this.ideaProject = ideaProject;
        this.kotlinModules = kotlinModules;
    }

    public @NonNull IdeaProject getIdeaProject() {
        return ideaProject;
    }

    public @NonNull Map<@NonNull String, @NonNull KotlinModule> getKotlinModules() {
        return kotlinModules;
    }
}
