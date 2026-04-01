// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.action;

import com.jetbrains.ls.imports.gradle.model.ExternalModuleDependency;
import com.jetbrains.ls.imports.gradle.model.AndroidProject;
import com.jetbrains.ls.imports.gradle.model.KotlinModule;
import com.jetbrains.ls.imports.gradle.model.ModuleSourceSet;
import org.gradle.tooling.model.idea.IdeaProject;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ProjectMetadata implements Serializable {

    private final @NotNull List<? extends IdeaProject> includedProjects;
    private final @NotNull Map<String, KotlinModule> kotlinModules;
    private final @NotNull Map<String, Set<@NotNull ModuleSourceSet>> sourceSets;
    private final @NotNull Map<String, @NotNull Set<ExternalModuleDependency>> moduleDependencies;
    private final @NotNull Map<@NotNull String, @NotNull AndroidProject> androidProjects;

    public ProjectMetadata(
            @NotNull List<? extends IdeaProject> includedProjects,
            @NotNull Map<@NotNull String, @NotNull KotlinModule> kotlinModules,
            @NotNull Map<@NotNull String, @NotNull Set<@NotNull ModuleSourceSet>> sourceSets,
            @NotNull Map<String, @NotNull Set<ExternalModuleDependency>> moduleDependencies,
            @NotNull Map<@NotNull String, @NotNull AndroidProject> androidProjects
    ) {
        this.includedProjects = includedProjects;
        this.kotlinModules = kotlinModules;
        this.sourceSets = sourceSets;
        this.moduleDependencies = moduleDependencies;
        this.androidProjects = androidProjects;
    }

    public @NotNull List<? extends IdeaProject> getIncludedProjects() {
        return includedProjects;
    }

    public @NotNull Map<@NotNull String, @NotNull KotlinModule> getKotlinModules() {
        return kotlinModules;
    }

    public @NotNull Map<@NotNull String, @NotNull Set<@NotNull ModuleSourceSet>> getSourceSets() {
        return sourceSets;
    }

    public @NotNull Map<@NotNull String, @NotNull Set<ExternalModuleDependency>> getModuleDependencies() {
        return moduleDependencies;
    }

    public @NotNull Map<@NotNull String, @NotNull AndroidProject> getAndroidProjects() {
        return androidProjects;
    }
}
