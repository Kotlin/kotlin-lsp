import { type ChildProcess, type ChildProcessWithoutNullStreams, spawn } from 'node:child_process';
import { isAbsolute } from 'node:path';
import {
  type CancellationToken,
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
import type { LanguageClient } from 'vscode-languageclient/node';
import {
  BUILD_TASK_NAME,
  BUILD_TASK_SOURCE,
  BUILD_TASK_TYPE,
  type BuildTarget,
  type BuildToRun,
  type FileBuildTarget,
  type ResolvedBuildCommand,
  buildToRun,
  createOutputLineSplitter,
  needsCmdShell,
  quoteCommandForCmd,
  quoteForCmd,
  taskBuildTargetOf,
} from './buildTaskModel';
import { getLspClient } from './lspClient';

export { BUILD_TASK_LABEL, BUILD_TASK_TYPE } from './buildTaskModel';

const RESOLVE_BUILD_COMMAND = 'intellij.java.resolveBuildCommand';
const RESOLVE_CLASS_DOCUMENT = 'intellij.java.resolveClassDocument';
const RESOLVE_REQUEST_TIMEOUT_MS = 30_000;

/**
 * The task's own contribution in tasks.json, declared in `contributes.taskDefinitions`.
 *
 * It names *what to compile*, for a build task the user defines themselves — a task cannot see the launch that
 * triggered it, so anything other than the launch handing its build over has to be said outright.
 *
 * A launch that references the extension's own `intellij: build` needs nothing here: `dap.ts` resolves that launch's
 * build before the task runs, class name and all. What is left for a task to say is which module, and a path says it
 * without a lookup that can fail — see [taskBuildTargetOf].
 */
interface IntellijBuildTaskDefinition extends TaskDefinition {
  /** Path to a file whose module to compile. Supports `${file}`, which VS Code substitutes before the task runs. */
  file?: string;
}

/** The shape `intellij.java.resolveClassDocument` answers with. */
interface ClassDocumentResponse {
  uri: string;
}

/**
 * The build the next lens-triggered task should run. The Run/Debug lens resolves it *before* starting the session
 * and only injects the task when there is one, so the task never has to report that there was nothing to do:
 * `preLaunchTask` is a label reference and gets no launch context, so this bridge is how the build learns what to
 * run — mirroring how the Red Hat java-debug task binds its target.
 *
 * A user-authored `preLaunchTask` leaves this unset; that build resolves itself from the active editor.
 *
 * One slot is enough because the lens serializes its launches (see `dap.ts`): the writer waits for the
 * previous launch, and therefore for the task that reads the slot, before setting it again.
 */
let pendingBuild: BuildToRun | undefined;

export function setPendingBuild(build: BuildToRun | undefined): void {
  pendingBuild = build;
}

/**
 * Whether a build is already waiting for the next task.
 *
 * The Run/Debug lens fills the slot itself before it starts a session, so the launch hook that fills it for
 * *user-authored* configurations has to leave that one alone — resolving it again would ask the server the same
 * question twice per lens launch and could answer differently while an import is in flight.
 */
export function hasPendingBuild(): boolean {
  return pendingBuild !== undefined;
}

/**
 * Asks the server what has to be built for [targetUri]'s module before a launch.
 *
 * Exported because the Run/Debug lens asks the same question before it decides whether to inject the build task at
 * all: a project with no build tool has nothing to compile with, and injecting a task that only says so opened a
 * terminal on every launch of one. A build-tool launch does not come here at all — it compiles as part of running.
 */
export async function resolveBuildCommand(
  client: LanguageClient,
  targetUri: string,
): Promise<ResolvedBuildCommand> {
  return await withTimeout(
    (token) =>
      client.sendRequest(
        'workspace/executeCommand',
        { command: RESOLVE_BUILD_COMMAND, arguments: [{ uri: targetUri }] },
        token,
      ) as Promise<ResolvedBuildCommand>,
    RESOLVE_REQUEST_TIMEOUT_MS,
    RESOLVE_BUILD_COMMAND,
  );
}

export function registerBuildTaskProvider(context: ExtensionContext): void {
  const provider: TaskProvider = {
    provideTasks: () => [createBuildTask()],
    // Tasks referenced from launch.json/tasks.json arrive here with the user's definition to complete.
    resolveTask: (task: Task) =>
      withPresentation(
        new Task(
          task.definition,
          task.scope ?? TaskScope.Workspace,
          BUILD_TASK_NAME,
          BUILD_TASK_SOURCE,
          buildExecution(),
        ),
      ),
  };
  context.subscriptions.push(tasks.registerTaskProvider(BUILD_TASK_TYPE, provider));
}

function createBuildTask(): Task {
  const definition: IntellijBuildTaskDefinition = { type: BUILD_TASK_TYPE };
  return withPresentation(
    new Task(definition, TaskScope.Workspace, BUILD_TASK_NAME, BUILD_TASK_SOURCE, buildExecution()),
  );
}

/**
 * A launch that builds first spends most of its wall time in the build, so that is what the panel shows while it
 * runs: `Always` makes the build's terminal the active one. `Silent` — revealing it only on failure — left the panel
 * on whatever terminal was there before, and a Maven build takes long enough that the launch looked hung until the
 * program's own terminal appeared.
 *
 * Handing focus *back* needs nothing here: VS Code runs `preLaunchTask` to completion before the session starts, and
 * the `runInTerminal` the server then issues reveals and focuses the program's terminal itself.
 *
 * `focus` is left off, so the build takes the panel but not the keyboard: it is not a terminal the user types into,
 * and the launch terminal is what focus should land on.
 *
 * Set on *both* task paths, and that is the point of having this function: a task referenced by label from
 * launch.json goes through `resolveTask`, which used to build a `Task` with no presentation options at all, so those
 * launches ignored whatever the provided task was configured with.
 */
function withPresentation(task: Task): Task {
  task.presentationOptions = { reveal: TaskRevealKind.Always, focus: false, clear: true };
  return task;
}

function buildExecution(): CustomExecution {
  // VS Code hands the callback the task's *resolved* definition — variables already substituted — which is how a
  // user-authored task's target reaches the build. `provideTasks`' own task names none, and does not need to: the
  // Run/Debug lens hands its build over out of band.
  return new CustomExecution(async (definition: TaskDefinition) => createBuildTerminal(definition));
}

function createBuildTerminal(definition: TaskDefinition): Pseudoterminal {
  const writeEmitter = new EventEmitter<string>();
  const closeEmitter = new EventEmitter<number>();
  // Set once the build tool is spawned, so terminating the task can take the build down with it.
  const running: { child?: ChildProcess; cancelled?: boolean } = {};
  return {
    onDidWrite: writeEmitter.event,
    onDidClose: closeEmitter.event,
    open: () => void runBuild(definition, writeEmitter, closeEmitter, running),
    // VS Code calls this when the task is terminated or its launch is abandoned. Without killing the child, the
    // build tool would keep running unsupervised — holding a daemon, file locks and CPU nobody is watching.
    close: () => {
      running.cancelled = true;
      running.child?.kill();
    },
  };
}

/**
 * Reports that the build could not even be determined. Deliberately exits 0: this is the same "we have no build
 * to run" situation as an unsupported project, and a launch the user asked for should not be refused because the
 * *build lookup* failed — only a build that actually ran and failed should stop it.
 */
function skipBuild(
  line: (text: string) => void,
  closeEmitter: EventEmitter<number>,
  reason: string,
): void {
  line(`${reason} Launching without building.`);
  closeEmitter.fire(0);
}

async function runBuild(
  definition: TaskDefinition,
  writeEmitter: EventEmitter<string>,
  closeEmitter: EventEmitter<number>,
  running: { child?: ChildProcess; cancelled?: boolean },
): Promise<void> {
  const line = (text: string) => writeEmitter.fire(`${text}\r\n`);
  // A launch resolves the build for its own target and leaves it here — the lens before it starts a session, and
  // `dap.ts`' launch hook for a user-authored configuration. Taken out of the slot before the await below, so a
  // launch that fills the slot meanwhile does not have it cleared out from under it.
  const pending = pendingBuild;
  pendingBuild = undefined;
  // A target the task itself names is the user's own word about what to compile, so it outranks the slot, which
  // whatever launch preceded this task filled. A definition naming none — the task the extension provides, and a
  // plain `intellij: build` — takes the slot, and resolves the active editor's module when there is nothing in it.
  const target = taskBuildTargetOf(definition);
  const build =
    (target ? undefined : pending) ?? (await resolveTargetBuild(target, line, closeEmitter));
  if (!build) return;
  if (running.cancelled) {
    closeEmitter.fire(1);
    return;
  }

  await runProcess({
    tool: build.tool,
    command: build.command,
    cwd: build.cwd,
    line,
    closeEmitter,
    running,
  });
}

/**
 * The build for the module [target] names — or for the active editor's, when nothing does — or `undefined` once it
 * has reported to [closeEmitter] that there is nothing to run, which is not a failure: see [skipBuild].
 */
async function resolveTargetBuild(
  target: FileBuildTarget | undefined,
  line: (text: string) => void,
  closeEmitter: EventEmitter<number>,
): Promise<BuildToRun | undefined> {
  const client = getLspClient();
  if (!client) {
    skipBuild(line, closeEmitter, 'IntelliJ LSP is not running.');
    return undefined;
  }
  let targetUri: string | undefined;
  try {
    targetUri = await buildTargetUri(client, target);
  } catch (e) {
    // A path is turned into a URI without asking the server anything, so nothing here is expected to throw; the guard
    // stays because a build that cannot name its target must skip rather than fail the launch behind it.
    skipBuild(line, closeEmitter, `Could not find ${describeTarget(target)}: ${errorMessage(e)}.`);
    return undefined;
  }
  if (!targetUri) {
    skipBuild(
      line,
      closeEmitter,
      'No file to build: the task names no "file", and there is no active editor.',
    );
    return undefined;
  }

  let resolved: ResolvedBuildCommand;
  try {
    resolved = await resolveBuildCommand(client, targetUri);
  } catch (e) {
    // Includes the everyday case of a target that has no module — e.g. F5 with launch.json itself focused, which
    // is how the active-editor fallback gets asked about a file the server knows nothing about. Naming the target
    // in the task definition is what avoids it; see [IntellijBuildTaskDefinition].
    skipBuild(line, closeEmitter, `Could not resolve the build command: ${errorMessage(e)}.`);
    return undefined;
  }

  const build = buildToRun(resolved);
  if (!build) {
    // Nothing to run: no build tool can compile this module — a pure-JPS project, or one whose tool cannot name it.
    // Report the server's reason, but do not block the launch.
    skipBuild(line, closeEmitter, resolved.reason ?? 'Nothing to build for this project.');
    return undefined;
  }
  return build;
}

interface RunProcessOptions {
  tool: string | undefined;
  command: string[];
  cwd: string | undefined;
  line: (text: string) => void;
  closeEmitter: EventEmitter<number>;
  running: { child?: ChildProcess; cancelled?: boolean };
}

function runProcess({
  tool,
  command,
  cwd,
  line,
  closeEmitter,
  running,
}: RunProcessOptions): Promise<void> {
  return new Promise<void>((resolve) => {
    const [executable, ...args] = command;
    line(`Building with ${tool ?? 'build tool'}: ${command.join(' ')}`);
    // Node emits *both* `error` and `close` when the executable cannot be spawned at all — no `mvn` on PATH and no
    // wrapper, which is exactly what `wrapperOrTool` falls back to. (Through a shell there is no spawn failure to
    // report: cmd.exe starts fine and exits non-zero instead, so that case arrives as a plain build failure.)
    // Firing twice writes a second "Build failed" into a pseudoterminal VS Code has already closed, so the first
    // one to arrive wins.
    let settled = false;
    const finish = (exitCode: number) => {
      settled = true;
      closeEmitter.fire(exitCode);
      resolve();
    };
    // On Windows the build tool is either a batch wrapper (`gradlew.bat` / `mvnw.cmd`) or a bare name to look up on
    // PATH, and neither can be spawned without a shell — see `needsCmdShell`. `shell: true` makes cmd.exe run it,
    // and then cmd.exe, not Node, splits the command line, so every part has to be quoted for it — every *argument*,
    // that is. The name goes through `quoteCommandForCmd`, which quotes a path and leaves a name to look up alone,
    // because quoting that one is what stops cmd.exe from looking it up.
    //
    // Node wraps the whole line in one more pair of quotes for `cmd /d /s /c`, and an unquoted name survives that:
    // `/s` strips exactly the first and the last quote of the string, which are the pair Node just added.
    const useShell = needsCmdShell(executable, process.platform);
    // Guarded because `spawn` reports some failures by *throwing* rather than by emitting `error`: an argument it
    // rejects outright — an empty executable, a `cwd` that is not a string — never reaches a child process to have
    // an event. This runs inside a promise executor, so such a throw would reject a promise nobody awaits for its
    // rejection (`open: () => void runBuild(…)`), leaving the pseudoterminal open on a build that will never report:
    // the task hangs, and the launch behind it waits on a task that cannot finish. Reported as a failed build
    // instead, which is what the `error` event two handlers down does with the failures that do arrive as events.
    //
    // Unreachable through the arguments this task builds today — a `.bat`/`.cmd` always gets `shell: true`, and
    // `buildToRun` drops an empty command — but the command is a server response, so the shape it arrives in is not
    // this file's to guarantee.
    // `ChildProcessWithoutNullStreams`, not `ChildProcess`: the streams are non-null only because neither call
    // passes `stdio`, and that is the overload's promise rather than the class's. Annotating the wider type is what
    // made `child.stdout` nullable below.
    let child: ChildProcessWithoutNullStreams;
    try {
      child = useShell
        ? spawn(quoteCommandForCmd(executable), args.map(quoteForCmd), { cwd, shell: true })
        : spawn(executable, args, { cwd, shell: false });
    } catch (e) {
      line(`Failed to start the build: ${errorMessage(e)}`);
      finish(1);
      return;
    }
    running.child = child;
    // Terminated while we were resolving the build command, so the kill in `close` found nothing to kill.
    if (running.cancelled) child.kill();
    // Normalize LF to CRLF (via `line`) so the pseudoterminal renders lines correctly, holding back the partial
    // line at the end of each chunk rather than emitting it as a line of its own.
    // One splitter per stream, not one shared: a splitter holds an unterminated tail, so feeding both streams into
    // the same one splices a half-written stdout line onto the next stderr chunk. Gradle writes progress to one and
    // warnings to the other, so that garbling is the normal case, not an edge one.
    const stdout = createOutputLineSplitter(line);
    const stderr = createOutputLineSplitter(line);
    const flushOutput = () => {
      stdout.flush();
      stderr.flush();
    };
    child.stdout.on('data', (chunk: Buffer) => stdout.push(chunk.toString()));
    child.stderr.on('data', (chunk: Buffer) => stderr.push(chunk.toString()));
    child.on('error', (e) => {
      if (settled) return;
      flushOutput();
      line(`Failed to start the build: ${errorMessage(e)}`);
      finish(1);
    });
    child.on('close', (code, signal) => {
      running.child = undefined;
      if (settled) return;
      flushOutput();
      // A killed build exits with a signal and no code; report a failure so the launch does not proceed as if
      // the build had succeeded.
      const exitCode = code ?? 1;
      if (signal != null) line(`Build stopped (${signal}).`);
      else
        line(
          exitCode === 0 ? 'Build finished successfully.' : `Build failed (exit code ${exitCode}).`,
        );
      finish(exitCode);
    });
  });
}

/**
 * The document URI of what to compile: what [target] names, or the active editor when nothing does.
 *
 * A named class is resolved by the server (`intellij.java.resolveClassDocument`, the same command a launch
 * configuration's target goes through) because only it knows which file declares the class — and, for a class
 * declared in more than one, which one this project means. Only a launch configuration names one: this is shared with
 * the launch hook in `dap.ts`, which resolves the build for a configuration's own target before its `preLaunchTask`
 * runs, while a task names a path (see [taskBuildTargetOf]).
 */
export async function buildTargetUri(
  client: LanguageClient,
  target: BuildTarget | undefined,
): Promise<string | undefined> {
  if (!target) return activeEditorProtocolUri(client);
  if (target.kind === 'file') {
    const file = fileUri(target.path);
    return file && client.code2ProtocolConverter.asUri(file);
  }
  const response = await withTimeout(
    (token) =>
      client.sendRequest(
        'workspace/executeCommand',
        { command: RESOLVE_CLASS_DOCUMENT, arguments: [{ fqn: target.mainClass }] },
        token,
      ) as Promise<ClassDocumentResponse>,
    RESOLVE_REQUEST_TIMEOUT_MS,
    RESOLVE_CLASS_DOCUMENT,
  );
  return response.uri;
}

/**
 * The file a task's `file` names. A relative path is resolved against the workspace folder — the active editor's, or
 * the first one — the way the Run/Debug lens picks the folder it launches in: a task definition is written by hand,
 * and `Uri.file` on a relative path yields a URI rooted at the filesystem root, which resolves to no module at all
 * and would report "nothing to build" for a path that is merely relative.
 */
function fileUri(path: string): Uri | undefined {
  if (isAbsolute(path)) return Uri.file(path);
  const folder =
    (window.activeTextEditor
      ? workspace.getWorkspaceFolder(window.activeTextEditor.document.uri)
      : undefined) ?? workspace.workspaceFolders?.[0];
  return folder && Uri.joinPath(folder.uri, path);
}

/** How the target reads in the terminal when the build could not be resolved for it. */
function describeTarget(target: FileBuildTarget | undefined): string {
  return target ? `file ${target.path}` : 'the file to build';
}

function activeEditorProtocolUri(client: LanguageClient): string | undefined {
  const editor = window.activeTextEditor;
  if (!editor) return undefined;
  return client.code2ProtocolConverter.asUri(editor.document.uri);
}

function errorMessage(e: unknown): string {
  if (e instanceof Error) return e.message;
  if (typeof e === 'string') return e;
  return String(e);
}

/**
 * Runs [start] with a cancellation token and rejects if it takes longer than [ms], cancelling the request on the
 * way out so the server stops working on an answer nobody is waiting for any more.
 */
function withTimeout<T>(
  start: (token: CancellationToken) => Promise<T>,
  ms: number,
  command: string,
): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const cancellation = new CancellationTokenSource();
    const settle = (finish: () => void) => {
      clearTimeout(timer);
      cancellation.dispose();
      finish();
    };
    const timer = setTimeout(() => {
      cancellation.cancel();
      settle(() => reject(new Error(`${command} timed out after ${ms}ms`)));
    }, ms);
    start(cancellation.token).then(
      (value) => settle(() => resolve(value)),
      (error) => settle(() => reject(error)),
    );
  });
}
