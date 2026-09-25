// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import { randomUUID } from 'node:crypto';
import { workspace } from 'vscode';
import { type StackFrameParser, TeamCityReader } from './teamCityReader';
import {
  launchAndCollectOutput,
  type TestProcessLaunch,
  type TrackedDebugConfiguration,
} from './testDebugLaunch';
import type { TestRunInput, TestRunner } from './testLanguage';

export interface TestLaunchConfig {
  readonly type: string;
  readonly request: 'launch';
}

export interface DebugAdapterRunnerOptions<Launch extends TestLaunchConfig> {
  readonly mode: 'run' | 'debug';
  launches(input: TestRunInput): Promise<readonly Launch[]>;
  readonly stackFrames?: StackFrameParser;
  readonly spawn?: (launch: TestProcessLaunch) => Promise<number | undefined>;
}

export function debugAdapterRunner<Launch extends TestLaunchConfig>(
  options: DebugAdapterRunnerOptions<Launch>,
): TestRunner {
  const { mode, stackFrames } = options;
  return {
    async run(input) {
      const { group, report, token, run } = input;
      const launches = await options.launches(input);
      if (token.isCancellationRequested) return;
      if (launches.length === 0) throw new Error('The server found no way to run these tests.');
      const spawn = options.spawn ?? launchAndCollectOutput;
      const folder = workspace.getWorkspaceFolder(group.uri);
      for (const launch of launches) {
        const config: TrackedDebugConfiguration = {
          ...launch,
          name: group.name,
          testRunToken: randomUUID(),
        };
        if (mode === 'run') config.internalConsoleOptions = 'neverOpen';
        const reader = new TeamCityReader(report, stackFrames);
        const exitCode = await spawn({
          folder,
          config,
          output: reader.feed,
          mode,
          token,
          testRun: run,
        });
        reader.flush();
        if (token.isCancellationRequested) return;
        report.processExited(exitCode);
      }
    },
  };
}
