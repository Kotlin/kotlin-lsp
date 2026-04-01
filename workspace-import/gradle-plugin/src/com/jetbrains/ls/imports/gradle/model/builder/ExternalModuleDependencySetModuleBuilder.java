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
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.artifacts.type.ArtifactTypeDefinition;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.plugins.ExtensionContainer;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.tooling.provider.model.ToolingModelBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

public final class ExternalModuleDependencySetModuleBuilder implements ToolingModelBuilder {

    private static final @NotNull String MODEL_NAME = ExternalModuleDependencySet.class.getCanonicalName();

    @Override
    public boolean canBuild(@NotNull String modelName) {
        return MODEL_NAME.equals(modelName);
    }

    @Override
    public @Nullable Object buildAll(@NotNull String modelName, @NotNull Project project) {
        /* Generically resolving dependencies in Android Projects is not allowed */
        if(AndroidUtilsKt.isAndroidProject(project)) {
            return null;
        }

        ExtensionContainer extensions = project.getExtensions();
        SourceSetContainer sourceSets = extensions.findByType(SourceSetContainer.class);
        if (sourceSets != null) {
            Set<ExternalModuleDependency> result = resolveSourceSetDependencies(sourceSets, project);
            return new ExternalModuleDependencySetImpl(result);
        }
        return null;
    }

    private static @NotNull Set<ExternalModuleDependency> resolveSourceSetDependencies(
            @NotNull SourceSetContainer sourceSets,
            @NotNull Project project
    ) {
        Set<ExternalModuleDependency> result = new HashSet<>();
        for (SourceSet sourceSet : sourceSets) {
            String classpathConfigurationName = sourceSet.getCompileClasspathConfigurationName();
            Configuration classpathConfiguration = project.getConfigurations().getByName(classpathConfigurationName);
            if (classpathConfiguration.isCanBeResolved()) {
                Set<ExternalModuleDependency> dependencies = resolveConfiguration(classpathConfiguration);
                result.addAll(dependencies);
            }
        }
        return result;
    }

    private static @NotNull Set<@NotNull ExternalModuleDependency> resolveConfiguration(@NotNull Configuration configuration) {
        return configuration.getIncoming()
                .artifactView(new Action<ArtifactView.ViewConfiguration>() {
                    @Override
                    public void execute(ArtifactView.@NotNull ViewConfiguration configuration) {
                        configuration.setLenient(true);
                        configuration.componentFilter(element -> !(element instanceof ProjectComponentIdentifier));
                        configuration.attributes(container -> {
                            container.attribute(Attribute.of("artifactType", String.class), ArtifactTypeDefinition.JAR_TYPE);
                        });
                    }
                })
                .getArtifacts()
                .getArtifacts()
                .stream()
                .map(it -> new ExternalModuleDependencyImpl(
                        it.getId().getComponentIdentifier().getDisplayName(),
                        it.getFile()
                ))
                .collect(Collectors.toSet());
    }
}
