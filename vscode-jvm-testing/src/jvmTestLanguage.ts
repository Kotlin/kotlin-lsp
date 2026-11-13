// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import type { CancellationToken, Uri } from 'vscode';
import {
  getLspClient,
  sendLspCommand,
  serverResolvesStackTraces,
} from '@jetbrains/vscode-extension-core';
import {
  debugAdapterRunner,
  lspDiscovery,
  type StackFrameParser,
  type TestFrame,
  type TestLanguage,
  type TestLaunchConfig,
  type TestProfile,
  type TestRunGroup,
  type TestRunner,
} from '@jetbrains/vscode-testing';
import { JvmTestBuilds } from './jvmTestBuild';
import {
  type JvmTestConfig,
  type JvmTestConfigPrompt,
  JvmTestRunConfigs,
  type JvmTestSettingsReader,
  windowPrompt,
  workspaceTestSettings,
} from './jvmTestConfig';
import {
  JVM_LANGUAGE_IDS,
  jvmSuiteLocation,
  JvmTestCommands,
  type JvmTestLaunch,
  type JvmTestLaunchRequest,
  RESOLVE_TEST_LAUNCH_COMMAND,
} from './jvmTestProtocol';

const FRAME = /^\s*at\s+(\S.*)\(([^()]*)\)\s*$/;
const SOURCE_LINE = /:(\d+)\s*$/;

type JvmTestProfile = 'run' | 'debug';

export interface JvmTestLaunchConfig extends TestLaunchConfig {
  readonly type: 'intellij_debugger';
  /** The adapter then sends the process output as telemetry, so the `##teamcity` lines stay out of the Debug Console. */
  readonly console: 'none';
  readonly mainClass: string;
  /** The test file: the server runs the module that owns it, on its own test runtime plus the runner's jars. */
  readonly file: string;
  readonly args: string[];
  /** The jars of the test runner, added to the class path of the module. */
  readonly additionalClassPaths: string[];
  /** The module path entries of the runner, added to the module path of a modular test module. */
  readonly additionalModulePaths: string[];
  /** The fields of the test configuration, when it sets them. The `encoding` is the first of the `vmArgs`. */
  readonly vmArgs?: string[];
  readonly env?: Record<string, string>;
  readonly envFile?: string;
  readonly cwd?: string;
  readonly javaExec?: string;
  readonly preLaunchTask?: string;
  readonly postDebugTask?: string;
}

export interface JvmTestLaunchServer {
  launches(request: JvmTestLaunchRequest): Promise<JvmTestLaunch[]>;
}

const lspLaunchServer: JvmTestLaunchServer = {
  launches: (request) =>
    sendLspCommand<JvmTestLaunch[]>(runningClient(), RESOLVE_TEST_LAUNCH_COMMAND, [request]),
};

export interface JvmTestLanguageOptions {
  readonly server?: JvmTestLaunchServer;
  readonly settings?: JvmTestSettingsReader;
  readonly prompt?: JvmTestConfigPrompt;
}

export class JvmTestLanguage implements TestLanguage<JvmTestProfile> {
  readonly controller = { id: 'intellijJvmTest', label: 'JVM Tests' };
  readonly profiles: readonly TestProfile<JvmTestProfile>[] = [
    { id: 'run', label: 'Run', button: 'run', nodes: 'any' },
    { id: 'debug', label: 'Debug', button: 'debug', nodes: 'any' },
  ];
  readonly discovery = lspDiscovery({
    commands: JvmTestCommands,
    languageIds: new Set([...JVM_LANGUAGE_IDS, 'xml']),
    sourceGlob: '**/*.{java,kt,xml}',
  });

  private readonly server: JvmTestLaunchServer;
  private readonly settings: JvmTestSettingsReader;
  private readonly prompt: JvmTestConfigPrompt;

  constructor(options: JvmTestLanguageOptions = {}) {
    this.server = options.server ?? lspLaunchServer;
    this.settings = options.settings ?? workspaceTestSettings;
    this.prompt = options.prompt ?? windowPrompt;
  }

  startRun(profile: JvmTestProfile): TestRunner {
    const builds = new JvmTestBuilds();
    const configs = this.runConfigs();
    return debugAdapterRunner({
      mode: profile,
      launches: async (input) => {
        const config = await configs.configFor(input.group.uri, input.token);
        if (input.token.isCancellationRequested) return [];
        const launches = await this.serverLaunches(input.group, config);
        const moduleFile = launches.find((launch) => launch.file)?.file ?? undefined;
        const built = await builds.ensureBuilt(input, { moduleFile });
        if (built === 'failed') {
          throw new Error('Compilation failed. See the build output in Test Results.');
        }
        return launches.map((launch) => configOf(launch, input.group, config));
      },
      stackFrames: this.stackFrames,
    });
  }

  async resolveLaunches(
    group: TestRunGroup,
    token: CancellationToken,
  ): Promise<JvmTestLaunchConfig[]> {
    const config = await this.runConfigs().configFor(group.uri, token);
    return (await this.serverLaunches(group, config)).map((launch) =>
      configOf(launch, group, config),
    );
  }

  private runConfigs(): JvmTestRunConfigs {
    return new JvmTestRunConfigs({ settings: this.settings, prompt: this.prompt });
  }

  private serverLaunches(
    group: TestRunGroup,
    config: JvmTestConfig | undefined,
  ): Promise<JvmTestLaunch[]> {
    return this.server.launches({
      testIds: [...group.testIds],
      uniqueIds: [...group.uniqueIds],
      tags: config?.tags ?? [],
    });
  }

  private readonly stackFrames: StackFrameParser = (stacktrace, report) => {
    const frames: TestFrame[] = [];
    const linked = serverResolvesStackTraces();
    for (const line of stacktrace.split('\n')) {
      const frame = FRAME.exec(line);
      if (!frame) continue;
      const [, reference, source] = frame;
      const sourceLine = SOURCE_LINE.exec(source);
      frames.push({
        label: reference,
        uri: fileOfFrame(reference),
        line: sourceLine ? Number(sourceLine[1]) : undefined,
        linked,
      });
    }
    return frames;

    function fileOfFrame(reference: string): Uri | undefined {
      const module = reference.indexOf('/');
      const qualifiedMethod = module === -1 ? reference : reference.slice(module + 1);
      const method = qualifiedMethod.lastIndexOf('.');
      let className = method > 0 ? qualifiedMethod.slice(0, method) : undefined;
      while (className) {
        const uri = report.sourceOf(jvmSuiteLocation(className));
        if (uri) return uri;
        const nested = className.lastIndexOf('$');
        className = nested > 0 ? className.slice(0, nested) : undefined;
      }
      return undefined;
    }
  };
}

function configOf(
  launch: JvmTestLaunch,
  group: TestRunGroup,
  config: JvmTestConfig | undefined,
): JvmTestLaunchConfig {
  const vmArgs = [
    ...(config?.encoding ? [`-Dfile.encoding=${config.encoding}`] : []),
    ...(config?.vmArgs ?? []),
  ];
  return {
    type: 'intellij_debugger',
    request: 'launch',
    console: 'none',
    mainClass: launch.mainClass,
    file: launch.file ?? group.uri.fsPath,
    args: launch.args,
    additionalClassPaths: [...addedPathsOf(config?.classPaths ?? []), ...launch.runtimeClasspath],
    additionalModulePaths: [
      ...addedPathsOf(config?.modulePaths ?? []),
      ...(launch.runtimeModulePath ?? []),
    ],
    ...(vmArgs.length > 0 ? { vmArgs } : {}),
    ...(config && Object.keys(config.env).length > 0 ? { env: config.env } : {}),
    ...(config?.envFile ? { envFile: config.envFile } : {}),
    ...(config?.workingDirectory ? { cwd: config.workingDirectory } : {}),
    ...(config?.javaExec ? { javaExec: config.javaExec } : {}),
    ...(config?.preLaunchTask ? { preLaunchTask: config.preLaunchTask } : {}),
    ...(config?.postDebugTask ? { postDebugTask: config.postDebugTask } : {}),
  };
}

/** The tokens of the runtime of the module, such as `$Auto`. The server always adds that runtime. */
const MODULE_RUNTIME_TOKENS: ReadonlySet<string> = new Set(['$Auto', '$Runtime', '$Test']);

/**
 * The [entries] of `classPaths` or `modulePaths` to add to the module runtime. An entry with the `!` prefix of
 * vscode-java-test is skipped: the runtime of the module comes from the project model.
 */
const addedPathsOf = (entries: readonly string[]): string[] =>
  entries.filter((entry) => !MODULE_RUNTIME_TOKENS.has(entry) && !entry.startsWith('!'));

function runningClient() {
  const client = getLspClient();
  if (!client) throw new Error('IntelliJ LSP is not running');
  return client;
}
