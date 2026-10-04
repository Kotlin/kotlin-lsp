// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import { getLspClient, getOutputChannel } from '@jetbrains/vscode-extension-core';
import {
  type BuildOutput,
  type BuildResponse,
  errorMessage,
  runServerBuild,
} from '@jetbrains/vscode-extension-core/build';
import type { CancellationToken } from 'vscode';
import type { TestRunInput, TestRunReport } from '@jetbrains/vscode-testing';

export type TestBuildOutcome = 'built' | 'failed' | 'skipped';

/** Builds the module of [uri] on the server and streams its lines to [line]; the server's answer is the result. */
export type ServerBuild = (
  uri: string,
  line: (output: BuildOutput) => void,
  token: CancellationToken,
) => Promise<BuildResponse>;

export interface JvmTestBuildsOptions {
  /** Overridden in tests, where there is no server to ask. */
  build?: ServerBuild;
  log?: (message: string) => void;
}

/**
 * Builds the module of a test group before its tests run, once per module per test run. The server builds with
 * the tool of the module and streams the output into the test results.
 */
export class JvmTestBuilds {
  private readonly outcomes = new Map<string, TestBuildOutcome>();
  private readonly build: ServerBuild;
  private readonly log: (message: string) => void;

  constructor(options: JvmTestBuildsOptions = {}) {
    this.build = options.build ?? defaultBuild;
    this.log = options.log ?? ((message) => getOutputChannel().appendLine(message));
  }

  async ensureBuilt({ group, report, token }: TestRunInput): Promise<TestBuildOutcome> {
    if (token.isCancellationRequested) return 'skipped';
    const key = group.uri.toString();
    const done = this.outcomes.get(key);
    if (done) return done;

    let response: BuildResponse;
    try {
      response = await this.build(key, (output) => line(report, output.line), token);
    } catch (e) {
      if (token.isCancellationRequested) return 'skipped';
      return this.skip(report, `The build could not start: ${errorMessage(e)}.`);
    }
    if (token.isCancellationRequested) return 'skipped';
    if (response.exitCode === undefined) return this.skip(report, response.reason ?? 'Nothing to build for this project.');
    const outcome: TestBuildOutcome = response.exitCode === 0 ? 'built' : 'failed';
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

const defaultBuild: ServerBuild = (uri, line, token) => {
  const client = getLspClient();
  if (!client) throw new Error('IntelliJ LSP is not running');
  return runServerBuild(client, { uri, testScope: true }, line, token);
};
