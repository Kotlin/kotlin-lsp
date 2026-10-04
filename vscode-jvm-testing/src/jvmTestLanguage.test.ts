// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import type { Uri } from 'vscode';
import type { TestNodeId, TestRunGroup } from '@jetbrains/vscode-testing';
import type { JvmTestLaunch } from './jvmTestProtocol';
import { JvmTestLanguage, type JvmTestLaunchConfig } from './jvmTestLanguage';

const RUNNER_JARS = ['/idea/lib/idea_rt.jar', '/idea/lib/junit5_rt.jar'];

const junitLaunch: JvmTestLaunch = {
  mainClass: 'com.intellij.rt.junit.JUnitStarter',
  args: ['-junit5', 'apptest.MainTest'],
  runtimeClasspath: RUNNER_JARS,
};

function uriOf(path: string): Uri {
  return { toString: () => `file://${path}`, fsPath: path } as unknown as Uri;
}

const GROUP: TestRunGroup = {
  moduleName: 'app',
  testIds: ['apptest.MainTest' as TestNodeId],
  uniqueIds: [],
  uri: uriOf('/project/app/testSrc/apptest/MainTest.java'),
  name: 'Run MainTest',
};

/** The launches of one group, against a server that answers [launch]. */
function launchedConfigs(launch: JvmTestLaunch = junitLaunch): Promise<JvmTestLaunchConfig[]> {
  return new JvmTestLanguage({ launches: () => Promise.resolve([launch]) }).resolveLaunches(GROUP);
}

describe('JvmTestLanguage.resolveLaunches', () => {
  // The module's own class path is the server's business at launch time; the client names the file and adds the
  // runner's jars, and asks for nothing else.
  test('names the test file and adds the runner jars to the module runtime', async () => {
    const [config] = await launchedConfigs();

    assert.equal(config.file, '/project/app/testSrc/apptest/MainTest.java');
    assert.equal(config.mainClass, junitLaunch.mainClass);
    assert.deepEqual(config.args, junitLaunch.args);
    assert.deepEqual(config.additionalClassPaths, RUNNER_JARS);
    assert.deepEqual(config.additionalModulePaths, []);
  });

  test('passes the runner module path entries on for the server to place', async () => {
    const [config] = await launchedConfigs({ ...junitLaunch, runtimeModulePath: ['/idea/lib/junit-platform-launcher.jar'] });

    assert.deepEqual(config.additionalModulePaths, ['/idea/lib/junit-platform-launcher.jar']);
  });

  test('keeps the output of the test process out of the Debug Console', async () => {
    const [config] = await launchedConfigs();

    assert.equal(config.console, 'none');
  });
});
