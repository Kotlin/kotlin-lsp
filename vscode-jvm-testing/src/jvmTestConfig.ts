// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import { type CancellationToken, type Uri, window, workspace } from 'vscode';

/** The settings of vscode-java-test, so its users keep their configurations as they are. */
const SECTION = 'java.test';

/** The `java.test.defaultConfig` value that vscode-java-test reserves for its built-in configuration. */
const BUILTIN_CONFIG_NAME = 'default';

/** The built-in configuration of vscode-java-test: the tests run in the workspace folder. */
const BUILTIN_CONFIG: JvmTestConfig = {
  name: BUILTIN_CONFIG_NAME,
  vmArgs: [],
  env: {},
  workingDirectory: '${workspaceFolder}',
  tags: [],
};

/**
 * One entry of the `java.test.config` setting of vscode-java-test. The fields that this client does not use, such as
 * `when` or `envFile`, are ignored.
 */
export interface JvmTestConfig {
  readonly name?: string;
  readonly vmArgs: string[];
  readonly env: Record<string, string>;
  readonly workingDirectory?: string;
  /** The JUnit tags or tag expressions to run. A `!` prefix excludes. */
  readonly tags: string[];
}

/** The raw settings that hold for one test file. */
export interface JvmTestSettings {
  /** The workspace folder of the file: one choice holds for all its tests. */
  readonly folder?: { readonly uri: string; readonly name: string };
  readonly config: unknown;
  readonly defaultConfig: unknown;
}

export type JvmTestSettingsReader = (uri: Uri) => JvmTestSettings;

export interface JvmTestConfigPick {
  readonly folderName: string;
  /** The token of the run. The prompt closes when the run is cancelled. */
  readonly token: CancellationToken;
}

/** How the user takes part in the choice of a configuration. */
export interface JvmTestConfigPrompt {
  /** The configuration the user picks, or undefined when the user or the run cancels. */
  pick(
    configs: readonly JvmTestConfig[],
    options: JvmTestConfigPick,
  ): Promise<JvmTestConfig | undefined>;

  warn(message: string): void;
}

export const workspaceTestSettings: JvmTestSettingsReader = (uri) => {
  const settings = workspace.getConfiguration(SECTION, uri);
  const folder = workspace.getWorkspaceFolder(uri);
  return {
    folder: folder && { uri: folder.uri.toString(), name: folder.name },
    config: settings.get('config'),
    defaultConfig: settings.get('defaultConfig'),
  };
};

export const windowPrompt: JvmTestConfigPrompt = {
  async pick(configs, { folderName, token }) {
    const items = configs.map((config, index) => ({
      label: config.name ?? `Configuration #${index + 1}`,
      config,
    }));
    const picked = await window.showQuickPick(
      items,
      { placeHolder: `Select the test configuration for "${folderName}"` },
      token,
    );
    return picked?.config;
  },
  warn: (message) => void window.showWarningMessage(message),
};

/** The test configurations of one run. The run asks the user at most once for each workspace folder. */
export class JvmTestRunConfigs {
  private readonly choices = new Map<string, Promise<JvmTestConfig | undefined>>();

  constructor(
    private readonly options: { settings: JvmTestSettingsReader; prompt: JvmTestConfigPrompt },
  ) {}

  /**
   * The configuration for the file at [uri], or undefined when the file has none. It is also undefined when [token]
   * cancels the choice, so the caller checks [token] before it uses the result.
   */
  configFor(uri: Uri, token: CancellationToken): Promise<JvmTestConfig | undefined> {
    const settings = this.options.settings(uri);
    const key = settings.folder?.uri ?? '';
    let choice = this.choices.get(key);
    if (!choice) {
      choice = this.choose(settings, token);
      this.choices.set(key, choice);
    }
    return choice;
  }

  private async choose(
    settings: JvmTestSettings,
    token: CancellationToken,
  ): Promise<JvmTestConfig | undefined> {
    const { prompt } = this.options;
    const { configs, problems } = parseTestConfigs(settings.config);
    for (const problem of problems) prompt.warn(problem);
    const defaultName = typeof settings.defaultConfig === 'string' ? settings.defaultConfig : '';
    if (defaultName === BUILTIN_CONFIG_NAME) return BUILTIN_CONFIG;
    if (defaultName) {
      const named = configs.filter((config) => config.name === defaultName);
      if (named.length === 1) return named[0];
      prompt.warn(
        named.length === 0
          ? `No test configuration is named "${defaultName}". The tests run without a configuration.`
          : `Several test configurations are named "${defaultName}". The tests run without a configuration.`,
      );
      return undefined;
    }
    if (configs.length <= 1) return configs[0];
    const picked = await prompt.pick(configs, {
      folderName: settings.folder?.name ?? 'the workspace',
      token,
    });
    if (picked || token.isCancellationRequested) return picked;
    throw new Error('No test configuration was selected.');
  }
}

export interface ParsedTestConfigs {
  readonly configs: JvmTestConfig[];
  /** The values that were ignored, one message each. */
  readonly problems: string[];
}

/** Reads the value of `java.test.config`: one entry, or an array of them. */
export function parseTestConfigs(raw: unknown): ParsedTestConfigs {
  const problems: string[] = [];
  let entries: unknown[] = [];
  if (Array.isArray(raw)) entries = raw;
  else if (isObject(raw)) entries = Object.keys(raw).length > 0 ? [raw] : [];
  else if (raw !== undefined && raw !== null)
    problems.push(`${SECTION}.config must be an object or an array.`);
  const configs: JvmTestConfig[] = [];
  entries.forEach((entry: unknown, index) => {
    const at = `${SECTION}.config[${index}]`;
    if (!isObject(entry)) {
      problems.push(`${at} must be an object.`);
      return;
    }
    const filters = entry.filters;
    if (filters !== undefined && !isObject(filters))
      problems.push(`${at}.filters must be an object.`);
    configs.push({
      name: optionalString(entry.name, `${at}.name`, problems),
      vmArgs: strings(entry.vmArgs, `${at}.vmArgs`, problems),
      env: stringMap(entry.env, `${at}.env`, problems),
      workingDirectory: optionalString(entry.workingDirectory, `${at}.workingDirectory`, problems),
      tags: strings(isObject(filters) ? filters.tags : undefined, `${at}.filters.tags`, problems),
    });
  });
  return { configs, problems };
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function optionalString(value: unknown, at: string, problems: string[]): string | undefined {
  if (value === undefined) return undefined;
  if (typeof value === 'string') return value || undefined;
  problems.push(`${at} must be a string.`);
  return undefined;
}

function strings(value: unknown, at: string, problems: string[]): string[] {
  if (value === undefined) return [];
  if (!Array.isArray(value)) {
    problems.push(`${at} must be an array of strings.`);
    return [];
  }
  if (value.some((item) => typeof item !== 'string')) {
    problems.push(`${at} must hold strings only.`);
  }
  return value.filter((item): item is string => typeof item === 'string' && item !== '');
}

function stringMap(value: unknown, at: string, problems: string[]): Record<string, string> {
  if (value === undefined) return {};
  if (!isObject(value)) {
    problems.push(`${at} must be an object.`);
    return {};
  }
  const result: Record<string, string> = {};
  for (const [key, item] of Object.entries(value)) {
    if (typeof item === 'string') result[key] = item;
    else problems.push(`${at}.${key} must be a string.`);
  }
  return result;
}
