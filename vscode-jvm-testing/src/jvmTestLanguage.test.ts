// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import type { CancellationToken, TestRun, Uri } from 'vscode';
import type {
  TestNodeId,
  TestRunGroup,
  TestRunInput,
  TestRunReport,
} from '@jetbrains/vscode-testing';
import type { JvmTestConfigPrompt, JvmTestSettings } from './jvmTestConfig';
import type { JvmTestLaunch, JvmTestLaunchRequest } from './jvmTestProtocol';
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

const NO_PROMPT: JvmTestConfigPrompt = {
  pick: () => Promise.reject(new Error('Unexpected prompt')),
  warn: (message) => assert.fail(message),
};

function tokenOf(): CancellationToken {
  return { isCancellationRequested: false, onCancellationRequested: () => ({ dispose() {} }) };
}

/** A language against a server that answers [launch] and records each request in [requests]. */
function languageOf(options: {
  launch?: JvmTestLaunch;
  config?: unknown;
  prompt?: JvmTestConfigPrompt;
  requests: JvmTestLaunchRequest[];
}): JvmTestLanguage {
  const { launch = junitLaunch, config, prompt = NO_PROMPT, requests } = options;
  const settings: JvmTestSettings = { config, defaultConfig: undefined };
  return new JvmTestLanguage({
    server: {
      launches: (request) => {
        requests.push(request);
        return Promise.resolve([launch]);
      },
    },
    settings: () => settings,
    prompt,
  });
}

/** The launches of one group, against a server that answers [launch], with [config] as the setting. */
async function launchedConfigs(
  launch: JvmTestLaunch = junitLaunch,
  config?: unknown,
): Promise<{ configs: JvmTestLaunchConfig[]; requests: JvmTestLaunchRequest[] }> {
  const requests: JvmTestLaunchRequest[] = [];
  const language = languageOf({ launch, config, requests });
  return { configs: await language.resolveLaunches(GROUP, tokenOf()), requests };
}

describe('JvmTestLanguage.resolveLaunches', () => {
  // The module's own class path is the server's business at launch time; the client names the file and adds the
  // runner's jars, and asks for nothing else.
  test('names the test file and adds the runner jars to the module runtime', async () => {
    const {
      configs: [config],
    } = await launchedConfigs();

    assert.equal(config.file, '/project/app/testSrc/apptest/MainTest.java');
    assert.equal(config.mainClass, junitLaunch.mainClass);
    assert.deepEqual(config.args, junitLaunch.args);
    assert.deepEqual(config.additionalClassPaths, RUNNER_JARS);
    assert.deepEqual(config.additionalModulePaths, []);
  });

  test('passes the runner module path entries on for the server to place', async () => {
    const {
      configs: [config],
    } = await launchedConfigs({
      ...junitLaunch,
      runtimeModulePath: ['/idea/lib/junit-platform-launcher.jar'],
    });

    assert.deepEqual(config.additionalModulePaths, ['/idea/lib/junit-platform-launcher.jar']);
  });

  test('names the file the server picked, when the launched file is outside the test sources', async () => {
    const {
      configs: [config],
    } = await launchedConfigs({ ...junitLaunch, file: 'file:///project/app/testSrc' });

    assert.equal(config.file, 'file:///project/app/testSrc');
  });

  test('keeps the output of the test process out of the Debug Console', async () => {
    const {
      configs: [config],
    } = await launchedConfigs();

    assert.equal(config.console, 'none');
  });

  test('adds the VM options, the environment and the working directory of the test configuration', async () => {
    const {
      configs: [config],
    } = await launchedConfigs(junitLaunch, {
      vmArgs: ['-Xmx64m'],
      env: { LANG: 'de_DE.UTF-8' },
      workingDirectory: '${workspaceFolder}/app',
    });

    assert.deepEqual(config.vmArgs, ['-Xmx64m']);
    assert.deepEqual(config.env, { LANG: 'de_DE.UTF-8' });
    assert.equal(config.cwd, '${workspaceFolder}/app');
  });

  // Without a value the adapter keeps its own default, such as the working directory of the module.
  test('sets no launch value that the test configuration does not set', async () => {
    const {
      configs: [config],
    } = await launchedConfigs();

    assert.equal('vmArgs' in config, false);
    assert.equal('env' in config, false);
    assert.equal('cwd' in config, false);
  });

  test('sends the tag filters of the test configuration to the server', async () => {
    const { requests } = await launchedConfigs(junitLaunch, {
      filters: { tags: ['fast', '!slow'] },
    });

    assert.deepEqual(
      requests.map((request) => request.tags),
      [['fast', '!slow']],
    );
  });
});

describe('JvmTestLanguage.startRun', () => {
  test('ends a run that is cancelled during the choice of a configuration, and asks the server nothing', async () => {
    const requests: JvmTestLaunchRequest[] = [];
    const token = tokenOf();
    const language = languageOf({
      config: [{ name: 'a' }, { name: 'b' }],
      prompt: {
        pick: () => {
          token.isCancellationRequested = true;
          return Promise.resolve(undefined);
        },
        warn: (message) => assert.fail(message),
      },
      requests,
    });
    const input: TestRunInput = {
      group: GROUP,
      report: {} as TestRunReport,
      token,
      run: {} as TestRun,
    };

    await language.startRun('run').run(input);

    assert.deepEqual(requests, []);
  });
});
