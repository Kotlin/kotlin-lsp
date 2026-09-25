// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import type { CancellationToken, TestRun, Uri } from 'vscode';
import type { ServerCapabilities } from 'vscode-languageclient/node';
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
  run(input: TestRunInput): Promise<void>;
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
