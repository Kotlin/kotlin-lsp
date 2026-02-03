// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle;

import com.jetbrains.ls.imports.gradle.model.builder.KotlinMetadataModelBuilder;
import com.jetbrains.ls.imports.gradle.model.builder.ModuleSourceSetsModelBuilder;
import org.gradle.api.Plugin;
import org.gradle.api.invocation.Gradle;
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry;
import org.jspecify.annotations.NonNull;

import javax.inject.Inject;

public final class IdeaGradleLspPlugin implements Plugin<Gradle> {

    private final ToolingModelBuilderRegistry registry;

    @Inject
    public IdeaGradleLspPlugin(ToolingModelBuilderRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void apply(@NonNull Gradle target) {
        registry.register(new KotlinMetadataModelBuilder());
        registry.register(new ModuleSourceSetsModelBuilder());
    }
}
