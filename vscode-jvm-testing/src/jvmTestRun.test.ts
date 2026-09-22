// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import type { CancellationToken, DebugConfiguration, TestItem, TestRun, Uri } from 'vscode';
import { runTestGroup } from './jvmTestRun';
import type { JvmTestBuilds } from './jvmTestBuild';
import type { JvmTestLaunchGroup } from './jvmTestPlan';
import type { JvmTestTree } from './jvmTestTree';

const RUNNER_JARS = ['/idea/lib/idea_rt.jar', '/idea/lib/junit5_rt.jar'];

const junitLaunch = {
  mainClass: 'com.intellij.rt.junit.JUnitStarter',
  args: ['-junit5', 'apptest.MainTest'],
  runtimeClasspath: RUNNER_JARS,
};

function uriOf(path: string): Uri {
  return { toString: () => `file://${path}`, fsPath: path } as unknown as Uri;
}

function tokenOf(): CancellationToken {
  return {
    isCancellationRequested: false,
    onCancellationRequested: () => ({ dispose() {} }),
  } as unknown as CancellationToken;
}

/** A group of one class, enough for a run: the tree is only read for nodes the runner reports. */
function groupOf(): JvmTestLaunchGroup {
  const item = {
    id: 'app/apptest.MainTest',
    label: 'MainTest',
    children: [],
  } as unknown as TestItem;
  return {
    moduleName: 'app',
    items: [item],
    testIds: ['apptest.MainTest'],
    uniqueIds: [],
    uri: uriOf('/project/app/testSrc/apptest/MainTest.java'),
  };
}

/**
 * Runs one group against a server that answers [paths], and returns the debug configurations that reached
 * `startDebugging`.
 */
async function launchedConfigs(paths: {
  classpath?: string[];
  modulePath?: string[];
  vmArgs?: string[];
}): Promise<DebugConfiguration[]> {
  const launched: DebugConfiguration[] = [];
  await runTestGroup({
    group: groupOf(),
    run: {
      enqueued() {},
      started() {},
      passed() {},
      failed() {},
      skipped() {},
      errored() {},
      appendOutput() {},
      end() {},
    } as unknown as TestRun,
    tree: {
      forgetRuntimeChildren() {},
      get: () => undefined,
      itemByLocation: () => undefined,
      fileOfClass: () => undefined,
    } as unknown as JvmTestTree,
    builds: { ensureBuilt: () => Promise.resolve('skipped') } as unknown as JvmTestBuilds,
    noDebug: true,
    token: tokenOf(),
    resolve: () => Promise.resolve({ launches: [junitLaunch], paths }),
    spawn: (config) => {
      launched.push(config);
      return Promise.resolve(0);
    },
  });
  return launched;
}

describe('running a group of tests', () => {
  /**
   * VS Code resolves the configuration once more on the way to the debug session, and that resolution answers an
   * override verbatim. A path the run leaves out is therefore not merely missing — it is resolved again from the
   * module alone. Leaving the module path out ran a modular test off the class path, in the unnamed module where
   * the module system is not in force, so a test Maven rejects for a missing `opens` came back green (LSP-1773).
   */
  test('runs the tests on the module path the server resolved', async () => {
    const [config] = await launchedConfigs({
      classpath: ['/project/out/libs/guava.jar'],
      modulePath: ['/project/out/production/app', '/project/out/test/app'],
      vmArgs: ['--add-modules=myapp.test'],
    });

    assert.deepEqual(config.modulePaths, ['/project/out/production/app', '/project/out/test/app']);
    assert.deepEqual(config.vmArgs, ['--add-modules=myapp.test']);
  });

  /** The runner runs in the unnamed module, so its jars go on the class path and never on the module path. */
  test('runs the framework runner off the class path', async () => {
    const [config] = await launchedConfigs({
      classpath: ['/project/out/libs/guava.jar'],
      modulePath: ['/project/out/test/app'],
    });

    assert.equal(config.mainClass, 'com.intellij.rt.junit.JUnitStarter');
    assert.deepEqual(config.classPaths, [...RUNNER_JARS, '/project/out/libs/guava.jar']);
  });

  /**
   * A non-modular project resolves no module path, and the run still has to say so. An absent value is not an
   * override, so the second resolution would answer it from the module and could contradict this one.
   */
  test('says a non-modular run has no module path rather than leaving it out', async () => {
    const [config] = await launchedConfigs({ classpath: ['/project/out/test/app'] });

    assert.deepEqual(config.modulePaths, []);
    assert.deepEqual(config.vmArgs, []);
  });
});
