// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
/**
 * The build task with the editor taken out: what a task definition names, the wire shapes of the server's
 * `intellij.build` command and its `intellij/buildOutput` stream, and the routing of that stream to the build that
 * asked for it. It stays out of `buildTask.ts` so a test can use it without `vscode`.
 *
 * The server runs the build tool; nothing here spawns a process.
 */

/**
 * Task type contributed via `contributes.taskDefinitions` in package.json, and the `"type"` a task written by hand in
 * tasks.json names. It says `build` because in tasks.json nothing else does: VS Code owns the `type` property of the
 * schema it generates from `taskDefinitions`.
 */
export const BUILD_TASK_TYPE = 'intellij_build';

/** Task name, which VS Code renders after the source. */
export const BUILD_TASK_NAME = 'build';

/** Task source: a display name VS Code puts in front of the task's own, and deliberately not [BUILD_TASK_TYPE]. */
export const BUILD_TASK_SOURCE = 'intellij';

/**
 * Label VS Code assigns to the provided task (`<source>: <name>`). Users reference it from their own tasks.json; the
 * launch snippets in the products' package.json used to ship it as `preLaunchTask`, which a launch no longer needs:
 * the server builds as part of the launch.
 */
export const BUILD_TASK_LABEL = `${BUILD_TASK_SOURCE}: ${BUILD_TASK_NAME}`;

/** The server command that builds on the server and streams its output; see the server's `LSBuildDescriptorProvider`. */
export const BUILD_COMMAND = 'intellij.build';

/** The notification the server streams a build's output with. */
export const BUILD_OUTPUT_NOTIFICATION = 'intellij/buildOutput';

/** The arguments of [BUILD_COMMAND]. Without [uri] the whole workspace builds. */
export interface BuildArgs {
  uri?: string;
  testScope?: boolean;
  /** The id the output lines of this build carry, so several builds tell their lines apart. */
  buildId: string;
  /** Whether a module no build tool imported may be built by a tool present in the checkout. */
  checkoutTools?: boolean;
}

/** One line of a build's output; see the server's `BuildOutputParams`. */
export interface BuildOutput {
  buildId: string;
  category: 'stdout' | 'stderr' | 'system';
  line: string;
}

/** The answer of [BUILD_COMMAND]: the exit code of the tool, or none with the reason nothing could build. */
export interface BuildResponse {
  exitCode?: number;
  reason?: string;
  unsupported?: string[];
}

/**
 * Routes [BUILD_OUTPUT_NOTIFICATION] lines to the build they belong to. One instance per client, fed by the one
 * notification subscription; a build registers its sink for the time it runs.
 */
export class BuildOutputRouter {
  private readonly sinks = new Map<string, (output: BuildOutput) => void>();
  private counter = 0;

  /** A build id no running build uses. */
  newBuildId(): string {
    this.counter += 1;
    return `build-${this.counter}`;
  }

  /** Registers [sink] for the lines of [buildId]; the returned function unregisters it. */
  listen(buildId: string, sink: (output: BuildOutput) => void): () => void {
    this.sinks.set(buildId, sink);
    return () => {
      if (this.sinks.get(buildId) === sink) this.sinks.delete(buildId);
    };
  }

  /** Delivers [output] to the sink of its build; a line of a build nobody listens to is dropped. */
  deliver(output: BuildOutput): void {
    this.sinks.get(output.buildId)?.(output);
  }
}

/**
 * The exit code a build task ends with for [response]: the tool's own, or 0 when nothing could build.
 *
 * Deliberately 0 in the second case: "nothing to build" is not a failure, and a task that referenced the build
 * from tasks.json should not fail a user's workflow because the project has no build tool. Only a build that ran
 * and failed fails the task.
 */
export function buildTaskExitCode(response: BuildResponse): number {
  return response.exitCode ?? 0;
}

/**
 * What a build was told to compile: a source file. A task compiles a module, and a path names one as directly as
 * anything here can.
 */
export type FileBuildTarget = { kind: 'file'; path: string };

/**
 * The target a *task definition* names (`{"type": "intellij_build", "file": …}` in tasks.json), or `undefined` when it
 * names none and the active editor decides. VS Code substitutes variables in a task definition before the task runs,
 * so `${file}` is how a task says "the active editor's module" outright, and a value that still contains `${` here
 * is a variable VS Code did not recognize.
 */
export function taskBuildTargetOf(definition: unknown): FileBuildTarget | undefined {
  const file = namedString(definition, 'file');
  return file ? { kind: 'file', path: file } : undefined;
}

/** The non-blank string at [key] without an unsubstituted variable, or `undefined`. */
function namedString(source: unknown, key: string): string | undefined {
  const value = (source as Record<string, unknown> | undefined)?.[key];
  if (typeof value !== 'string') return undefined;
  if (value.includes('${')) return undefined;
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : undefined;
}

/** The message of [e], whatever [e] is, so a report can always say something. */
export function errorMessage(e: unknown): string {
  if (e instanceof Error) return e.message;
  if (typeof e === 'string') return e;
  return String(e);
}
