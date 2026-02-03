// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.model.builder;

import com.jetbrains.ls.imports.gradle.model.ModuleSourceSet;
import com.jetbrains.ls.imports.gradle.model.ModuleSourceSets;
import com.jetbrains.ls.imports.gradle.model.impl.ModuleSourceSetImpl;
import com.jetbrains.ls.imports.gradle.model.impl.ModuleSourceSetsImpl;
import org.gradle.api.Project;
import org.gradle.api.file.SourceDirectorySet;
import org.gradle.api.plugins.ExtensionContainer;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.tooling.provider.model.ToolingModelBuilder;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

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
            return new ModuleSourceSetsImpl(readSourceSets(sourceSets));
        }
        return null;
    }

    private static @NonNull Set<@NonNull ModuleSourceSet> readSourceSets(@NonNull SourceSetContainer sourceSets) {
        Set<ModuleSourceSet> result = new HashSet<>();
        for (SourceSet sourceSet : sourceSets) {
            SourceDirectorySet sources = sourceSet.getAllJava();
            SourceDirectorySet resources = sourceSet.getResources();

            Set<String> excludes = new HashSet<>();
            excludes.addAll(sources.getExcludes());
            excludes.addAll(resources.getExcludes());

            result.add(new ModuleSourceSetImpl(
                    sourceSet.getName(),
                    sources.getSrcDirs(),
                    resources.getSrcDirs(),
                    excludes
            ));
        }
        return result;
    }
}
