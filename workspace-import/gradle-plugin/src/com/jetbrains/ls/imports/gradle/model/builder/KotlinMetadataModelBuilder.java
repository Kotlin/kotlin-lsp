// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.model.builder;

import com.jetbrains.ls.imports.gradle.model.KotlinModule;
import com.jetbrains.ls.imports.gradle.model.impl.KotlinCompilerSettingsImpl;
import com.jetbrains.ls.imports.gradle.model.impl.KotlinModuleImpl;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.provider.DefaultProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.tooling.provider.model.ToolingModelBuilder;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class KotlinMetadataModelBuilder implements ToolingModelBuilder {

    private static final String COMPILE_TASK_NAME = "compileKotlin";
    private static final String COMPILER_OPTIONS_PROPERTY_NAME = "compilerOptions";
    private static final String JVM_TARGET_PROPERTY_NAME = "jvmTarget";
    private static final String FREE_COMPILER_ARGS_PROPERTY_NAME = "freeCompilerArgs";
    private static final String PLUGIN_CLASSPATH_PROPERTY_NAME = "pluginClasspath";
    private static final String PLUGIN_OPTIONS_PROPERTY_NAME = "pluginOptions";

    private static final String GET_PROPERTY_METHOD_NAME = "getProperty";
    private static final String HAS_PROPERTY_METHOD_NAME = "hasProperty";

    private static final Pattern COMPILER_PLUGIN_JAR_PATTERN = Pattern.compile(".*-compiler-plugin.*\\.jar");

    private static final String TARGET_MODEL_NAME = KotlinModule.class.getName();

    @Override
    public boolean canBuild(@NonNull String modelName) {
        return TARGET_MODEL_NAME.equals(modelName);
    }

    @Override
    public Object buildAll(@NonNull String modelName, @NonNull Project project) {
        TaskContainer tasks = project.getTasks();
        Task compileKotlinTask = tasks.findByName(COMPILE_TASK_NAME);
        if (compileKotlinTask != null) {
            return readCompilerSettings(compileKotlinTask);
        }
        return null;
    }

    private static @NonNull KotlinModule readCompilerSettings(@NonNull Task task) {
        KotlinCompilerSettingsImpl compilerSettings = getKotlinCompilerSettings(task);
        return new KotlinModuleImpl(compilerSettings);
    }

    private static @NonNull KotlinCompilerSettingsImpl getKotlinCompilerSettings(@NonNull Task task) {
        String jvmTarget = null;
        List<String> compilerArgs = Collections.emptyList();
        if (task.hasProperty(COMPILER_OPTIONS_PROPERTY_NAME)) {
            Object compilerOptions = task.property(COMPILER_OPTIONS_PROPERTY_NAME);
            jvmTarget = getJvmTarget(compilerOptions);
            compilerArgs = getCompilerArgs(compilerOptions);
        }
        return new KotlinCompilerSettingsImpl(
                jvmTarget,
                getPluginOptions(task),
                getPluginClasspath(task),
                compilerArgs
        );
    }

    private static @NonNull List<String> getPluginOptions(@NonNull Task task) {
        if (!task.hasProperty(PLUGIN_OPTIONS_PROPERTY_NAME)) {
            return Collections.emptyList();
        }
        ListProperty pluginOptionsProperty = (ListProperty) task.property(PLUGIN_OPTIONS_PROPERTY_NAME);
        List<String> result = new ArrayList<>();
        if (pluginOptionsProperty != null && pluginOptionsProperty.isPresent()) {
            Iterable<Object> pluginOptions = (Iterable<Object>) pluginOptionsProperty.get();
            for (Object option : pluginOptions) {
                result.addAll(extractPluginOption(option));
            }
        }
        return result;
    }

    private static @NonNull List<String> extractPluginOption(@NonNull Object pluginOption) {
        try {
            Class<?> optionClass = pluginOption.getClass();
            return (List<String>) optionClass.getMethod("getArguments")
                    .invoke(pluginOption);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private static @NonNull List<String> getPluginClasspath(@NonNull Task task) {
        if (!task.hasProperty(PLUGIN_CLASSPATH_PROPERTY_NAME)) {
            return Collections.emptyList();
        }
        Set<File> pluginClasspath = ((FileCollection) task.property(PLUGIN_CLASSPATH_PROPERTY_NAME))
                .getFiles();
        List<String> result = new ArrayList<>();
        for (File path : pluginClasspath) {
            String pathString = path.getPath();
            Matcher matcher = COMPILER_PLUGIN_JAR_PATTERN.matcher(pathString);
            if (matcher.find()) {
                result.add(pathString);
            }
        }
        return result;
    }

    private static @Nullable String getJvmTarget(@NonNull Object compilerOptions) {
        DefaultProperty jvmTarget = (DefaultProperty) getProperty(compilerOptions, JVM_TARGET_PROPERTY_NAME);
        if (jvmTarget != null && jvmTarget.isPresent()) {
            String target = jvmTarget.get()
                    .toString();
            return target.replace("JVM_", "");
        }
        return null;
    }

    private static @NonNull List<String> getCompilerArgs(@NonNull Object compilerOptions) {
        List<String> args = new ArrayList<>();
        ListProperty property = (ListProperty) getProperty(compilerOptions, FREE_COMPILER_ARGS_PROPERTY_NAME);
        if (property != null && property.isPresent()) {
            Iterable<Object> propertyArgs = (Iterable<Object>) property.get();
            for (Object arg : propertyArgs) {
                args.add(arg.toString());
            }
        }
        return args;
    }

    private static @Nullable Object getProperty(@NonNull Object object, @NonNull String field) {
        try {
            Class<?> targetClass = object.getClass();
            Object checkResult = targetClass.getMethod(HAS_PROPERTY_METHOD_NAME, String.class)
                    .invoke(object, field);
            if (Boolean.TRUE.equals(checkResult)) {
                return targetClass.getMethod(GET_PROPERTY_METHOD_NAME, String.class)
                        .invoke(object, field);
            }
        } catch (Exception ignore) {

        }
        return null;
    }
}
