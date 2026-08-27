/**
 * The build task with the editor taken out: deciding whether there is a build to run at all, quoting a command line
 * for `cmd.exe`, running the build tool, and turning its output stream into terminal lines. Kept out of
 * `buildTask.ts` so it is testable without `vscode` — and it is the part worth testing, because it fails in ways a
 * running build only shows on Windows, at chunk boundaries, or as a stray terminal.
 *
 * The line drawn here is `vscode`, not purity: [runProcess] spawns a child and belongs on this side because nothing
 * about doing so wants the editor API — only the task that hosts it does. Its one concession to the other side is
 * [RunProcessOptions.close], a plain callback the task satisfies with its `EventEmitter`'s `fire`.
 */
import { type ChildProcess, type ChildProcessWithoutNullStreams, spawn } from 'node:child_process';

/**
 * Task type contributed via `contributes.taskDefinitions` in package.json, and the `"type"` a task written by hand in
 * tasks.json names.
 *
 * It says `build` because in tasks.json nothing else does: VS Code owns the `type` property of the schema it generates
 * from `taskDefinitions` (hovering it shows its own "The task type to customize"), so a contributed definition can
 * describe its *other* properties but never that one. A type of just `intellij` left a hand-written task with no
 * indication anywhere in it that it compiles anything.
 */
export const BUILD_TASK_TYPE = 'intellij_build';

/** Task name, which VS Code renders after the source. */
export const BUILD_TASK_NAME = 'build';

/**
 * Task source: a display name VS Code puts in front of the task's own ("e.g. 'gulp', 'npm'", per the `Task`
 * constructor), and deliberately not [BUILD_TASK_TYPE].
 *
 * Keeping the two apart is what let the type say `build` without the provided task turning into
 * `intellij_build: build`. It reads as the product it comes from, which is what the picker's other entries read as.
 */
export const BUILD_TASK_SOURCE = 'intellij';

/**
 * Label VS Code assigns to the provided task (`<source>: <name>`). The Run/Debug lens sets this as the created
 * config's `preLaunchTask`, the launch snippets in every product's package.json ship it as the default, and users can
 * reference it from their own launch.json.
 *
 * Here rather than in `buildTask.ts` so the contribution test can read it without `vscode`: the string is a contract
 * with three manifests that spell it out literally, and nothing else would notice them drifting from the code.
 */
export const BUILD_TASK_LABEL = `${BUILD_TASK_SOURCE}: ${BUILD_TASK_NAME}`;

/**
 * Quotes one argument for `cmd.exe`, which is what splits the command line when Node spawns a `.bat`/`.cmd`
 * wrapper with `shell: true`.
 *
 * Wrapping in double quotes is the whole job: inside them `cmd.exe` stops treating `& | < > ( ) ^` as
 * metacharacters, so escaping those *as well* passes the escape character through to the program. That is what
 * turned a workspace at `C:\Users\me\Projects (work)\demo` into `"C:\Users\me\Projects ^(work^)\demo\mvnw.cmd"`
 * and failed the build with "The system cannot find the path specified".
 *
 * `!` is *not* an exception, even though delayed expansion would consume it after quote removal: Node spawns
 * `cmd.exe /d /s /c` without `/v:on`, and delayed expansion is off by default, so `!` is already literal — while
 * `^` inside double quotes is not an escape character but a character of its own. Escaping it therefore *created*
 * the corruption it was meant to prevent: a workspace at `C:\hi!\demo` was handed to the build as
 * `C:\hi^!\demo\gradlew.bat`, which does not exist.
 *
 * A literal `"` cannot be passed through `cmd.exe` inside a quoted argument at all (it ends the quoted run
 * whatever precedes it), and no build-tool argument the server produces contains one — Windows paths cannot.
 */
export function quoteForCmd(arg: string): string {
  return `"${arg}"`;
}

/**
 * Whether spawning [executable] on [platform] has to go through `cmd.exe` (`shell: true`, and therefore
 * [quoteCommandForCmd] on the name and [quoteForCmd] on every argument).
 *
 * Two Windows-only reasons, and missing either one fails the build before it starts:
 *
 * - a `.bat`/`.cmd` wrapper (`gradlew.bat`, `mvnw.cmd`) is not an executable image, and Node refuses to spawn one
 *   without a shell (EINVAL since the CVE-2024-27980 fix);
 * - a *bare* tool name is what the server falls back to when the project has no wrapper (`gradle`, `mvn`), and on
 *   Windows those exist only as `gradle.bat` / `mvn.cmd` on `PATH`. Resolving a name through `PATHEXT` is the
 *   command processor's job, not `CreateProcess`'s, so `spawn('mvn', …, {shell: false})` fails with ENOENT even
 *   though the tool is installed and on `PATH` — which read as "no build tool" and sent users looking for a
 *   missing installation.
 *
 * An absolute path needs neither: it names the image outright, and only the extension decides the first case.
 */
export function needsCmdShell(executable: string, platform: string): boolean {
  if (platform !== 'win32') return false;
  if (/\.(bat|cmd)$/i.test(executable)) return true;
  return !namesAPath(executable);
}

/**
 * Whether [executable] names a *path* — it has a directory component or a drive — rather than a command name for
 * cmd.exe to look up on `PATH`.
 *
 * The distinction both [needsCmdShell] and [quoteCommandForCmd] turn on, which is why it is one function: a path
 * names the file outright and has to be quoted because it can contain spaces, while a name has to be looked up and
 * must *not* be quoted for that lookup to happen. Reading it two ways is what left the two disagreeing.
 */
function namesAPath(executable: string): boolean {
  return /[/\\]/.test(executable) || /^[a-zA-Z]:/.test(executable);
}

/**
 * Quotes the *command name* — the first element of the command line — for `cmd.exe`, which is not the same job as
 * quoting an argument with [quoteForCmd].
 *
 * A wrapper path is quoted, because that is where spaces turn up: `C:\Users\me\my project\mvnw.cmd`.
 *
 * A bare tool name is not, and that is the whole point of this function. It is what the server falls back to when the
 * project ships no wrapper — `mvn.cmd`, `gradle.bat` — and cmd.exe does not resolve a *quoted* bare name the way it
 * resolves a typed one: it reads the quoted text as a file specification instead of a command to look up, so it
 * neither searches `PATH` for it nor, where it does find the file, hands the batch file its own `%~dp0`. Both Gradle's
 * `gradle.bat` and Maven's `mvn.cmd` locate their installation through `%~dp0`, so quoting them ended the build at
 * their own "cannot find my installation" exit — code 1, and nothing about the project in the terminal (LSP-1716).
 *
 * A name that is neither a path nor safe to leave bare is quoted anyway. None of the names the server produces are
 * like that, but running the *leading word* of one as some other command would be a worse way to be wrong than
 * failing to find this one.
 *
 * Quote-only-when-needed is also the rule VS Code's own task system arrived at, which is worth knowing because this
 * task cannot borrow it: `ShellExecution`'s quoting lives in `TerminalTaskSystem._buildShellCommandLine`, which runs
 * a `needsQuotes` check — true only for a value containing a space — over the command before quoting it, and is
 * reachable only by handing VS Code a `ShellExecution`. A `CustomExecution` spawns the tool itself; see `buildTask.ts`
 * for why this task has to be one.
 */
export function quoteCommandForCmd(executable: string): string {
  if (namesAPath(executable)) return quoteForCmd(executable);
  // Whitespace splits the name, and `& | < > ( ) ^` are metacharacters cmd.exe acts on outside quotes. A `%` is
  // quoted for a different reason: quotes do not stop the expansion, but they do keep whatever it expands to as one
  // name rather than a name and some arguments.
  //
  // `!` is not in the set, for the reason [quoteForCmd] gives: Node spawns `cmd.exe` without `/v:on`, so delayed
  // expansion is off and `!` is already a literal character. Quoting it would only break the PATH lookup.
  if (/[\s"&|<>()^%]/.test(executable)) return quoteForCmd(executable);
  return executable;
}

export interface OutputLineSplitter {
  /** Feeds one stdout/stderr chunk, emitting every line it completes. */
  push(chunk: string): void;
  /** Emits whatever is left, for output whose last line has no terminator. */
  flush(): void;
}

/**
 * Splits a byte stream into lines, holding back the partial line at the end of each chunk.
 *
 * Splitting each chunk on its own instead emits a spurious empty line per chunk — a chunk ending in `\n` splits
 * into a trailing `''` — and a build arrives in many chunks, so the whole log renders double-spaced. It also
 * breaks any line that happens to straddle two chunks into two.
 *
 * Trailing `\r` is stripped so a CRLF stream does not double the carriage return once the caller re-adds one.
 */
export function createOutputLineSplitter(emit: (line: string) => void): OutputLineSplitter {
  let pending = '';
  return {
    push: (chunk: string) => {
      pending += chunk;
      let newline = pending.indexOf('\n');
      while (newline >= 0) {
        emit(pending.slice(0, newline).replace(/\r$/, ''));
        pending = pending.slice(newline + 1);
        newline = pending.indexOf('\n');
      }
    },
    flush: () => {
      if (pending.length === 0) return;
      emit(pending.replace(/\r$/, ''));
      pending = '';
    },
  };
}

/** The shape `intellij.java.resolveBuildCommand` answers with; see the server's `BuildCommandResponse`. */
export interface ResolvedBuildCommand {
  supported: boolean;
  reason?: string;
  tool?: string;
  cwd?: string;
  command?: string[];
}

/** A build that has something to run: the tool's own invocation, and where to run it. */
export interface BuildToRun {
  tool?: string;
  command: string[];
  cwd?: string;
}

/**
 * The build to run before a JVM launch, or `undefined` when there is nothing to run: no build tool can compile the
 * module — a pure-JPS project, or one whose tool cannot name it.
 *
 * Split out of `buildTask.ts` because two callers have to agree on it: the build task runs the result, and the
 * Run/Debug lens uses it to decide whether to inject the build task as `preLaunchTask` at all. Getting that second
 * one wrong is what opened a terminal on every launch only to print "nothing to build" into it.
 *
 * A `supported` answer with an empty command counts as nothing to run: a command that builds nothing would report
 * success while compiling nothing.
 */
export function buildToRun(resolved: ResolvedBuildCommand): BuildToRun | undefined {
  if (!resolved.supported) return undefined;
  if (!resolved.command || resolved.command.length === 0) return undefined;
  return { tool: resolved.tool, command: resolved.command, cwd: resolved.cwd };
}

/**
 * What a build was told to compile: a source file, or the class whose file the server looks up.
 *
 * Two shapes because the two sides that name a target speak differently. A *launch configuration* is read before VS
 * Code substitutes variables — see [launchBuildTargetOf] — so its `file` is often unusable and its class name is what
 * identifies it. A *task* is handed its definition already substituted, so a path is all it needs: see
 * [taskBuildTargetOf].
 */
export type FileBuildTarget = { kind: 'file'; path: string };
export type BuildTarget = FileBuildTarget | { kind: 'mainClass'; mainClass: string };

/**
 * The target a *task definition* names (`{"type": "intellij_build", "file": …}` in tasks.json), or `undefined` when it
 * names none and the active editor decides.
 *
 * A file, and only a file. A task compiles a *module*, and a path names one as directly as anything here can; the
 * class-name form exists for launch configurations, which cannot use a path (see [launchBuildTargetOf]), and it buys a
 * task nothing but a `resolveClassDocument` round-trip and a way to fail — a stale FQN, or a project still importing.
 *
 * VS Code substitutes variables in a task definition before the task runs, so `${file}` is how a task says "the active
 * editor's module" outright, and a value that still contains `${` here is a variable VS Code did not recognize.
 */
export function taskBuildTargetOf(definition: unknown): FileBuildTarget | undefined {
  const file = namedString(definition, 'file');
  return file ? { kind: 'file', path: file } : undefined;
}

/**
 * The target a *launch configuration* names, or `undefined` when it names none.
 *
 * Read from the configuration because a task cannot see the launch that triggered it: `preLaunchTask` is a label
 * reference and carries no context. So either the launch hands its build over before the task runs (`dap.ts`), or the
 * task's own definition says what to compile.
 *
 * `file` wins over `mainClass`, as it does for the launch's own target (`launchTargetUri` in `dap.ts`): it names a
 * document outright, while a class name has to be looked up and can be declared in more than one file.
 *
 * A value still containing `${` is ignored, and that is why `mainClass` is here at all: a launch configuration is read
 * *before* VS Code substitutes variables — the only point at which a build can still be handed to the task — so
 * `${file}` arrives literally, and resolving a module for a path spelled `${file}` would build something arbitrary or
 * nothing. The class name, which needs no substitution, is what such a configuration is identified by instead.
 */
export function launchBuildTargetOf(config: unknown): BuildTarget | undefined {
  const file = namedString(config, 'file');
  if (file) return { kind: 'file', path: file };
  const mainClass = namedString(config, 'mainClass');
  if (mainClass) return { kind: 'mainClass', mainClass };
  return undefined;
}

/**
 * The non-blank string at [key], or `undefined` when there is none to use.
 *
 * Both readers take user-authored JSON, so a value that is not a non-blank string is ignored rather than trusted or
 * rejected: falling through to the next candidate keeps a typo from failing the launch it precedes, which is the same
 * rule the rest of this task follows — only a build that ran and failed stops a launch. A value still containing `${`
 * is an unsubstituted variable, which names no file either.
 */
function namedString(source: unknown, key: string): string | undefined {
  const value = (source as Record<string, unknown> | undefined)?.[key];
  if (typeof value !== 'string') return undefined;
  if (value.includes('${')) return undefined;
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : undefined;
}

/**
 * The child a build is running in, so the task hosting it can take it down: without that, a terminated task leaves
 * the build tool running unsupervised — holding a daemon, file locks and CPU nobody is watching.
 */
export interface RunningBuild {
  /** Set once the build tool is spawned, and cleared once it has ended. */
  child?: ChildProcess;
  /** Set when the task was terminated, which can happen before there is any child to kill. */
  cancelled?: boolean;
}

/** What [runProcess] needs in order to run a build and say how it ended. */
export interface RunProcessOptions {
  tool: string | undefined;
  command: string[];
  cwd: string | undefined;
  /** Writes one line wherever the build is being shown. */
  line: (text: string) => void;
  /**
   * Called exactly once, with the code the build ended on. A callback rather than the task's `EventEmitter` so that
   * running a build needs nothing from `vscode`; see this file's header.
   */
  close: (exitCode: number) => void;
  running: RunningBuild;
}

/**
 * Runs [RunProcessOptions.command], streaming its output through [RunProcessOptions.line] and reporting the code it
 * ended on through [RunProcessOptions.close]. Resolves once it has reported, and never rejects: a build that could
 * not even start is a failed build, not a thrown one, because the only caller is a task with a terminal to write it
 * to.
 */
export function runProcess({
  tool,
  command,
  cwd,
  line,
  close,
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
      close(exitCode);
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

/** The message of whatever [e] turned out to be, for a report that has to say something either way. */
export function errorMessage(e: unknown): string {
  if (e instanceof Error) return e.message;
  if (typeof e === 'string') return e;
  return String(e);
}
