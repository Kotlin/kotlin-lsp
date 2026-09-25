// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import { getLspClient, getOutputChannel } from '@jetbrains/vscode-extension-core';
import {
  type BuildToRun,
  buildToRun,
  errorMessage,
  resolveBuildCommand,
  type ResolvedBuildCommand,
  type RunningBuild,
  runProcess,
} from '@jetbrains/vscode-extension-core/build';
import type { TestRunInput, TestRunReport } from '@jetbrains/vscode-testing';

export type TestBuildOutcome = 'built' | 'failed' | 'skipped';

type SpawnBuild = (
  build: BuildToRun,
  line: (text: string) => void,
  running: RunningBuild,
) => Promise<number>;

export interface JvmTestBuildsOptions {
  /** Overridden in tests, where there is no server to ask and no build tool to spawn. */
  resolve?: (targetUri: string) => Promise<ResolvedBuildCommand>;
  spawn?: SpawnBuild;
  log?: (message: string) => void;
}

export class JvmTestBuilds {
  private readonly outcomes = new Map<string, TestBuildOutcome>();
  private readonly resolve: (targetUri: string) => Promise<ResolvedBuildCommand>;
  private readonly spawn: SpawnBuild;
  private readonly log: (message: string) => void;

  constructor(options: JvmTestBuildsOptions = {}) {
    this.resolve = options.resolve ?? defaultResolve;
    this.spawn =
      options.spawn ??
      ((build, line, running) =>
        new Promise<number>((resolve) => {
          void runProcess({
            tool: build.tool,
            command: build.command,
            cwd: build.cwd,
            env: build.env,
            line,
            close: resolve,
            running,
          });
        }));
    this.log = options.log ?? ((message) => getOutputChannel().appendLine(message));
  }

  async ensureBuilt({ group, report, token }: TestRunInput): Promise<TestBuildOutcome> {
    if (token.isCancellationRequested) return 'skipped';

    let resolved: ResolvedBuildCommand;
    try {
      resolved = await this.resolve(group.uri.toString());
    } catch (e) {
      return this.skip(report, `Could not resolve the build command: ${errorMessage(e)}.`);
    }

    const build = buildToRun(resolved);
    if (!build) return this.skip(report, resolved.reason ?? 'Nothing to build for this project.');

    const key = [build.cwd ?? '', ...build.command].join(' ');
    const done = this.outcomes.get(key);
    if (done) return done;

    const running: RunningBuild = { cancelled: token.isCancellationRequested };
    const cancellation = token.onCancellationRequested(() => {
      running.cancelled = true;
      running.child?.kill();
    });
    let exitCode: number;
    try {
      exitCode = await this.spawn(build, (text) => line(report, text), running);
    } finally {
      cancellation.dispose();
    }
    if (running.cancelled) return 'skipped';
    const outcome: TestBuildOutcome = exitCode === 0 ? 'built' : 'failed';
    this.outcomes.set(key, outcome);
    return outcome;
  }

  private skip(report: TestRunReport, reason: string): TestBuildOutcome {
    line(report, `${reason} Running the classes that are already compiled.`);
    this.log(`[jvmTest] ${reason}`);
    return 'skipped';
  }
}

function line(report: TestRunReport, text: string): void {
  report.output({ text: `${text}\n` });
}

function defaultResolve(targetUri: string): Promise<ResolvedBuildCommand> {
  const client = getLspClient();
  if (!client) throw new Error('IntelliJ LSP is not running');
  return resolveBuildCommand(client, targetUri);
}
