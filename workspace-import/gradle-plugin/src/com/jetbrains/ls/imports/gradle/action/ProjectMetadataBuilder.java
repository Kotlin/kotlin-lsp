// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.action;

import com.jetbrains.ls.imports.gradle.model.ExternalModuleDependency;
import com.jetbrains.ls.imports.gradle.model.ExternalModuleDependencySet;
import com.jetbrains.ls.imports.gradle.model.AndroidProject;
import com.jetbrains.ls.imports.gradle.model.InternalIdeaModule;
import com.jetbrains.ls.imports.gradle.model.InternalIdeaProject;
import com.jetbrains.ls.imports.gradle.model.KotlinModule;
import com.jetbrains.ls.imports.gradle.model.ModuleSourceSet;
import com.jetbrains.ls.imports.gradle.model.ModuleSourceSets;
import com.jetbrains.ls.imports.gradle.utils.ProxyUtil;
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
import org.jetbrains.kotlin.gradle.idea.serialize.IdeaKotlinSerializationContext;
import org.jetbrains.kotlin.gradle.idea.tcs.IdeaKotlinDependency;
import org.jetbrains.kotlin.tooling.core.Extras;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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

    private static final @NotNull String BUILD_SRC_MODULE_NAME = "buildSrc";
    private static final @NotNull GradleVersion INCLUDED_BUILD_API_GRADLE_VERSION = GradleVersion.version("8.0");

    @Override
    public @NotNull ProjectMetadata execute(@NotNull BuildController controller) {
        Map<String, KotlinModule> kotlinModules = new HashMap<>();
        Map<String, Set<ModuleSourceSet>> sourceSets = new HashMap<>();
        Map<String, Set<ExternalModuleDependency>> externalModuleDependencySet = new HashMap<>();
        Map<String, AndroidProject> androidProjects = new HashMap<>();
        List<InternalIdeaProject> ideaProjects = fetchProjects(controller);
        resolveModels(ideaProjects, controller, kotlinModules, sourceSets, externalModuleDependencySet, androidProjects);
        return new ProjectMetadata(
                ideaProjects,
                kotlinModules,
                sourceSets,
                externalModuleDependencySet,
                androidProjects
        );
    }

    private static void resolveModels(
            @NotNull List<@NotNull InternalIdeaProject> ideaProjects,
            @NotNull BuildController controller,
            @NotNull Map<@NotNull String, @NotNull KotlinModule> kotlinModules,
            @NotNull Map<@NotNull String, @NotNull Set<ModuleSourceSet>> sourceSets,
            @NotNull Map<@NotNull String, @NotNull Set<ExternalModuleDependency>> externalModuleDependencySet,
            @NotNull Map<@NotNull String, @NotNull AndroidProject> androidProjects
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

                AndroidProject androidProject = unwrapFetchedModel(controller.fetch(delegate, AndroidProject.class));
                if (androidProject != null) {
                    androidProjects.put(moduleFqdn, ProxyUtil.unpackProxy(ProjectMetadata.class.getClassLoader(), androidProject));
                }
            }
        }
    }

    private static @NotNull String getModuleFqdn(@NotNull IdeaModule module) {
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

    private static <Model> @Nullable Model unwrapFetchedModel(@NotNull FetchModelResult<Model> result) {
        if (!result.getFailures().isEmpty()) {
            for (Failure failure : result.getFailures()) {
                System.err.println(failure.getMessage());
            }
        }
        return result.getModel();
    }

    private static @NotNull DomainObjectSet<? extends GradleBuild> getIncludedBuilds(@NotNull BuildController controller) {
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

    private static @NotNull List<@NotNull InternalIdeaProject> fetchProjects(@NotNull BuildController controller) {
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

    private static @NotNull String getBuildSrcName(
            @NotNull IdeaModule module,
            @NotNull List<@NotNull InternalIdeaProject> includedProjects
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

    private static @NotNull String getRootProjectName(
            @NotNull IdeaModule module,
            @NotNull String parentRootDir,
            @NotNull Map<@NotNull String, @NotNull String> includedProjectPaths
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

    private static <T> @Nullable T firstOrNull(@NotNull Collection<@NotNull T> items) {
        Iterator<T> iterator = items.iterator();
        if (iterator.hasNext()) {
            return iterator.next();
        }
        return null;
    }

    /**
     * The Gradle Tooling will infer the classpath of this action by traversing this class's dependencies.
     * Some dependencies can be lost, since this class will only use some classes (e.g. IdeaKotlinDependency)
     * inside a box (Set, Map, ...), which erases the generic types. The types need to be mentioned directly
     * in a class, within its constant pool.
     * This method, therefore, is just a dummy, ensuring that some dependency classes are mentioned.
     */
    @SuppressWarnings("unused")
    private static void __use_dependency_classes(Object obj) {
        /*
          Used to declare custom dependencies in the Android model
         */
        if (obj instanceof IdeaKotlinDependency) {
            return;
        }

        /*
        Used for serializing Kotlin dependencies.
         */
        if (obj instanceof IdeaKotlinSerializationContext) {
            return;
        }

        /*
        Aux for 'IdeaKotlinDependency'
         */
        if (obj instanceof Extras) {
            return;
        }

        throw new UnsupportedOperationException("This method shall never be executed");
    }
}
