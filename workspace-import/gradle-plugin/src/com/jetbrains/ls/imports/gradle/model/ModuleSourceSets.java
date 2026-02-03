// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.model;

import org.jspecify.annotations.NonNull;

import java.io.Serializable;
import java.util.Set;

public interface ModuleSourceSets extends Serializable {

    @NonNull Set<@NonNull ModuleSourceSet> getSourceSets();

}
