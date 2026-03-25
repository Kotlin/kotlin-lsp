// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.action;

import com.jetbrains.ls.imports.gradle.model.ExternalModuleDependency;
import com.jetbrains.ls.imports.gradle.model.AndroidProject;
import com.jetbrains.ls.imports.gradle.model.KotlinModule;
import com.jetbrains.ls.imports.gradle.model.ModuleSourceSet;
import org.gradle.tooling.model.idea.IdeaProject;
import org.jspecify.annotations.NonNull;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ProjectMetadata implements Serializable {

    private final @NonNull List<? extends IdeaProject> includedProjects;
    private final @NonNull Map<String, KotlinModule> kotlinModules;
    private final @NonNull Map<String, Set<@NonNull ModuleSourceSet>> sourceSets;
    private final @NonNull Map<String, @NonNull Set<ExternalModuleDependency>> moduleDependencies;
    private final @NonNull Map<@NonNull String, @NonNull AndroidProject> androidProjects;

    public ProjectMetadata(
            @NonNull List<? extends IdeaProject> includedProjects,
            @NonNull Map<@NonNull String, @NonNull KotlinModule> kotlinModules,
            @NonNull Map<@NonNull String, @NonNull Set<@NonNull ModuleSourceSet>> sourceSets,
            @NonNull Map<String, @NonNull Set<ExternalModuleDependency>> moduleDependencies,
            @NonNull Map<@NonNull String, @NonNull AndroidProject> androidProjects
    ) {
        this.includedProjects = includedProjects;
        this.kotlinModules = kotlinModules;
        this.sourceSets = sourceSets;
        this.moduleDependencies = moduleDependencies;
        this.androidProjects = androidProjects;
    }

    public @NonNull List<? extends IdeaProject> getIncludedProjects() {
        return includedProjects;
    }

    public @NonNull Map<@NonNull String, @NonNull KotlinModule> getKotlinModules() {
        return kotlinModules;
    }

    public @NonNull Map<@NonNull String, @NonNull Set<@NonNull ModuleSourceSet>> getSourceSets() {
        return sourceSets;
    }

    public @NonNull Map<@NonNull String, @NonNull Set<ExternalModuleDependency>> getModuleDependencies() {
        return moduleDependencies;
    }

    public @NonNull Map<@NonNull String, @NonNull AndroidProject> getAndroidProjects() {
        return androidProjects;
    }
}
