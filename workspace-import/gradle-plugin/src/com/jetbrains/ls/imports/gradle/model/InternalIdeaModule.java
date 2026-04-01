// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.model;

import org.gradle.tooling.model.DomainObjectSet;
import org.gradle.tooling.model.GradleProject;
import org.gradle.tooling.model.HierarchicalElement;
import org.gradle.tooling.model.ProjectIdentifier;
import org.gradle.tooling.model.UnsupportedMethodException;
import org.gradle.tooling.model.idea.IdeaCompilerOutput;
import org.gradle.tooling.model.idea.IdeaContentRoot;
import org.gradle.tooling.model.idea.IdeaDependency;
import org.gradle.tooling.model.idea.IdeaJavaLanguageSettings;
import org.gradle.tooling.model.idea.IdeaModule;
import org.gradle.tooling.model.idea.IdeaProject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;

public class InternalIdeaModule implements Serializable, IdeaModule {

    private final @NotNull IdeaModule delegate;
    private @NotNull String name;

    public InternalIdeaModule(@NotNull IdeaModule module) {
        this.delegate = module;
        this.name = module.getName();
    }

    @Override
    public @NotNull String getName() {
        return this.name;
    }

    public @NotNull IdeaModule getDelegate() {
        return delegate;
    }

    public void setName(@NotNull String name) {
        this.name = name;
    }

    @Override
    public @Nullable IdeaJavaLanguageSettings getJavaLanguageSettings() throws UnsupportedMethodException {
        return delegate.getJavaLanguageSettings();
    }

    @Override
    public String getJdkName() throws UnsupportedMethodException {
        return delegate.getJdkName();
    }

    @Override
    public DomainObjectSet<? extends IdeaContentRoot> getContentRoots() {
        return delegate.getContentRoots();
    }

    @Override
    public GradleProject getGradleProject() {
        return delegate.getGradleProject();
    }

    @Override
    public IdeaProject getParent() {
        return delegate.getParent();
    }

    @Override
    public IdeaProject getProject() {
        return delegate.getProject();
    }

    @Override
    public IdeaCompilerOutput getCompilerOutput() {
        return delegate.getCompilerOutput();
    }

    @Override
    public DomainObjectSet<? extends IdeaDependency> getDependencies() {
        return delegate.getDependencies();
    }

    @Override
    public ProjectIdentifier getProjectIdentifier() {
        return delegate.getProjectIdentifier();
    }

    @Override
    public DomainObjectSet<? extends HierarchicalElement> getChildren() {
        return delegate.getChildren();
    }

    @Override
    public @Nullable String getDescription() {
        return delegate.getDescription();
    }
}
