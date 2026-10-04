// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import assert from 'node:assert/strict';
import { beforeEach, describe, test } from 'node:test';
import type { CancellationToken, TestRun, Uri } from 'vscode';
import type { TestRunGroup, TestRunInput, TestRunReport } from '@jetbrains/vscode-testing';
import { JvmTestBuilds } from './jvmTestBuild';

function uriOf(path: string): Uri {
  return { toString: () => `file://${path}` } as unknown as Uri;
}

function tokenOf(cancelled = false): CancellationToken {
  return {
    isCancellationRequested: cancelled,
    onCancellationRequested: () => ({ dispose() {} }),
  } as unknown as CancellationToken;
}

let output: string[];

function inputOf(path: string, token = tokenOf()): TestRunInput {
  const group = { uri: uriOf(path) } as TestRunGroup;
  const report = {
    output: ({ text }: { text: string }) => output.push(text),
  } as unknown as TestRunReport;
  return { group, report, token, run: {} as TestRun };
}

describe('JvmTestBuilds', () => {
  let log: string[];

  beforeEach(() => {
    output = [];
    log = [];
  });

  test('builds the module of the test file on the server and streams the output', async () => {
    const built: string[] = [];
    const builds = new JvmTestBuilds({
      log: (message) => log.push(message),
      build: (uri, line) => {
        built.push(uri);
        line({ buildId: 'b', category: 'stdout', line: 'BUILD SUCCESS' });
        return Promise.resolve({ exitCode: 0 });
      },
    });

    assert.equal(await builds.ensureBuilt(inputOf('/p/app/AppTest.java')), 'built');
    assert.deepEqual(built, ['file:///p/app/AppTest.java']);
    assert.deepEqual(output, ['BUILD SUCCESS\n']);
  });

  test('builds one module once per run', async () => {
    let count = 0;
    const builds = new JvmTestBuilds({ log: () => {}, build: () => Promise.resolve({ exitCode: ++count && 0 }) });

    await builds.ensureBuilt(inputOf('/p/app/ATest.java'));
    await builds.ensureBuilt(inputOf('/p/app/ATest.java'));
    assert.equal(count, 1);
  });

  test('a failed build fails the run', async () => {
    const builds = new JvmTestBuilds({ log: () => {}, build: () => Promise.resolve({ exitCode: 1 }) });

    assert.equal(await builds.ensureBuilt(inputOf('/p/app/AppTest.java')), 'failed');
  });

  test('a module nothing can build is skipped with the reason, and the tests still run', async () => {
    const builds = new JvmTestBuilds({
      log: (message) => log.push(message),
      build: () => Promise.resolve({ reason: 'No build tool imported this module' }),
    });

    assert.equal(await builds.ensureBuilt(inputOf('/p/app/AppTest.java')), 'skipped');
    assert.deepEqual(output, ['No build tool imported this module Running the classes that are already compiled.\n']);
    assert.deepEqual(log, ['[jvmTest] No build tool imported this module']);
  });

  test('a build that cannot start is skipped, not failed', async () => {
    const builds = new JvmTestBuilds({ log: () => {}, build: () => Promise.reject(new Error('server gone')) });

    assert.equal(await builds.ensureBuilt(inputOf('/p/app/AppTest.java')), 'skipped');
    assert.match(output[0], /server gone/);
  });

  test('a cancelled run skips the build', async () => {
    const builds = new JvmTestBuilds({ log: () => {}, build: () => Promise.resolve({ exitCode: 0 }) });

    assert.equal(await builds.ensureBuilt(inputOf('/p/app/AppTest.java', tokenOf(true))), 'skipped');
  });
});
