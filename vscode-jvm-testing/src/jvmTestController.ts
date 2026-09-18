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
import { JvmTestBuilds } from './jvmTestBuild';
import { JvmTestDiscovery } from './jvmTestDiscovery';
import { leafTests, planTestRun } from './jvmTestPlan';
import { JvmTestTree, RUNNABLE_TAG } from './jvmTestTree';
import { runTestGroup } from './jvmTestRun';
import { WorkspaceImportStateNotification } from './workspaceImport';

const CHANGE_DEBOUNCE_MS = 500;

export function registerJvmTestController(context: ExtensionContext, client: LanguageClient) {
  const controller = tests.createTestController('intellijJvmTest', 'JVM Tests');
  context.subscriptions.push(controller);

  const tree = new JvmTestTree(controller);
  const discovery = new JvmTestDiscovery(tree);

  const runHandler = async (request: TestRunRequest, token: CancellationToken): Promise<void> => {
    // A module holds no test until it is scanned, and a run of one has to find them.
    await discovery.resolveModules(
      request.include ?? [...controller.items].map(([, item]) => item),
    );
    const groups = planTestRun(request, controller.items, tree);
    if (groups.length === 0) {
      void window.showInformationMessage('There is no test to run.');
      return;
    }
    await discovery.resolveMethods(groups.flatMap((group) => group.items));
    const run = controller.createTestRun(request);
    const builds = new JvmTestBuilds({ run });
    const noDebug = request.profile?.kind !== TestRunProfileKind.Debug;
    try {
      for (const group of groups) {
        for (const item of group.items) {
          for (const test of leafTests(item)) run.enqueued(test);
        }
      }
      for (const group of groups) {
        if (token.isCancellationRequested) break;
        try {
          await runTestGroup({ group, run, tree, builds, noDebug, token });
        } catch (e) {
          const message = new TestMessage(e instanceof Error ? e.message : String(e));
          for (const item of group.items) run.errored(item, message);
        }
      }
    } finally {
      run.end();
    }
  };
  controller.createRunProfile('Run', TestRunProfileKind.Run, runHandler, true, RUNNABLE_TAG);
  controller.createRunProfile('Debug', TestRunProfileKind.Debug, runHandler, false, RUNNABLE_TAG);
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
  const sourceWatcher = workspace.createFileSystemWatcher('**/*.{java,kt}');
  context.subscriptions.push(
    sourceWatcher,
    sourceWatcher.onDidCreate((uri) => void discovery.refreshFile(uri)),
    sourceWatcher.onDidDelete((uri) => discovery.forgetFile(uri)),
  );

  // A file the user is looking at, or has just changed, is the one most worth keeping accurate.
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
      if (stateChange.newState === State.Running) watchWorkspaceImports(client);
    }),
    { dispose: () => importStateSubscription?.dispose() },
  );
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
