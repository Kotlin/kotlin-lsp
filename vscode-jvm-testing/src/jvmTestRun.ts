// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import { randomUUID } from 'node:crypto';
import { type CancellationToken, type DebugConfiguration, type TestRun, workspace } from 'vscode';
import { getLspClient, sendLspCommand } from '@jetbrains/vscode-extension-core';
import { jvmFailureMessage } from './jvmTestFailure';
import { launchAndCollectOutput, type TrackedDebugConfiguration } from './jvmDebugLaunch';
import type { JvmTestBuilds } from './jvmTestBuild';
import { jvmTestNodes } from './jvmTestNodes';
import type { JvmTestLaunchGroup } from './jvmTestPlan';
import {
  JvmTestCommands,
  type JvmTestLaunch,
  type JvmTestLaunchRequest,
  RESOLVE_LAUNCH_COMMAND,
} from './jvmTestProtocol';
import type { JvmTestTree } from './jvmTestTree';
import { TestRunReporter } from './testRunReporter';

const DEBUG_TYPE = 'intellij_debugger';

interface JvmTestLaunchConfig extends TrackedDebugConfiguration {
  request: 'launch';
  mainClass: string;
  file: string;
  args: string[];
  classPaths: string[];
  modulePaths: string[];
  vmArgs: string[];
  console: 'none';
  internalConsoleOptions?: 'neverOpen';
}

interface ResolvedTestRun {
  launches: JvmTestLaunch[];
  paths: JvmTestRunPaths;
}

export interface RunTestGroupOptions {
  group: JvmTestLaunchGroup;
  run: TestRun;
  tree: JvmTestTree;
  builds: JvmTestBuilds;
  noDebug: boolean;
  token: CancellationToken;
  resolve?: (group: JvmTestLaunchGroup, request: JvmTestLaunchRequest) => Promise<ResolvedTestRun>;
  spawn?: (config: DebugConfiguration) => Promise<number | undefined>;
}

/**
 * Runs everything in [group] and reports each test's outcome as the runner announces it. The server
 * answers with one process per framework in the group, and they run one after the other.
 */
export async function runTestGroup({
  group,
  run,
  tree,
  builds,
  noDebug,
  token,
  resolve = askServer,
  spawn,
}: RunTestGroupOptions): Promise<void> {
  const { uri, items } = group;
  const [resolved, built] = await Promise.all([
    resolve(group, {
      testIds: [...group.testIds],
      uniqueIds: [...group.uniqueIds],
    }),
    builds.ensureBuilt(uri, token),
  ]);
  const configs = testLaunchConfigs({ group, ...resolved });
  if (token.isCancellationRequested) return;
  if (built === 'failed') {
    throw new Error('Compilation failed. See the build output in Test Results.');
  }
  if (configs.length === 0) throw new Error('The server found no way to run these tests.');
  for (const item of items) tree.forgetRuntimeChildren(item);

  const nodes = jvmTestNodes(tree, group);
  const reporter = new TestRunReporter({
    run,
    launched: items,
    locate: nodes.locate,
    describeFailure: (failed) => jvmFailureMessage(failed, nodes.fileOf),
  });
  let exitCode: number | undefined = 0;
  for (const config of configs) {
    if (noDebug) config.internalConsoleOptions = 'neverOpen';
    const code = spawn
      ? await spawn(config)
      : await launchAndCollectOutput(
          workspace.getWorkspaceFolder(uri),
          config,
          reporter.feed,
          noDebug,
          token,
        );
    // A cancelled process has no meaningful exit code — leaving its tests unresolved is honest.
    if (token.isCancellationRequested) return;
    if (exitCode === 0) exitCode = code;
  }
  reporter.finish(exitCode);
}

/** Asks the server how to run [request] and what to run it on. */
async function askServer(
  group: JvmTestLaunchGroup,
  request: JvmTestLaunchRequest,
): Promise<ResolvedTestRun> {
  const client = getLspClient();
  if (!client) throw new Error('IntelliJ LSP is not running');

  // Any file of the group resolves the same paths — they all live in one module.
  const [paths, launches] = await Promise.all([
    sendLspCommand<JvmTestRunPaths>(client, RESOLVE_LAUNCH_COMMAND, [
      { uri: group.uri.toString() },
    ]),
    sendLspCommand<JvmTestLaunch[]>(client, JvmTestCommands.resolveTestLaunch, [request]),
  ]);

  return { launches, paths };
}

interface JvmTestRunPaths {
  classpath?: string[];
  modulePath?: string[];
  vmArgs?: string[];
}

function testLaunchConfigs({
  group,
  launches,
  paths,
}: {
  group: JvmTestLaunchGroup;
  launches: JvmTestLaunch[];
  paths: JvmTestRunPaths;
}): JvmTestLaunchConfig[] {
  return launches.map((launch) => ({
    type: DEBUG_TYPE,
    request: 'launch',
    name: launchName(group),
    mainClass: launch.mainClass,
    file: group.uri.fsPath,
    args: launch.args,
    classPaths: [...launch.runtimeClasspath, ...(paths.classpath ?? [])],
    modulePaths: paths.modulePath ?? [],
    vmArgs: paths.vmArgs ?? [],
    jvmTestRunToken: randomUUID(),
    console: 'none',
  }));
}

/** What the debug session is called in the UI — the single test, or how much of a module is running. */
function launchName(group: JvmTestLaunchGroup): string {
  const single = group.items.length === 1 ? group.items[0].label : undefined;
  if (single) return `Run ${single}`;
  return `Run ${group.items.length} tests${group.moduleName ? ` in ${group.moduleName}` : ''}`;
}
