// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import {
  type CancellationToken,
  type Disposable,
  type ExtensionContext,
  TestMessage,
  TestRunProfileKind,
  type TestRunRequest,
  tests,
  type TextDocument,
  window,
  workspace,
} from 'vscode';
import { subscribeToClientEvent } from '@jetbrains/vscode-extension-core';
import { type LanguageClient, State } from 'vscode-languageclient/node';
import { LanguageFileCoverage } from './testCoverage';
import { lspDiscoveryServer, TestTreeDiscovery } from './testDiscovery';
import { leafTests } from './testItems';
import type { TestLanguage, TestProfile } from './testLanguage';
import { TestProfileTags } from './testProfileTags';
import { GroupReport } from './testRunReport';
import { TestTree } from './testTree';
import { WorkspaceImportStateNotification } from './workspaceImport';

const CHANGE_DEBOUNCE_MS = 500;

export function registerTestController<Profile extends string>(
  context: ExtensionContext,
  client: LanguageClient,
  language: TestLanguage<Profile>,
) {
  const controller = tests.createTestController(language.controller.id, language.controller.label);
  context.subscriptions.push(controller);

  const tags = new TestProfileTags(language.profiles);
  const tree = new TestTree(controller, tags);
  const discovery = new TestTreeDiscovery(tree, language.discovery, lspDiscoveryServer);

  const runHandler =
    (profile: TestProfile<Profile>) =>
    async (request: TestRunRequest, token: CancellationToken): Promise<void> => {
      const groups = await discovery.planRun(request, controller.items, tags.of(profile));
      if (groups.length === 0) {
        void window.showInformationMessage('There is no test to run.');
        return;
      }
      const run = controller.createTestRun(request);
      try {
        const runner = language.startRun(profile.id);
        for (const group of groups) {
          for (const item of group.items) {
            for (const test of leafTests(item)) run.enqueued(test);
          }
        }
        for (const group of groups) {
          if (token.isCancellationRequested) break;
          const report = new GroupReport({ run, tree, group });
          try {
            await runner.run({ group, report, token, run });
            if (!token.isCancellationRequested) report.conclude();
          } catch (e) {
            const message = new TestMessage(e instanceof Error ? e.message : String(e));
            for (const item of group.items) run.errored(item, message);
          }
        }
      } finally {
        run.end();
      }
    };
  const buttonsWithDefault = new Set<TestProfile['button']>();
  for (const profile of language.profiles) {
    const runProfile = controller.createRunProfile(
      profile.label,
      kindOf(profile.button),
      runHandler(profile),
      !buttonsWithDefault.has(profile.button),
      tags.of(profile),
    );
    buttonsWithDefault.add(profile.button);
    // Any profile may measure coverage: a debug run can, and so can one with a profiler.
    runProfile.loadDetailedCoverage = async (_run, coverage, token) =>
      coverage instanceof LanguageFileCoverage ? coverage.details(token) : [];
  }
  controller.refreshHandler = () => discovery.refreshWorkspace();
  controller.resolveHandler = (item) =>
    item ? discovery.resolve(item) : discovery.refreshWorkspace();

  const refreshFileSoon = debounce(
    (doc: TextDocument) => void discovery.refreshFile(doc),
    CHANGE_DEBOUNCE_MS,
    (doc) => doc.uri.toString(),
  );

  // A file created or deleted outside the editor still belongs in (or out of) the tree; edits to an
  // open file are covered by the document events below.
  const sourceWatcher = workspace.createFileSystemWatcher(language.discovery.sourceGlob);
  context.subscriptions.push(
    sourceWatcher,
    sourceWatcher.onDidCreate((uri) => void discovery.refreshFile(uri)),
    sourceWatcher.onDidDelete((uri) => discovery.forgetFile(uri)),
  );

  // A file the user is looking at, or has just changed, is the one most worth keeping accurate.
  // The editors already open when the controller registers get no event, so they are scanned here.
  const refreshVisibleEditors = (): void => {
    for (const editor of window.visibleTextEditors) void discovery.refreshFile(editor.document);
  };
  refreshVisibleEditors();
  context.subscriptions.push(
    window.onDidChangeActiveTextEditor((editor) => {
      if (editor) void discovery.refreshFile(editor.document);
    }),
    workspace.onDidOpenTextDocument((doc) => void discovery.refreshFile(doc)),
    workspace.onDidSaveTextDocument((doc) => void discovery.refreshFile(doc)),
    workspace.onDidChangeTextDocument((e) => refreshFileSoon(e.document)),
  );

  // The modules of a workspace exist only once its import has run, and a server restart re-imports it.
  let importStateSubscription: Disposable | undefined;
  const watchWorkspaceImports = (client: LanguageClient): void => {
    importStateSubscription?.dispose();
    importStateSubscription = client.onNotification(
      WorkspaceImportStateNotification,
      () => void discovery.refreshWorkspace(),
    );
    void discovery.refreshWorkspace();
  };
  watchWorkspaceImports(client);
  context.subscriptions.push(
    workspace.onDidChangeWorkspaceFolders(() => void discovery.refreshWorkspace()),
    subscribeToClientEvent((client, stateChange) => {
      if (stateChange.newState !== State.Running) return;
      watchWorkspaceImports(client);
      refreshVisibleEditors();
    }),
    { dispose: () => importStateSubscription?.dispose() },
  );
}

function kindOf(button: TestProfile['button']): TestRunProfileKind {
  switch (button) {
    case 'run':
      return TestRunProfileKind.Run;
    case 'debug':
      return TestRunProfileKind.Debug;
    case 'coverage':
      return TestRunProfileKind.Coverage;
  }
}

function debounce<T>(
  action: (argument: T) => void,
  delayMs: number,
  keyOf: (argument: T) => string,
): (argument: T) => void {
  const timers = new Map<string, NodeJS.Timeout>();
  return (argument) => {
    const key = keyOf(argument);
    clearTimeout(timers.get(key));
    timers.set(
      key,
      setTimeout(() => {
        timers.delete(key);
        action(argument);
      }, delayMs),
    );
  };
}
