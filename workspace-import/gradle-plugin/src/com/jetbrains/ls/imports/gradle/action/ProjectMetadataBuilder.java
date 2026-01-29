// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.action;

import com.jetbrains.ls.imports.gradle.model.KotlinModule;
import org.gradle.tooling.BuildAction;
import org.gradle.tooling.BuildController;
import org.gradle.tooling.model.HierarchicalElement;
import org.gradle.tooling.model.idea.IdeaModule;
import org.gradle.tooling.model.idea.IdeaProject;
import org.jspecify.annotations.NonNull;

import java.util.HashMap;
import java.util.Map;

public class ProjectMetadataBuilder implements BuildAction<ProjectMetadata> {

    @Override
    public @NonNull ProjectMetadata execute(@NonNull BuildController controller) {
        IdeaProject ideaProject = controller.findModel(IdeaProject.class);
        if (ideaProject == null) {
            throw new IllegalStateException("Unable to resolve the basic Gradle Project information");
        }
        Map<String, KotlinModule> kotlinModules = new HashMap<>();
        ideaProject.getModules()
                .forEach(module -> {
                    kotlinModules.put(
                            getModuleFqdn(module),
                            controller.findModel(module, KotlinModule.class)
                    );
                });
        return new ProjectMetadata(
                ideaProject,
                kotlinModules
        );
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
}
