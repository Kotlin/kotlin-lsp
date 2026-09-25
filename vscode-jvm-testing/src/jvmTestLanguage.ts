// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import type { Uri } from 'vscode';
import { getLspClient, sendLspCommand } from '@jetbrains/vscode-extension-core';
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
  JVM_LANGUAGE_IDS,
  jvmSuiteLocation,
  JvmTestCommands,
  type JvmTestLaunch,
  type JvmTestLaunchRequest,
  type JvmTestRunPaths,
  RESOLVE_LAUNCH_COMMAND,
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
  readonly file: string;
  readonly args: string[];
  readonly classPaths: string[];
  readonly modulePaths: string[];
  readonly vmArgs: string[];
}

export interface JvmTestLaunchServer {
  paths(uri: Uri): Promise<JvmTestRunPaths>;
  launches(request: JvmTestLaunchRequest): Promise<JvmTestLaunch[]>;
}

const lspLaunchServer: JvmTestLaunchServer = {
  paths: (uri) =>
    sendLspCommand<JvmTestRunPaths>(runningClient(), RESOLVE_LAUNCH_COMMAND, [
      { uri: uri.toString() },
    ]),
  launches: (request) =>
    sendLspCommand<JvmTestLaunch[]>(runningClient(), RESOLVE_TEST_LAUNCH_COMMAND, [request]),
};

export class JvmTestLanguage implements TestLanguage<JvmTestProfile> {
  readonly controller = { id: 'intellijJvmTest', label: 'JVM Tests' };
  readonly profiles: readonly TestProfile<JvmTestProfile>[] = [
    { id: 'run', label: 'Run', button: 'run', nodes: 'any' },
    { id: 'debug', label: 'Debug', button: 'debug', nodes: 'any' },
  ];
  readonly discovery = lspDiscovery({
    commands: JvmTestCommands,
    languageIds: JVM_LANGUAGE_IDS,
    sourceGlob: '**/*.{java,kt}',
  });

  constructor(private readonly server: JvmTestLaunchServer = lspLaunchServer) {}

  startRun(profile: JvmTestProfile): TestRunner {
    const builds = new JvmTestBuilds();
    return debugAdapterRunner({
      mode: profile,
      launches: async (input) => {
        const [launches, built] = await Promise.all([
          this.resolveLaunches(input.group),
          builds.ensureBuilt(input),
        ]);
        if (built === 'failed') {
          throw new Error('Compilation failed. See the build output in Test Results.');
        }
        return launches;
      },
      stackFrames: this.stackFrames,
    });
  }

  async resolveLaunches(group: TestRunGroup): Promise<JvmTestLaunchConfig[]> {
    const [paths, launches] = await Promise.all([
      this.server.paths(group.uri),
      this.server.launches({ testIds: [...group.testIds], uniqueIds: [...group.uniqueIds] }),
    ]);
    const modular = (paths.modulePath ?? []).length > 0;
    return launches.map((launch) => ({
      type: 'intellij_debugger',
      request: 'launch',
      console: 'none',
      mainClass: launch.mainClass,
      file: group.uri.fsPath,
      args: launch.args,
      classPaths: [
        ...launch.runtimeClasspath,
        ...(modular ? [] : (launch.runtimeModulePath ?? [])),
        ...(paths.classpath ?? []),
      ],
      modulePaths: [
        ...(paths.modulePath ?? []),
        ...(modular ? (launch.runtimeModulePath ?? []) : []),
      ],
      vmArgs: paths.vmArgs ?? [],
    }));
  }

  private readonly stackFrames: StackFrameParser = (stacktrace, report) => {
    const frames: TestFrame[] = [];
    for (const line of stacktrace.split('\n')) {
      const frame = FRAME.exec(line);
      if (!frame) continue;
      const [, reference, source] = frame;
      const sourceLine = SOURCE_LINE.exec(source);
      frames.push({
        label: reference,
        uri: fileOfFrame(reference),
        line: sourceLine ? Number(sourceLine[1]) : undefined,
      });
    }
    return frames;

    function fileOfFrame(reference: string): Uri | undefined {
      const module = reference.indexOf('/');
      const qualifiedMethod = module === -1 ? reference : reference.slice(module + 1);
      const method = qualifiedMethod.lastIndexOf('.');
      let className = method > 0 ? qualifiedMethod.slice(0, method) : undefined;
      while (className) {
        const uri = report.atLocation(jvmSuiteLocation(className))?.uri;
        if (uri) return uri;
        const nested = className.lastIndexOf('$');
        className = nested > 0 ? className.slice(0, nested) : undefined;
      }
      return undefined;
    }
  };
}

function runningClient() {
  const client = getLspClient();
  if (!client) throw new Error('IntelliJ LSP is not running');
  return client;
}
