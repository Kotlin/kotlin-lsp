// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.model.builder;

import com.jetbrains.ls.imports.gradle.model.ExternalModuleDependency;
import com.jetbrains.ls.imports.gradle.model.ExternalModuleDependencySet;
import com.jetbrains.ls.imports.gradle.model.ExternalModuleFullDependencySet;
import com.jetbrains.ls.imports.gradle.model.builder.android.AndroidUtilsKt;
import com.jetbrains.ls.imports.gradle.model.impl.ExternalModuleDependencyImpl;
import com.jetbrains.ls.imports.gradle.model.impl.ExternalModuleDependencySetImpl;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ArtifactView;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.component.ComponentArtifactIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.artifacts.type.ArtifactTypeDefinition;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.attributes.Bundling;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.DocsType;
import org.gradle.api.attributes.Usage;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.ExtensionContainer;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.internal.component.external.model.DefaultModuleComponentArtifactIdentifier;
import org.gradle.internal.component.model.IvyArtifactName;
import org.gradle.tooling.provider.model.ToolingModelBuilder;
import org.gradle.util.GradleVersion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@SuppressWarnings("IO_FILE_USAGE")
public final class ExternalModuleDependencySetModuleBuilder implements ToolingModelBuilder {

    private static final @NotNull Set<String> MODEL_NAMES;

    static {
        Set<String> supportedModels = new HashSet<>();
        supportedModels.add(ExternalModuleDependencySet.class.getCanonicalName());
        supportedModels.add(ExternalModuleFullDependencySet.class.getCanonicalName());
        MODEL_NAMES = Collections.unmodifiableSet(supportedModels);
    }

    @Override
    public boolean canBuild(@NotNull String modelName) {
        return MODEL_NAMES.contains(modelName);
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
            Set<ExternalModuleDependency> result = resolveSourceSetDependencies(
                    sourceSets,
                    project,
                    modelName.equals(ExternalModuleFullDependencySet.class.getCanonicalName())
            );
            return new ExternalModuleDependencySetImpl(result);
        }
        return null;
    }

    private static @NotNull Set<ExternalModuleDependency> resolveSourceSetDependencies(
            @NotNull SourceSetContainer sourceSets,
            @NotNull Project project,
            boolean downloadSources
    ) {
        Set<ExternalModuleDependency> result = new HashSet<>();
        ConfigurationContainer projectConfigurations = project.getConfigurations();
        for (SourceSet sourceSet : sourceSets) {
            String compileConfigurationName = sourceSet.getCompileClasspathConfigurationName();
            Configuration classpathConfiguration = projectConfigurations.getByName(compileConfigurationName);
            if (classpathConfiguration.isCanBeResolved()) {
                Set<ExternalModuleDependency> dependencies = joinSourcesAndBinaries(
                        resolveConfiguration(classpathConfiguration),
                        downloadSources ? resolveConfigurationSources(project, classpathConfiguration) : Collections.emptySet()
                );
                result.addAll(dependencies);
            }
        }
        return result;
    }

    private static @NotNull Set<@NotNull ExternalModuleDependency> joinSourcesAndBinaries(
            @NotNull Set<@NotNull ResolvedArtifactResult> binaries,
            @NotNull Set<@NotNull ResolvedArtifactResult> sources
    ) {
        Map<String, File> artifactSources = new HashMap<>();
        for (ResolvedArtifactResult source : sources) {
            artifactSources.put(getArtifactId(source), source.getFile());
        }
        return binaries.stream()
                .map(it -> {
                    String id = getArtifactId(it);
                    File sourceFile = artifactSources.get(id);
                    return new ExternalModuleDependencyImpl(
                            getArtifactMavenCoordinates(it),
                            it.getFile(),
                            sourceFile
                    );
                })
                .collect(Collectors.toSet());
    }

    private static @NotNull Set<@NotNull ResolvedArtifactResult> resolveConfiguration(@NotNull Configuration configuration) {
        return resolveFiles(configuration, view -> {
            view.attributes(container -> {
                container.attribute(Attribute.of("artifactType", String.class), ArtifactTypeDefinition.JAR_TYPE);
            });
        });
    }

    private static @NotNull Set<@NotNull ResolvedArtifactResult> resolveConfigurationSources(
            @NotNull Project project,
            @NotNull Configuration configuration
    ) {
        // withVariantReselection was added only in Gradle 7.5 thus we have to rely on lsp-gradle-idea-configurator init script
        if (GradleVersion.current().compareTo(GradleVersion.version("7.5")) < 0) {
            return new HashSet<>();
        }
        ObjectFactory objects = project.getObjects();
        return resolveFiles(configuration, view -> {
            view.withVariantReselection();
            view.attributes(container -> {
                // all sources/Javadoc are considered as Runtime libraries from the perspective of API/Implementation use
                container.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.class, Category.DOCUMENTATION));
                container.attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.class, Bundling.EXTERNAL));
                container.attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named(DocsType.class, DocsType.SOURCES));
            });
        });
    }

    private static @NotNull Set<@NotNull ResolvedArtifactResult> resolveFiles(
            @NotNull Configuration configuration,
            @NotNull Consumer<ArtifactView.ViewConfiguration> viewConfigurer
    ) {
        return configuration.getIncoming()
                .artifactView(view -> {
                    view.setLenient(true);
                    view.componentFilter(element -> !(element instanceof ProjectComponentIdentifier));
                    viewConfigurer.accept(view);
                })
                .getArtifacts()
                .getArtifacts();
    }

    @SuppressWarnings("StringBufferReplaceableByString")
    private static @NotNull String getArtifactMavenCoordinates(@NotNull ResolvedArtifactResult artifact) {
        ComponentArtifactIdentifier id = artifact.getId();
        String coordinates = id.getComponentIdentifier().getDisplayName();
        if (id instanceof DefaultModuleComponentArtifactIdentifier) {
            IvyArtifactName artifactName = ((DefaultModuleComponentArtifactIdentifier) id).getName();
            String classifier = artifactName.getClassifier();
            if (classifier == null) {
                return coordinates;
            }
            String[] gav = coordinates.split(":");
            if (gav.length == 3) {
                return new StringBuilder()
                        .append(gav[0]).append(":")
                        .append(gav[1]).append(":")
                        .append(classifier).append(":")
                        .append(gav[2])
                        .toString();
            }
        }
        return coordinates;
    }

    private static @NotNull String getArtifactId(@NotNull ResolvedArtifactResult artifactResult) {
        return artifactResult.getId()
                .getComponentIdentifier()
                .getDisplayName();
    }
}
