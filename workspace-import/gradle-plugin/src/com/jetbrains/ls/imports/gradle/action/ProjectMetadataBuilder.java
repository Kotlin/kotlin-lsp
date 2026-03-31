// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.action;

import com.jetbrains.ls.imports.gradle.model.ExternalModuleDependency;
import com.jetbrains.ls.imports.gradle.model.ExternalModuleDependencySet;
import com.jetbrains.ls.imports.gradle.model.InternalIdeaModule;
import com.jetbrains.ls.imports.gradle.model.InternalIdeaProject;
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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class ProjectMetadataBuilder implements BuildAction<ProjectMetadata> {

    private static final @NonNull String BUILD_SRC_MODULE_NAME = "buildSrc";
    private static final @NonNull GradleVersion INCLUDED_BUILD_API_GRADLE_VERSION = GradleVersion.version("8.0");

    @Override
    public @NonNull ProjectMetadata execute(@NonNull BuildController controller) {
        Map<String, KotlinModule> kotlinModules = new HashMap<>();
        Map<String, Set<ModuleSourceSet>> sourceSets = new HashMap<>();
        Map<String, Set<ExternalModuleDependency>> externalModuleDependencySet = new HashMap<>();
        List<InternalIdeaProject> ideaProjects = fetchProjects(controller);
        resolveModels(ideaProjects, controller, kotlinModules, sourceSets, externalModuleDependencySet);
        return new ProjectMetadata(
                ideaProjects,
                kotlinModules,
                sourceSets,
                externalModuleDependencySet
        );
    }

    private static void resolveModels(
            @NonNull List<@NonNull InternalIdeaProject> ideaProjects,
            @NonNull BuildController controller,
            @NonNull Map<@NonNull String, @NonNull KotlinModule> kotlinModules,
            @NonNull Map<@NonNull String, @NonNull Set<ModuleSourceSet>> sourceSets,
            @NonNull Map<@NonNull String, @NonNull Set<ExternalModuleDependency>> externalModuleDependencySet
    ) {
        for (InternalIdeaProject project : ideaProjects) {
            for (InternalIdeaModule module : project.getModules()) {
                String moduleFqdn = BUILD_SRC_MODULE_NAME.equals(module.getName())
                                    ? getBuildSrcName(module, ideaProjects)
                                    : getModuleFqdn(module);
                module.setName(moduleFqdn);

                IdeaModule delegate = module.getDelegate();
                KotlinModule kotlinModule = unwrapFetchedModel(controller.fetch(delegate, KotlinModule.class));
                if (kotlinModule != null) {
                    kotlinModules.put(moduleFqdn, kotlinModule);
                }
                ModuleSourceSets moduleSourceSets = unwrapFetchedModel(controller.fetch(delegate, ModuleSourceSets.class));
                sourceSets.put(moduleFqdn, moduleSourceSets == null ? Collections.emptySet() : moduleSourceSets.getSourceSets());

                ExternalModuleDependencySet moduleDependencies = unwrapFetchedModel(
                        controller.fetch(delegate, ExternalModuleDependencySet.class)
                );
                externalModuleDependencySet.put(
                        moduleFqdn,
                        moduleDependencies == null ? Collections.emptySet() : moduleDependencies.getDependencies()
                );
            }
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

    private static @NonNull List<@NonNull InternalIdeaProject> fetchProjects(@NonNull BuildController controller) {
        List<InternalIdeaProject> allProjects = new ArrayList<>();
        IdeaProject rootProject = unwrapFetchedModel(controller.fetch(IdeaProject.class));
        if (rootProject != null) {
            allProjects.add(new InternalIdeaProject(rootProject));
        }
        for (GradleBuild includedBuild : getIncludedBuilds(controller)) {
            for (BasicGradleProject project : includedBuild.getProjects()) {
                IdeaProject nestedProject = unwrapFetchedModel(controller.fetch(project, IdeaProject.class));
                if (nestedProject != null) {
                    InternalIdeaProject mappedProject = new InternalIdeaProject(nestedProject);
                    allProjects.add(mappedProject);
                }
            }
        }
        return allProjects;
    }

    private static @NonNull String getBuildSrcName(
            @NonNull IdeaModule module,
            @NonNull List<@NonNull InternalIdeaProject> includedProjects
    ) {
        String parentRootDir = module.getProjectIdentifier()
                .getBuildIdentifier()
                .getRootDir()
                .getParent();
        List<InternalIdeaModule> projectModules = includedProjects
                .stream()
                .map(it -> firstOrNull(it.getModules()))
                .filter(it -> it != null)
                .collect(Collectors.toList());
        Map<String, String> includedProjectPaths = new HashMap<>();
        for (InternalIdeaModule ideaModule : projectModules) {
            includedProjectPaths.put(
                    ideaModule.getProject().getName(),
                    ideaModule.getProjectIdentifier()
                            .getBuildIdentifier()
                            .getRootDir()
                            .getParent()
            );
        }
        String rootProjectName = getRootProjectName(module, parentRootDir, includedProjectPaths);
        if (!rootProjectName.equals(module.getParent().getName())) {
            return rootProjectName + "." + module.getName();
        }
        return rootProjectName;
    }

    private static @NonNull String getRootProjectName(
            @NonNull IdeaModule module,
            @NonNull String parentRootDir,
            @NonNull Map<@NonNull String, @NonNull String> includedProjectPaths
    ) {
        String rootProjectName = module.getProject().getName();
        String rootProjectPath = parentRootDir;
        for (Map.Entry<String, String> entry : includedProjectPaths.entrySet()) {
            String projectName = entry.getKey();
            String projectPath = entry.getValue();
            if (rootProjectPath.contains(projectPath) && projectPath.length() < rootProjectPath.length()) {
                rootProjectName = projectName;
                rootProjectPath = projectPath;
            }
        }
        return rootProjectName;
    }

    private static <T> @Nullable T firstOrNull(@NonNull Collection<@NonNull T> items) {
        Iterator<T> iterator = items.iterator();
        if (iterator.hasNext()) {
            return iterator.next();
        }
        return null;
    }
}
