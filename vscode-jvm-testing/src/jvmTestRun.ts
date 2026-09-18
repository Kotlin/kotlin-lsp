import { randomUUID } from 'node:crypto';
import { type CancellationToken, type TestRun, workspace } from 'vscode';
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
  console: 'none';
  internalConsoleOptions?: 'neverOpen';
}

export interface RunTestGroupOptions {
  group: JvmTestLaunchGroup;
  run: TestRun;
  tree: JvmTestTree;
  builds: JvmTestBuilds;
  noDebug: boolean;
  token: CancellationToken;
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
}: RunTestGroupOptions): Promise<void> {
  const { uri, items } = group;
  const [configs, built] = await Promise.all([
    resolveLaunchConfigs(group, {
      testIds: [...group.testIds],
      uniqueIds: [...group.uniqueIds],
    }),
    builds.ensureBuilt(uri, token),
  ]);
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
    const code = await launchAndCollectOutput(
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

/** Asks the server what it takes to launch [request], as one debug configuration per process. */
async function resolveLaunchConfigs(
  group: JvmTestLaunchGroup,
  request: JvmTestLaunchRequest,
): Promise<JvmTestLaunchConfig[]> {
  const client = getLspClient();
  if (!client) throw new Error('IntelliJ LSP is not running');

  // Any file of the group resolves the same classpath — they all live in one module.
  const [{ classpath }, launches] = await Promise.all([
    sendLspCommand<{ classpath?: string[] }>(client, RESOLVE_LAUNCH_COMMAND, [
      { uri: group.uri.toString() },
    ]),
    sendLspCommand<JvmTestLaunch[]>(client, JvmTestCommands.resolveTestLaunch, [request]),
  ]);

  return launches.map((launch) => ({
    type: DEBUG_TYPE,
    request: 'launch',
    name: launchName(group),
    mainClass: launch.mainClass,
    file: group.uri.fsPath,
    args: launch.args,
    classPaths: [...launch.runtimeClasspath, ...(classpath ?? [])],
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
