// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.model.builder;

import com.jetbrains.ls.imports.gradle.model.ModuleSourceSet;
import com.jetbrains.ls.imports.gradle.model.ModuleSourceSets;
import com.jetbrains.ls.imports.gradle.model.impl.ModuleSourceSetImpl;
import com.jetbrains.ls.imports.gradle.model.impl.ModuleSourceSetsImpl;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.SourceDirectorySet;
import org.gradle.api.plugins.ExtensionContainer;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.tooling.provider.model.ToolingModelBuilder;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@SuppressWarnings("IO_FILE_USAGE")
public final class ModuleSourceSetsModelBuilder implements ToolingModelBuilder {

    private static final String TARGET_MODEL_NAME = ModuleSourceSets.class.getName();

    @Override
    public boolean canBuild(@NonNull String modelName) {
        return TARGET_MODEL_NAME.equals(modelName);
    }

    @Override
    public @Nullable Object buildAll(@NonNull String modelName, @NonNull Project project) {
        ExtensionContainer extensions = project.getExtensions();
        SourceSetContainer sourceSets = extensions.findByType(SourceSetContainer.class);
        if (sourceSets != null) {
            return new ModuleSourceSetsImpl(readSourceSets(sourceSets, project));
        }
        return null;
    }

    private static @NonNull Set<@NonNull ModuleSourceSet> readSourceSets(
            @NonNull SourceSetContainer sourceSets,
            @NonNull Project project
    ) {
        Set<ModuleSourceSet> result = new HashSet<>();
        TaskContainer taskContainer = project.getTasks();

        for (SourceSet sourceSet : sourceSets) {
            SourceDirectorySet sources = sourceSet.getAllJava();
            SourceDirectorySet resources = sourceSet.getResources();

            Set<String> excludes = new HashSet<>();
            excludes.addAll(sources.getExcludes());
            excludes.addAll(resources.getExcludes());

            String jarTask = sourceSet.getJarTaskName();
            Task packagingTask = taskContainer.findByName(jarTask);
            Set<File> producedArtifacts = new HashSet<>();
            if (packagingTask instanceof Jar) {
                Set<File> producedJars = packagingTask.getOutputs()
                        .getFiles()
                        .getFiles();
                producedArtifacts.addAll(producedJars);
            }
            Set<File> compilationUnits = sourceSet.getOutput().getFiles();
            producedArtifacts.addAll(compilationUnits);

            String sourceSetName = sourceSet.getName();
            Set<File> runtimeDependencies = resolveFileCollectionFiles(sourceSetName, sourceSet.getRuntimeClasspath());
            Set<File> compileDependencies = resolveFileCollectionFiles(sourceSetName, sourceSet.getCompileClasspath());
            result.add(new ModuleSourceSetImpl(
                    sourceSetName,
                    sources.getSrcDirs(),
                    resources.getSrcDirs(),
                    excludes,
                    runtimeDependencies == null ? Collections.emptySet() : runtimeDependencies,
                    compileDependencies == null ? Collections.emptySet() : compileDependencies,
                    producedArtifacts,
                    runtimeDependencies == null || compileDependencies == null
            ));
        }
        return result;
    }

    private static @Nullable Set<@NonNull File> resolveFileCollectionFiles(
            @NonNull String sourceSetName,
            @NonNull FileCollection collection
    ) {
        try {
            return collection.getFiles();
        } catch (Exception e) {
            System.err.println("Unable to resolve a file collection for source set " + sourceSetName + " - " + e.getMessage());
            return null;
        }
    }
}
