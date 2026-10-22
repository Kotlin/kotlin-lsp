// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import type { CancellationToken, TestRun, Uri } from 'vscode';
import type { ServerCapabilities } from 'vscode-languageclient/node';
import type { TestFailure } from './testFailure';
import type { TestItemDto, TestNodeId, UniqueTestNode } from './testProtocol';
import type { TestRunReport } from './testRunReport';

export interface TestLanguage<Profile extends string = string> {
  readonly controller: { readonly id: string; readonly label: string };
  readonly profiles: readonly TestProfile<Profile>[];
  readonly discovery: TestDiscovery;
  startRun(profile: Profile): TestRunner;
}

export interface TestProfile<Id extends string = string> {
  readonly id: Id;
  readonly label: string;
  readonly button: 'run' | 'debug' | 'coverage';
  readonly nodes: 'any' | 'tagged';
}

export interface TestDiscovery {
  readonly languageIds: ReadonlySet<string>;
  readonly sourceGlob: string;
  supportedBy(capabilities: ServerCapabilities): boolean;
  testsInFile(uri: Uri): Promise<TestItemDto[]>;
  modules(): Promise<string[]>;
  testsInModule(moduleName: string): Promise<TestItemDto[]>;
}

export interface TestRunner {
  /** Runs a group. A runner that knows more than the exit code can say what the run did with the nodes it skipped. */
  run(input: TestRunInput): Promise<UnreportedNodes | void>;
}

/**
 * What a node of a group gets when its run reported nothing for it. Without it, a test is skipped, and a requested
 * node is skipped after a clean exit and errored after a failed one.
 */
export interface UnreportedNodes {
  /**
   * The failure that kept [node] from running, `'none'` for a node the run left out, which keeps no state, or
   * undefined for the default.
   */
  outcomeOf(node: TestItemDto): TestFailure | 'none' | undefined;
}

export interface TestRunInput {
  readonly group: TestRunGroup;
  readonly report: TestRunReport;
  readonly token: CancellationToken;
  readonly run: TestRun;
}

export interface TestRunGroup {
  readonly moduleName: string | null;
  readonly testIds: readonly TestNodeId[];
  readonly uniqueIds: readonly UniqueTestNode[];
  readonly uri: Uri;
  readonly name: string;
}
