// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.action;

import com.jetbrains.ls.imports.gradle.model.KotlinModule;
import com.jetbrains.ls.imports.gradle.model.ModuleSourceSet;
import com.jetbrains.ls.imports.gradle.model.ModuleSourceSets;
import org.gradle.tooling.BuildAction;
import org.gradle.tooling.BuildController;
import org.gradle.tooling.Failure;
import org.gradle.tooling.FetchModelResult;
import org.gradle.tooling.model.DomainObjectSet;
import org.gradle.tooling.model.HierarchicalElement;
import org.gradle.tooling.model.gradle.BasicGradleProject;
import org.gradle.tooling.model.gradle.GradleBuild;
import org.gradle.tooling.model.idea.IdeaModule;
import org.gradle.tooling.model.idea.IdeaProject;
import org.gradle.util.GradleVersion;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ProjectMetadataBuilder implements BuildAction<ProjectMetadata> {

    private static final @NonNull GradleVersion INCLUDED_BUILD_API_GRADLE_VERSION = GradleVersion.version("8.0");

    @Override
    public @NonNull ProjectMetadata execute(@NonNull BuildController controller) {
        Map<String, KotlinModule> kotlinModules = new HashMap<>();
        Map<String, Set<ModuleSourceSet>> sourceSets = new HashMap<>();
        List<IdeaProject> ideaProjects = fetchProjects(controller);
        for (IdeaProject project : ideaProjects) {
            fetchProjectData(project, controller, kotlinModules, sourceSets);
        }
        return new ProjectMetadata(
                ideaProjects,
                kotlinModules,
                sourceSets
        );
    }

    private static void fetchProjectData(
            @NonNull IdeaProject project,
            @NonNull BuildController controller,
            @NonNull Map<@NonNull String, @NonNull KotlinModule> kotlinModules,
            @NonNull Map<@NonNull String, @NonNull Set<ModuleSourceSet>> sourceSets
    ) {
        for (IdeaModule module : project.getModules()) {
            String moduleFqdn = getModuleFqdn(module);

            KotlinModule kotlinModule = unwrapFetchedModel(controller.fetch(module, KotlinModule.class));
            if (kotlinModule != null) {
                kotlinModules.put(moduleFqdn, kotlinModule);
            }

            ModuleSourceSets moduleSourceSets = unwrapFetchedModel(controller.fetch(module, ModuleSourceSets.class));
            sourceSets.put(moduleFqdn, moduleSourceSets == null ? Collections.emptySet() : moduleSourceSets.getSourceSets());
        }
    }

    private static @NonNull String getModuleFqdn(@NonNull IdeaModule module) {
        StringBuilder fqdn = new StringBuilder(module.getName());
        if (module.getName().equals(module.getProject().getName())) {
            return module.getName();
        }
        HierarchicalElement currentParent = module.getParent();
        while (currentParent != null) {
            fqdn.insert(0, currentParent.getName() + ".");
            currentParent = currentParent.getParent();
        }
        return fqdn.toString();
    }

    private static <Model> @Nullable Model unwrapFetchedModel(@NonNull FetchModelResult<Model> result) {
        if (!result.getFailures().isEmpty()) {
            for (Failure failure : result.getFailures()) {
                System.err.println(failure.getMessage());
            }
        }
        return result.getModel();
    }

    private static @NonNull DomainObjectSet<? extends GradleBuild> getIncludedBuilds(@NonNull BuildController controller) {
        GradleBuild buildModel = controller.getBuildModel();
        if (GradleVersion.current().compareTo(INCLUDED_BUILD_API_GRADLE_VERSION) <= 0) {
            return buildModel.getIncludedBuilds();
        }
        DomainObjectSet<? extends GradleBuild> editableBuilds = buildModel.getEditableBuilds();
        if (editableBuilds.isEmpty()) {
            return buildModel.getIncludedBuilds();
        }
        return editableBuilds;
    }

    private static @NonNull List<@NonNull IdeaProject> fetchProjects(@NonNull BuildController controller) {
        List<IdeaProject> allProjects = new ArrayList<>();
        IdeaProject rootProject = unwrapFetchedModel(controller.fetch(IdeaProject.class));
        if (rootProject != null) {
            allProjects.add(rootProject);
        }
        for (GradleBuild includedBuild : getIncludedBuilds(controller)) {
            for (BasicGradleProject project : includedBuild.getProjects()) {
                IdeaProject nestedProject = unwrapFetchedModel(controller.fetch(project, IdeaProject.class));
                if (nestedProject != null) {
                    allProjects.add(nestedProject);
                }
            }
        }
        return allProjects;
    }
}
