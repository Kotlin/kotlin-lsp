// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { type CancellationToken, Uri } from 'vscode';
import { debugAdapterRunner, type TestLaunchConfig } from './debugAdapterRunner';
import type { TrackedDebugConfiguration } from './testDebugLaunch';
import type { TestRunGroup } from './testLanguage';
import type { TestRunReport } from './testRunReport';

interface FakeLaunch extends TestLaunchConfig {
  readonly mainClass: string;
  readonly console?: string;
}

const launch = (mainClass: string, console?: string): FakeLaunch => ({
  type: 'fake',
  request: 'launch',
  mainClass,
  console,
});

const GROUP: TestRunGroup = {
  moduleName: 'moduleA',
  testIds: [],
  uniqueIds: [],
  uri: Uri.parse('file:///p/moduleA/src/FooTest.java'),
  name: 'Run FooTest',
};

/** The process side of a report: what each process printed and how it exited, as `<call> <value>`. */
function recordingReport(): { readonly report: TestRunReport; readonly calls: string[] } {
  const calls: string[] = [];
  const report: TestRunReport = {
    atLocation: () => undefined,
    runtimeChild: () => undefined,
    suiteStarted: () => {},
    testStarted: () => {},
    passed: () => {},
    failed: () => {},
    errored: () => {},
    skipped: () => {},
    suiteFinished: () => {},
    output: () => {},
    processOutput: (line) => calls.push(`line ${line}`),
    processExited: (exitCode) => calls.push(`exited ${exitCode}`),
    coverage: () => {},
  };
  return { report, calls };
}

function cancellation() {
  const token = { isCancellationRequested: false } as { isCancellationRequested: boolean };
  return {
    token: token as unknown as CancellationToken,
    cancel: () => (token.isCancellationRequested = true),
  };
}

/** A runner of [launches] whose processes print [output] and exit with [exitCode], recording each config. */
function makeRunner({
  mode = 'run',
  launches = [launch('FooTest')],
  output = '',
  exitCode = 0,
  whileRunning = () => {},
}: {
  readonly mode?: 'run' | 'debug';
  readonly launches?: readonly FakeLaunch[];
  readonly output?: string;
  readonly exitCode?: number;
  readonly whileRunning?: () => void;
} = {}) {
  const configs: TrackedDebugConfiguration[] = [];
  const runner = debugAdapterRunner<FakeLaunch>({
    mode,
    launches: async () => launches,
    spawn: async (config, print) => {
      configs.push(config);
      print(output);
      whileRunning();
      return exitCode;
    },
  });
  return { runner, configs };
}

describe('running a group through a debug adapter', () => {
  test('launches each process under the name of the group, with a token of its own', async () => {
    const { runner, configs } = makeRunner({ launches: [launch('FooTest'), launch('BarTest')] });
    const { report } = recordingReport();

    await runner.run({ group: GROUP, report, token: cancellation().token });

    assert.deepEqual(
      configs.map(({ mainClass, name, type, request }) => ({ mainClass, name, type, request })),
      [
        { mainClass: 'FooTest', name: 'Run FooTest', type: 'fake', request: 'launch' },
        { mainClass: 'BarTest', name: 'Run FooTest', type: 'fake', request: 'launch' },
      ],
    );
    assert.notEqual(configs[0].testRunToken, configs[1].testRunToken);
  });

  test('passes on the console the language chose for its adapter', async () => {
    const { runner, configs } = makeRunner({ launches: [launch('FooTest', 'internalConsole')] });

    await runner.run({
      group: GROUP,
      report: recordingReport().report,
      token: cancellation().token,
    });

    assert.equal(configs[0].console, 'internalConsole');
  });

  test('a run keeps the debug console closed, a debug session leaves it to the user', async () => {
    const run = makeRunner({ mode: 'run' });
    const debug = makeRunner({ mode: 'debug' });

    await run.runner.run({
      group: GROUP,
      report: recordingReport().report,
      token: cancellation().token,
    });
    await debug.runner.run({
      group: GROUP,
      report: recordingReport().report,
      token: cancellation().token,
    });

    assert.equal(run.configs[0].internalConsoleOptions, 'neverOpen');
    assert.equal(debug.configs[0].internalConsoleOptions, undefined);
  });

  test('reads what each process printed, its last line too, and then how it exited', async () => {
    const { runner } = makeRunner({
      launches: [launch('FooTest'), launch('BarTest')],
      output: 'first\nno newline at the end',
      exitCode: 1,
    });
    const { report, calls } = recordingReport();

    await runner.run({ group: GROUP, report, token: cancellation().token });

    assert.deepEqual(calls, [
      'line first',
      'line no newline at the end',
      'exited 1',
      'line first',
      'line no newline at the end',
      'exited 1',
    ]);
  });

  test('a group the server found no way to run fails the run', async () => {
    const { runner } = makeRunner({ launches: [] });

    await assert.rejects(
      runner.run({ group: GROUP, report: recordingReport().report, token: cancellation().token }),
      /The server found no way to run these tests\./,
    );
  });

  test('a run cancelled before it launched launches nothing', async () => {
    const { runner, configs } = makeRunner();
    const { token, cancel } = cancellation();
    cancel();

    await runner.run({ group: GROUP, report: recordingReport().report, token });

    assert.deepEqual(configs, []);
  });

  test('a process cancelled while running reports no exit, and the next one never starts', async () => {
    const { token, cancel } = cancellation();
    const { runner, configs } = makeRunner({
      launches: [launch('FooTest'), launch('BarTest')],
      whileRunning: cancel,
    });
    const { report, calls } = recordingReport();

    await runner.run({ group: GROUP, report, token });

    assert.equal(configs.length, 1);
    assert.deepEqual(calls, []);
  });
});
