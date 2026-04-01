// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.model;

import org.gradle.tooling.model.DomainObjectSet;
import org.gradle.tooling.model.HierarchicalElement;
import org.gradle.tooling.model.UnsupportedMethodException;
import org.gradle.tooling.model.idea.IdeaJavaLanguageSettings;
import org.gradle.tooling.model.idea.IdeaLanguageLevel;
import org.gradle.tooling.model.idea.IdeaModule;
import org.gradle.tooling.model.idea.IdeaProject;
import org.gradle.tooling.model.internal.ImmutableDomainObjectSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.util.List;
import java.util.stream.Collectors;

public final class InternalIdeaProject implements Serializable, IdeaProject {

    private final @NotNull IdeaProject delegate;
    private final @NotNull DomainObjectSet<InternalIdeaModule> children;

    public InternalIdeaProject(@NotNull IdeaProject project) {
        this.delegate = project;
        this.children = mapModules(project);
    }

    @Override
    public IdeaJavaLanguageSettings getJavaLanguageSettings() throws UnsupportedMethodException {
        return delegate.getJavaLanguageSettings();
    }

    @Override
    public String getJdkName() {
        return delegate.getJdkName();
    }

    @Override
    public IdeaLanguageLevel getLanguageLevel() {
        return delegate.getLanguageLevel();
    }

    @Override
    public @NotNull DomainObjectSet<? extends InternalIdeaModule> getChildren() {
        return children;
    }

    @Override
    public @NotNull DomainObjectSet<? extends InternalIdeaModule> getModules() {
        return children;
    }

    @Override
    public @Nullable HierarchicalElement getParent() {
        return delegate.getParent();
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public @Nullable String getDescription() {
        return delegate.getDescription();
    }

    private static @NotNull DomainObjectSet<InternalIdeaModule> mapModules(@NotNull IdeaProject project) {
        List<InternalIdeaModule> mappedModules = project.getModules()
                .stream()
                .map(InternalIdeaModule::new)
                .collect(Collectors.toList());
        return ImmutableDomainObjectSet.of(mappedModules);
    }
}