// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import type { TestDiscoveryCommands, TestNodeId, UniqueTestNode } from '@jetbrains/vscode-testing';

export const JvmTestCommands = {
  discoverTestsInFile: 'intellij.jvm.discoverTestsInFile',
  discoverTestModules: 'intellij.jvm.discoverTestModules',
  discoverTestsInModule: 'intellij.jvm.discoverTestsInModule',
} as const satisfies TestDiscoveryCommands;

export const RESOLVE_TEST_LAUNCH_COMMAND = 'intellij.jvm.resolveTestLaunch';
export const JVM_LANGUAGE_IDS: ReadonlySet<string> = new Set(['java', 'kotlin']);
export const jvmSuiteLocation = (binaryClassName: string): string =>
  `java:suite://${binaryClassName}`;

/**
 * The runtime paths a JVM launch of a file's module runs with. Owned by `dap/`
 * (`LSResolveLaunchCommandDescriptorProvider`), not by the testing providers — listed here only because a
 * test launch needs the classpath out of it.
 */
export const RESOLVE_LAUNCH_COMMAND = 'intellij.java.resolveLaunch';

/** Mirrors the server's answer to [RESOLVE_LAUNCH_COMMAND]. */
export interface JvmTestRunPaths {
  classpath?: string[];
  modulePath?: string[];
  vmArgs?: string[];
}

/** Mirrors the server's `TestLaunchRequest`. */
export interface JvmTestLaunchRequest {
  testIds: TestNodeId[];
  uniqueIds: UniqueTestNode[];
}

/** Mirrors the server's `TestLaunch`: one process to start. */
export interface JvmTestLaunch {
  mainClass: string;
  args: string[];
  runtimeClasspath: string[];
  runtimeModulePath?: string[];
}
