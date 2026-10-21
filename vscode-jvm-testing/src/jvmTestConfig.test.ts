// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import type { CancellationToken, Uri } from 'vscode';
import {
  type JvmTestConfig,
  type JvmTestConfigPick,
  type JvmTestConfigPrompt,
  JvmTestRunConfigs,
  type JvmTestSettings,
  parseTestConfigs,
} from './jvmTestConfig';

const FILE = { toString: () => 'file:///project/app/MainTest.java' } as unknown as Uri;
const FOLDER = { uri: 'file:///project', name: 'project' };

function tokenOf(): CancellationToken {
  return { isCancellationRequested: false, onCancellationRequested: () => ({ dispose() {} }) };
}

/** A prompt that records what it was asked, and picks the entry [pickIndex] or cancels. */
class RecordingPrompt implements JvmTestConfigPrompt {
  readonly picks: string[][] = [];
  readonly tokens: CancellationToken[] = [];
  readonly warnings: string[] = [];

  constructor(private readonly pickIndex?: number) {}

  pick(
    configs: readonly JvmTestConfig[],
    { token }: JvmTestConfigPick,
  ): Promise<JvmTestConfig | undefined> {
    this.picks.push(configs.map((config) => config.name ?? ''));
    this.tokens.push(token);
    return Promise.resolve(this.pickIndex === undefined ? undefined : configs[this.pickIndex]);
  }

  warn(message: string): void {
    this.warnings.push(message);
  }
}

function configsOf(
  settings: Omit<JvmTestSettings, 'folder'>,
  prompt: JvmTestConfigPrompt = new RecordingPrompt(),
) {
  return new JvmTestRunConfigs({ settings: () => ({ folder: FOLDER, ...settings }), prompt });
}

describe('parseTestConfigs', () => {
  test('reads one entry and an array of entries in the shape of java.test.config', () => {
    const entry = {
      name: 'fast',
      vmArgs: ['-Xmx64m', ''],
      env: { LANG: 'C' },
      workingDirectory: '${workspaceFolder}',
      filters: { tags: ['fast', '!slow'] },
    };
    const expected: JvmTestConfig = {
      name: 'fast',
      vmArgs: ['-Xmx64m'],
      env: { LANG: 'C' },
      workingDirectory: '${workspaceFolder}',
      tags: ['fast', '!slow'],
    };

    assert.deepEqual(parseTestConfigs(entry), { configs: [expected], problems: [] });
    assert.deepEqual(parseTestConfigs([entry, entry]), {
      configs: [expected, expected],
      problems: [],
    });
  });

  test('reads no entry from an absent or empty setting', () => {
    for (const raw of [undefined, null, {}, []]) {
      assert.deepEqual(parseTestConfigs(raw), { configs: [], problems: [] });
    }
  });

  test('ignores a value of the wrong type and names it', () => {
    const { configs, problems } = parseTestConfigs([
      { vmArgs: '-Xmx64m', env: { DEBUG: 1 }, filters: { tags: ['fast', 2] } },
      'fast',
    ]);

    assert.deepEqual(configs, [
      { name: undefined, vmArgs: [], env: {}, workingDirectory: undefined, tags: ['fast'] },
    ]);
    assert.deepEqual(problems, [
      'java.test.config[0].vmArgs must be an array of strings.',
      'java.test.config[0].env.DEBUG must be a string.',
      'java.test.config[0].filters.tags must hold strings only.',
      'java.test.config[1] must be an object.',
    ]);
  });
});

describe('JvmTestRunConfigs', () => {
  test('uses the only entry without a prompt', async () => {
    const config = await configsOf({
      config: { vmArgs: ['-ea'] },
      defaultConfig: undefined,
    }).configFor(FILE, tokenOf());

    assert.deepEqual(config?.vmArgs, ['-ea']);
  });

  test('uses no configuration when the setting has no entry', async () => {
    assert.equal(
      await configsOf({ config: undefined, defaultConfig: undefined }).configFor(FILE, tokenOf()),
      undefined,
    );
  });

  test('uses the entry that the default configuration names', async () => {
    const prompt = new RecordingPrompt();
    const config = await configsOf(
      { config: [{ name: 'a' }, { name: 'b' }], defaultConfig: 'b' },
      prompt,
    ).configFor(FILE, tokenOf());

    assert.equal(config?.name, 'b');
    assert.deepEqual(prompt.picks, []);
  });

  test('uses the built-in configuration of vscode-java-test for the default name "default"', async () => {
    const config = await configsOf({
      config: [{ name: 'a' }, { name: 'b' }],
      defaultConfig: 'default',
    }).configFor(FILE, tokenOf());

    assert.equal(config?.workingDirectory, '${workspaceFolder}');
  });

  test('warns and uses no configuration when the default configuration names no entry, or two', async () => {
    for (const config of [[{ name: 'a' }], [{ name: 'b' }, { name: 'b' }]]) {
      const prompt = new RecordingPrompt();

      assert.equal(
        await configsOf({ config, defaultConfig: 'b' }, prompt).configFor(FILE, tokenOf()),
        undefined,
      );
      assert.equal(prompt.warnings.length, 1);
    }
  });

  test('asks once for each workspace folder in a run when several entries match', async () => {
    const prompt = new RecordingPrompt(1);
    const configs = configsOf(
      { config: [{ name: 'a' }, { name: 'b' }], defaultConfig: undefined },
      prompt,
    );

    assert.equal((await configs.configFor(FILE, tokenOf()))?.name, 'b');
    assert.equal((await configs.configFor(FILE, tokenOf()))?.name, 'b');
    assert.deepEqual(prompt.picks, [['a', 'b']]);
  });

  test('gives the token of the run to the prompt', async () => {
    const prompt = new RecordingPrompt(0);
    const token = tokenOf();

    await configsOf(
      { config: [{ name: 'a' }, { name: 'b' }], defaultConfig: undefined },
      prompt,
    ).configFor(FILE, token);

    assert.deepEqual(prompt.tokens, [token]);
  });

  test('fails the run when the user cancels the choice', async () => {
    const configs = configsOf({ config: [{ name: 'a' }, { name: 'b' }], defaultConfig: undefined });

    await assert.rejects(configs.configFor(FILE, tokenOf()), /No test configuration was selected/);
  });

  test('uses no configuration and does not fail when the run is cancelled during the choice', async () => {
    const token = tokenOf();
    const prompt: JvmTestConfigPrompt = {
      pick: () => {
        token.isCancellationRequested = true;
        return Promise.resolve(undefined);
      },
      warn: (message) => assert.fail(message),
    };

    const config = await configsOf(
      { config: [{ name: 'a' }, { name: 'b' }], defaultConfig: undefined },
      prompt,
    ).configFor(FILE, token);

    assert.equal(config, undefined);
  });
});
