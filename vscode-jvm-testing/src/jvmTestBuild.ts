import type { CancellationToken, TestRun, Uri } from 'vscode';
import { getLspClient, getOutputChannel } from '@jetbrains/vscode-extension-core';
import {
  type BuildToRun,
  buildToRun,
  errorMessage,
  type ResolvedBuildCommand,
  resolveBuildCommand,
  runProcess,
  type RunningBuild,
} from '@jetbrains/vscode-extension-core/build';

export type JvmTestBuildOutcome = 'built' | 'skipped' | 'failed';

type SpawnBuild = (
  build: BuildToRun,
  line: (text: string) => void,
  running: RunningBuild,
) => Promise<number>;

export interface JvmTestBuildsOptions {
  run: TestRun;
  /** Overridden in tests, where there is no server to ask and no build tool to spawn. */
  resolve?: (targetUri: string) => Promise<ResolvedBuildCommand>;
  spawn?: SpawnBuild;
  log?: (message: string) => void;
}

export class JvmTestBuilds {
  private readonly outcomes = new Map<string, JvmTestBuildOutcome>();
  private readonly run: TestRun;
  private readonly resolve: (targetUri: string) => Promise<ResolvedBuildCommand>;
  private readonly spawn: SpawnBuild;
  private readonly log: (message: string) => void;

  constructor(options: JvmTestBuildsOptions) {
    this.run = options.run;
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

  async ensureBuilt(uri: Uri, token: CancellationToken): Promise<JvmTestBuildOutcome> {
    if (token.isCancellationRequested) return 'skipped';

    let resolved: ResolvedBuildCommand;
    try {
      resolved = await this.resolve(uri.toString());
    } catch (e) {
      return this.skip(`Could not resolve the build command: ${errorMessage(e)}.`);
    }

    const build = buildToRun(resolved);
    if (!build) return this.skip(resolved.reason ?? 'Nothing to build for this project.');

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
      exitCode = await this.spawn(build, (text) => this.line(text), running);
    } finally {
      cancellation.dispose();
    }
    const outcome: JvmTestBuildOutcome = exitCode === 0 ? 'built' : 'failed';
    this.outcomes.set(key, outcome);
    return outcome;
  }

  private skip(reason: string): JvmTestBuildOutcome {
    this.line(`${reason} Running the classes that are already compiled.`);
    this.log(`[jvmTest] ${reason}`);
    return 'skipped';
  }

  private line(text: string): void {
    this.run.appendOutput(`${text}\r\n`);
  }
}

function defaultResolve(targetUri: string): Promise<ResolvedBuildCommand> {
  const client = getLspClient();
  if (!client) throw new Error('IntelliJ LSP is not running');
  return resolveBuildCommand(client, targetUri);
}
