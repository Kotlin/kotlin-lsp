// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import { isAbsolute } from 'node:path';
import {
  CancellationTokenSource,
  CustomExecution,
  EventEmitter,
  type ExtensionContext,
  type Pseudoterminal,
  Task,
  type TaskDefinition,
  type TaskProvider,
  TaskRevealKind,
  TaskScope,
  tasks,
  Uri,
  window,
  workspace,
} from 'vscode';
import { type CancellationToken, NotificationType } from 'vscode-languageclient/node';
import type { LanguageClient } from 'vscode-languageclient/node';
import {
  BUILD_COMMAND,
  BUILD_OUTPUT_NOTIFICATION,
  BUILD_TASK_NAME,
  BUILD_TASK_SOURCE,
  BUILD_TASK_TYPE,
  type BuildArgs,
  type BuildOutput,
  BuildOutputRouter,
  type BuildResponse,
  buildTaskExitCode,
  errorMessage,
  taskBuildTargetOf,
} from './buildTaskModel';
import { getLspClient } from './lspClient';

export { BUILD_TASK_LABEL, BUILD_TASK_TYPE } from './buildTaskModel';

const buildOutputNotification = new NotificationType<BuildOutput>(BUILD_OUTPUT_NOTIFICATION);

/** The one router of build output lines; `subscribeBuildOutput` feeds it from the client. */
const router = new BuildOutputRouter();

/**
 * The task's own contribution in tasks.json, declared in `contributes.taskDefinitions`: a path to a file whose
 * module to compile. Supports `${file}`, which VS Code substitutes before the task runs. Without it the active
 * editor's module builds.
 */
interface IntellijBuildTaskDefinition extends TaskDefinition {
  file?: string;
}

/**
 * Routes the server's build output to the build it belongs to. Called once per language client, when it starts;
 * the returned disposable ends the subscription.
 */
export function subscribeBuildOutput(client: LanguageClient): { dispose(): void } {
  return client.onNotification(buildOutputNotification, (output) => router.deliver(output));
}

/**
 * Runs a build on the server and streams its lines to [line]; the answer is the server's. [token] cancels the
 * build. Exported for the test runner, which builds before it runs the tests.
 */
export async function runServerBuild(
  client: LanguageClient,
  args: Omit<BuildArgs, 'buildId'>,
  line: (output: BuildOutput) => void,
  token?: CancellationToken,
): Promise<BuildResponse> {
  const buildId = router.newBuildId();
  const stop = router.listen(buildId, line);
  try {
    return (await client.sendRequest(
      'workspace/executeCommand',
      { command: BUILD_COMMAND, arguments: [{ ...args, buildId }] },
      token,
    )) as BuildResponse;
  } finally {
    stop();
  }
}

export function registerBuildTaskProvider(context: ExtensionContext): void {
  const provider: TaskProvider = {
    provideTasks: () => [createBuildTask()],
    // Tasks referenced from tasks.json arrive here with the user's definition to complete.
    resolveTask: (task: Task) =>
      withPresentation(
        new Task(task.definition, task.scope ?? TaskScope.Workspace, BUILD_TASK_NAME, BUILD_TASK_SOURCE, buildExecution()),
      ),
  };
  context.subscriptions.push(tasks.registerTaskProvider(BUILD_TASK_TYPE, provider));
}

function createBuildTask(): Task {
  const definition: IntellijBuildTaskDefinition = { type: BUILD_TASK_TYPE };
  return withPresentation(new Task(definition, TaskScope.Workspace, BUILD_TASK_NAME, BUILD_TASK_SOURCE, buildExecution()));
}

/** The build takes the panel but not the keyboard: it is not a terminal the user types into. */
function withPresentation(task: Task): Task {
  task.presentationOptions = { reveal: TaskRevealKind.Always, focus: false, clear: true };
  return task;
}

function buildExecution(): CustomExecution {
  // VS Code hands the callback the task's *resolved* definition, variables already substituted.
  return new CustomExecution(async (definition: TaskDefinition) => createBuildTerminal(definition));
}

function createBuildTerminal(definition: TaskDefinition): Pseudoterminal {
  const writeEmitter = new EventEmitter<string>();
  const closeEmitter = new EventEmitter<number>();
  const cancellation = new CancellationTokenSource();
  return {
    onDidWrite: writeEmitter.event,
    onDidClose: closeEmitter.event,
    open: () => void runBuild(definition, writeEmitter, closeEmitter, cancellation.token),
    // VS Code calls this when the task is terminated. Cancelling the request cancels the build on the server.
    close: () => cancellation.cancel(),
  };
}

async function runBuild(
  definition: TaskDefinition,
  writeEmitter: EventEmitter<string>,
  closeEmitter: EventEmitter<number>,
  token: CancellationToken,
): Promise<void> {
  const line = (text: string) => writeEmitter.fire(`${text}\r\n`);
  const client = getLspClient();
  if (!client) {
    line('IntelliJ LSP is not running.');
    closeEmitter.fire(0);
    return;
  }
  const target = taskBuildTargetOf(definition);
  // A target names a module; without one the active editor's module builds, and without an editor the workspace.
  const file = target ? fileUri(target.path) : undefined;
  const uri = target
    ? file && client.code2ProtocolConverter.asUri(file)
    : activeEditorProtocolUri(client);
  try {
    const response = await runServerBuild(client, { uri, checkoutTools: true }, (output) => line(output.line), token);
    if (response.reason) line(response.reason);
    closeEmitter.fire(buildTaskExitCode(response));
  } catch (e) {
    line(`The build failed to start: ${errorMessage(e)}`);
    closeEmitter.fire(1);
  }
}

/**
 * The file a task's `file` names. A relative path is resolved against the workspace folder — the active editor's,
 * or the first one.
 */
function fileUri(path: string): Uri | undefined {
  if (isAbsolute(path)) return Uri.file(path);
  const folder =
    (window.activeTextEditor ? workspace.getWorkspaceFolder(window.activeTextEditor.document.uri) : undefined) ??
    workspace.workspaceFolders?.[0];
  return folder && Uri.joinPath(folder.uri, path);
}

function activeEditorProtocolUri(client: LanguageClient): string | undefined {
  const editor = window.activeTextEditor;
  if (!editor) return undefined;
  return client.code2ProtocolConverter.asUri(editor.document.uri);
}
