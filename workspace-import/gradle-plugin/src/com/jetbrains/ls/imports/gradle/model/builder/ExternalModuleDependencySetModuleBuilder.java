// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.model.builder;

import com.jetbrains.ls.imports.gradle.model.ExternalModuleDependency;
import com.jetbrains.ls.imports.gradle.model.ExternalModuleDependencySet;
import com.jetbrains.ls.imports.gradle.model.builder.android.AndroidUtilsKt;
import com.jetbrains.ls.imports.gradle.model.impl.ExternalModuleDependencyImpl;
import com.jetbrains.ls.imports.gradle.model.impl.ExternalModuleDependencySetImpl;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ArtifactView;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.artifacts.type.ArtifactTypeDefinition;
import org.gradle.api.attributes.Attribute;
import org.gradle.tooling.provider.model.ToolingModelBuilder;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class ExternalModuleDependencySetModuleBuilder implements ToolingModelBuilder {

    private static final @NonNull String MODEL_NAME = ExternalModuleDependencySet.class.getCanonicalName();

    @Override
    public boolean canBuild(@NonNull String modelName) {
        return MODEL_NAME.equals(modelName);
    }

    @Override
    public @Nullable Object buildAll(@NonNull String modelName, @NonNull Project project) {
        /* Generically resolving dependencies in Android Projects is not allowed */
        if(AndroidUtilsKt.isAndroidProject(project)) {
            return null;
        }

        Map<Integer, Dependency> dependencies = new HashMap<>();
        for (Configuration configuration : project.getConfigurations()) {
            for (Dependency dependency : configuration.getAllDependencies()) {
                if (dependency instanceof org.gradle.api.artifacts.ExternalModuleDependency) {
                    dependencies.put(
                            Objects.hash(dependency.getGroup(), dependency.getName(), dependency.getVersion()),
                            dependency
                    );
                }
            }
        }

        Configuration detachedConfiguration = project.getConfigurations().detachedConfiguration();
        detachedConfiguration.getDependencies().addAll(dependencies.values());
        Set<ResolvedArtifactResult> resolvedArtifactResults = resolveConfiguration(detachedConfiguration);
        Set<ExternalModuleDependency> resolvedDependencies = mapResolvedDependencies(resolvedArtifactResults);
        return new ExternalModuleDependencySetImpl(resolvedDependencies);
    }

    private static @NonNull Set<@NonNull ResolvedArtifactResult> resolveConfiguration(@NonNull Configuration configuration) {
        return configuration.getIncoming()
                .artifactView(new Action<ArtifactView.ViewConfiguration>() {
                    @Override
                    public void execute(ArtifactView.@NonNull ViewConfiguration configuration) {
                        configuration.setLenient(true);
                        configuration.attributes(container -> {
                            container.attribute(Attribute.of("artifactType", String.class), ArtifactTypeDefinition.JAR_TYPE);
                        });
                    }
                })
                .getArtifacts()
                .getArtifacts();
    }

    private static @NonNull Set<@NonNull ExternalModuleDependency> mapResolvedDependencies(
            @NonNull Set<@NonNull ResolvedArtifactResult> resolvedDependencies
    ) {
        return resolvedDependencies.stream()
                .map(it -> new ExternalModuleDependencyImpl(
                        it.getId().getComponentIdentifier().getDisplayName(),
                        it.getFile()
                ))
                .collect(Collectors.toSet());
    }
}
