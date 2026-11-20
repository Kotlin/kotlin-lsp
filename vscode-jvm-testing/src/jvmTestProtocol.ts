// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import type { TestDiscoveryCommands, TestNodeId, UniqueTestNode } from '@jetbrains/vscode-testing';

export const JvmTestCommands = {
  discoverTestsInFile: 'intellij.jvm.discoverTestsInFile',
  discoverTestModules: 'intellij.jvm.discoverTestModules',
  discoverTestsInModule: 'intellij.jvm.discoverTestsInModule',
} as const satisfies TestDiscoveryCommands;

export const RESOLVE_TEST_LAUNCH_COMMAND = 'intellij.jvm.resolveTestLaunch';
export const LOAD_TEST_COVERAGE_COMMAND = 'intellij.jvm.loadTestCoverage';
export const JVM_LANGUAGE_IDS: ReadonlySet<string> = new Set(['java', 'kotlin']);
export const jvmSuiteLocation = (binaryClassName: string): string =>
  `java:suite://${binaryClassName}`;

export type JvmTestProfile = 'run' | 'debug' | 'coverage';

/** Mirrors the server's `TestLaunchRequest`. */
export interface JvmTestLaunchRequest {
  testIds: TestNodeId[];
  uniqueIds: UniqueTestNode[];
  /** The tag filters of the run. A `!` prefix excludes. Only the JUnit Platform runners use them. */
  tags: string[];
  /** A launch of the `coverage` profile measures the coverage, see `JvmTestLaunch.coverageData`. */
  profile: JvmTestProfile;
}

/** Mirrors the server's `TestLaunch`: one process to start. */
export interface JvmTestLaunch {
  mainClass: string;
  args: string[];
  runtimeClasspath: string[];
  runtimeModulePath?: string[];
  file?: string | null;
  /** The VM options that the launch needs, added to those of the test configuration. */
  vmArgs?: string[];
  /**
   * The file that gets the coverage of the launch. The client gives it back in a `JvmTestCoverageRequest`, and
   * deletes it when the run finishes.
   */
  coverageData?: string | null;
}

/** Mirrors the server's `TestCoverageRequest`. */
export interface JvmTestCoverageRequest {
  /** The `coverageData` of the launches of one run, up to the launches of the module of `file`. */
  dataFiles: string[];
  /** The URI of a file of the module to report. The server reports this module and the modules that it depends on. */
  file: string;
}

/** Mirrors the server's `TestFileCoverage`: the coverage of one source file. */
export interface JvmFileCoverage {
  uri: string;
  lines: {
    /** The text of the line, without its indent, as an LSP range. */
    range: { start: { line: number; character: number }; end: { line: number; character: number } };
    covered: boolean;
    branches: number;
    coveredBranches: number;
  }[];
  methods: {
    name: string;
    /** The name in the declaration of the method, or the first line of the code of a method without a declaration. */
    range: { start: { line: number; character: number }; end: { line: number; character: number } };
    covered: boolean;
  }[];
}
