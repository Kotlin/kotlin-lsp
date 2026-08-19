/**
 * The parts of the build task that are pure logic: deciding whether there is a build to run at all, quoting a
 * command line for `cmd.exe`, and turning the build tool's output stream into terminal lines. Kept out of
 * `buildTask.ts` so they are testable without `vscode` — and they are the parts worth testing, because they fail in
 * ways a running build only shows on Windows, at chunk boundaries, or as a stray terminal.
 */

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
 * [quoteForCmd] on every part).
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
  // Bare name — no directory component, so it has to be looked up on PATH with PATHEXT applied.
  return !/[/\\]/.test(executable) && !/^[a-zA-Z]:/.test(executable);
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
