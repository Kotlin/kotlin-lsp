// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.utils;

import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.file.FileCollection;
import org.gradle.api.tasks.AbstractCopyTask;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetOutput;
import org.gradle.api.tasks.TaskCollection;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

@SuppressWarnings("IO_FILE_USAGE")
public final class SourceSetArtifactExtractor {

    public static @NotNull Collection<File> extractSourceSetArtifacts(
            @NotNull Project project,
            @NotNull SourceSet sourceSet
    ) {
        Collection<File> sourceSetArtifacts = new LinkedHashSet<>();
        TaskCollection<AbstractArchiveTask> archiveTaskCollection = project.getTasks().withType(AbstractArchiveTask.class);
        archiveTaskCollection.all(new Action<AbstractArchiveTask>() {
            @Override
            public void execute(@NotNull AbstractArchiveTask task) {
                if (containsAllSourceSetOutput(task, sourceSet)) {
                    File archiveFile = task.getArchiveFile().get().getAsFile();
                    sourceSetArtifacts.add(archiveFile);
                }
            }
        });
        return sourceSetArtifacts;
    }

    private static boolean isResolvableFileCollection(@NotNull Object param, @NotNull Project project) {
        Object object = tryUnpackPresentProvider(param, project);
        if (object instanceof FileCollection) {
            try {
                project.files(object).getFiles();
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }

    private static boolean isSafeToResolve(@NotNull Object param, @NotNull Project project) {
        Object object = tryUnpackPresentProvider(param, project);
        return object instanceof CharSequence ||
               object instanceof File ||
               object instanceof Path ||
               object instanceof SourceSetOutput ||
               isInstance(object, "org.gradle.api.file.Directory") ||
               isInstance(object, "org.gradle.api.file.RegularFile");
    }

    private static @NotNull Object tryUnpackPresentProvider(@NotNull Object object, @NotNull Project project) {
        if (!isInstance(object, "org.gradle.api.provider.Provider")) {
            return object;
        }
        try {
            Class<?> providerClass = object.getClass();
            Method isPresentMethod = providerClass.getMethod("isPresent");
            Method getterMethod = providerClass.getMethod("get");
            if ((Boolean) isPresentMethod.invoke(object)) {
                return getterMethod.invoke(object);
            }
            return object;
        } catch (InvocationTargetException | NoSuchMethodException | IllegalAccessException exception) {
            Throwable cause = exception.getCause();
            boolean isCodeException = isInstance(cause, "org.gradle.api.InvalidUserCodeException");
            boolean isDataException = isInstance(cause, "org.gradle.api.InvalidUserDataException");
            if (isCodeException || isDataException) {
                return object;
            }
            String msg = cause.getMessage();
            String className = cause.getClass().getCanonicalName();
            project.getLogger().info("Unable to resolve task source path: {} ({})", msg, className);
            return object;
        }
    }

    private static boolean containsAllSourceSetOutput(@NotNull AbstractArchiveTask archiveTask, @NotNull SourceSet sourceSet) {
        Set<File> outputFiles = new HashSet<>(sourceSet.getOutput().getFiles());
        Project project = archiveTask.getProject();
        try {
            Set<Object> sourcePaths = getArchiveTaskSourcePaths(archiveTask);
            for (Object path : sourcePaths) {
                if (isSafeToResolve(path, project) || isResolvableFileCollection(path, project)) {
                    outputFiles.removeAll(project.files(path).getFiles());
                }
            }
        } catch (Exception e) {
            project.getLogger().info("Unable to resolve all source set artifacts: {}", sourceSet.getName());
            return false;
        }
        return outputFiles.isEmpty();
    }

    private static @NotNull Set<Object> getArchiveTaskSourcePaths(@NotNull AbstractArchiveTask archiveTask) {
        try {
            final Method mainSpecGetter = AbstractCopyTask.class.getDeclaredMethod("getMainSpec");
            mainSpecGetter.setAccessible(true);
            Object mainSpec = mainSpecGetter.invoke(archiveTask);
            Method getSourcePaths = mainSpec.getClass().getMethod("getSourcePaths");

            @SuppressWarnings("unchecked")
            Set<Object> sourcePaths = (Set<Object>) getSourcePaths.invoke(mainSpec);
            if (sourcePaths != null) {
                return sourcePaths;
            } else {
                return Collections.emptySet();
            }
        } catch (InvocationTargetException | NoSuchMethodException | IllegalAccessException ignored) {
            return Collections.emptySet();
        }
    }

    private static boolean isInstance(@NotNull Object object, @NotNull String className) {
        Class<?> clazz = findClassForName(className);
        return clazz != null && clazz.isInstance(object);
    }

    private static @Nullable Class<?> findClassForName(@NotNull String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException __) {
            return null;
        }
    }
}
