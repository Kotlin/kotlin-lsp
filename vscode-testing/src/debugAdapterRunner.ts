// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import { randomUUID } from 'node:crypto';
import { workspace } from 'vscode';
import { type StackFrameParser, TeamCityReader } from './teamCityReader';
import { launchAndCollectOutput, type TrackedDebugConfiguration } from './testDebugLaunch';
import type { TestRunInput, TestRunner } from './testLanguage';

export interface TestLaunchConfig {
  readonly type: string;
  readonly request: 'launch';
}

export interface DebugAdapterRunnerOptions<Launch extends TestLaunchConfig> {
  readonly mode: 'run' | 'debug';
  launches(input: TestRunInput): Promise<readonly Launch[]>;
  readonly stackFrames?: StackFrameParser;
  readonly spawn?: (
    config: TrackedDebugConfiguration,
    output: (text: string) => void,
  ) => Promise<number | undefined>;
}

export function debugAdapterRunner<Launch extends TestLaunchConfig>(
  options: DebugAdapterRunnerOptions<Launch>,
): TestRunner {
  const { mode, stackFrames } = options;
  return {
    async run(input) {
      const { group, report, token } = input;
      const launches = await options.launches(input);
      if (token.isCancellationRequested) return;
      if (launches.length === 0) throw new Error('The server found no way to run these tests.');
      const spawn =
        options.spawn ??
        ((config, output) =>
          launchAndCollectOutput({
            folder: workspace.getWorkspaceFolder(group.uri),
            config,
            output,
            mode,
            token,
          }));
      for (const launch of launches) {
        const config: TrackedDebugConfiguration = {
          ...launch,
          name: group.name,
          testRunToken: randomUUID(),
        };
        if (mode === 'run') config.internalConsoleOptions = 'neverOpen';
        const reader = new TeamCityReader(report, stackFrames);
        const exitCode = await spawn(config, reader.feed);
        reader.flush();
        if (token.isCancellationRequested) return;
        report.processExited(exitCode);
      }
    },
  };
}
