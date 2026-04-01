// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.model;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.Serializable;

@SuppressWarnings("IO_FILE_USAGE")
public interface ExternalModuleDependency extends Serializable {

    @NotNull String getMavenCoordinates();

    @NotNull File getFile();
}
