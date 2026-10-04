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
}

export interface JvmTestLaunchServer {
  launches(request: JvmTestLaunchRequest): Promise<JvmTestLaunch[]>;
}

const lspLaunchServer: JvmTestLaunchServer = {
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
    const launches = await this.server.launches({ testIds: [...group.testIds], uniqueIds: [...group.uniqueIds] });
    // The module's own runtime comes from the server at launch time; only the runner's jars travel from here. The
    // server puts the runner's module path entries on the class path of a module that is not modular.
    return launches.map((launch) => ({
      type: 'intellij_debugger',
      request: 'launch',
      console: 'none',
      mainClass: launch.mainClass,
      file: group.uri.fsPath,
      args: launch.args,
      additionalClassPaths: launch.runtimeClasspath,
      additionalModulePaths: launch.runtimeModulePath ?? [],
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
