// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import {
  commands,
  debug,
  DebugAdapterDescriptorFactory,
  DebugAdapterServer,
  DebugConfiguration,
  DebugConfigurationProvider,
  DebugSession,
  type ExtensionContext,
  Uri,
  window,
  workspace,
  WorkspaceFolder,
} from 'vscode';
import { registerInitializationOptionsContributor } from './lspClient';
import { getOutputChannel } from './extension';
import { internalConsoleOptionsFor } from './consoleOptions';
import { errorMessage } from './buildTaskModel';
import { lensLaunchConfig } from './lensLaunchModel';
import { createRunOutputTerminalFactory } from './runTerminal';

/**
 * The launch configuration types, one per way of running a program: a plain JVM, or one type per build tool.
 *
 * They exist as separate types, with separate schemas, because they are configured in different vocabularies: a JVM
 * launch may spell a class path and a `java` binary, while a build-tool launch names a project, a source set and the
 * tool's own arguments. The server runs every one of them the same way: it finds the module of the main class, and
 * the type only says which tool the user expects (`tool` in the launch arguments). A type that names a tool other
 * than the module's fails on the server with a message that says so.
 */
export const JVM_DEBUG_TYPE = 'intellij_jvm';
const GRADLE_DEBUG_TYPE = 'intellij_gradle';
const BAZEL_DEBUG_TYPE = 'intellij_bazel';

/**
 * The name [JVM_DEBUG_TYPE] used to have, still accepted so that launch configurations written against it keep
 * working. It resolves exactly like the JVM type, and nothing new is ever written with it.
 */
const LEGACY_JVM_DEBUG_TYPE = 'intellij_debugger';

/** Build tool ids, as the server names them, mapped to their configuration type. */
export const DEBUG_TYPE_BY_TOOL: Record<string, string> = {
  gradle: GRADLE_DEBUG_TYPE,
  bazel: BAZEL_DEBUG_TYPE,
};

/** The tool each configuration type asks the server for; see `JvmLaunchArguments.tool`. */
const TOOL_BY_DEBUG_TYPE: Record<string, string> = {
  [JVM_DEBUG_TYPE]: 'java',
  [LEGACY_JVM_DEBUG_TYPE]: 'java',
  ...Object.fromEntries(Object.entries(DEBUG_TYPE_BY_TOOL).map(([tool, type]) => [type, tool])),
};

/** The part of a product's `package.json` that names its debuggers. */
interface DebuggersManifest {
  contributes?: { debuggers?: Array<{ type: string }> };
}

/** The debugger types the running product contributes. Each product declares only the tools its server imports. */
function declaredDebugTypes(context: ExtensionContext): Set<string> {
  const manifest = context.extension.packageJSON as DebuggersManifest | undefined;
  return new Set((manifest?.contributes?.debuggers ?? []).map((entry) => entry.type));
}

const RUN_MAIN_COMMAND = 'intellij.jvm.runMain';
const DEFAULT_CONSOLE = 'integratedTerminal';

export type ConsoleKind = 'internalConsole' | 'integratedTerminal' | 'externalTerminal' | 'none';

/** The arguments of the server's Run/Debug lens: the main class, its file, and the build tool of its module. */
interface RunMainArgs {
  mainClass: string;
  uri?: string;
  noDebug?: boolean;
  tool?: string;
}

/** What both launch configurations have in common: what to run, and where its output goes. */
interface CommonLaunchConfig extends DebugConfiguration {
  request: 'launch';
  mainClass?: string;
  /** The source file of the main class; the server runs the module that owns it. */
  file?: string;
  args?: string[];
  vmArgs?: string[];
  cwd?: string;
  env?: Record<string, string>;
  /**
   * Where the output goes. The server runs every program itself and streams the output; a terminal console shows
   * that stream, it does not run the program.
   */
  console?: ConsoleKind;
  internalConsoleOptions?: 'neverOpen' | 'openOnSessionStart' | 'openOnFirstSessionStart';
  /** A passthrough: the whole configuration is sent as the launch arguments. An attach configuration has the same field. */
  stepFilters?: StepFilters;
}

/**
 * The code a step does not enter, in the shape of vscode-java-debug's `stepFilters`. Every field is optional, and an
 * absent field keeps the IDE default.
 */
export interface StepFilters {
  skipClasses?: string[];
  skipSynthetics?: boolean;
  skipConstructors?: boolean;
  skipGetters?: boolean;
  skipClassLoaders?: boolean;
}

/**
 * A launch that runs the program as a plain `java` process. The class path, the module path and the `java` binary
 * come from the workspace model; a configuration that spells them runs on what it spells, module or no module.
 */
interface JvmLaunchConfig extends CommonLaunchConfig {
  classPaths?: string[];
  modulePaths?: string[];
  moduleName?: string;
  javaExec?: string;
}

/** A Gradle launch names a project and a source set, and passes Gradle its own arguments. */
interface GradleLaunchConfig extends CommonLaunchConfig {
  projectPath?: string;
  sourceSet?: string;
  gradleArgs?: string[];
}

/** A Bazel launch names a target label and passes Bazel its own options. */
interface BazelLaunchConfig extends CommonLaunchConfig {
  target?: string;
  bazelArgs?: string[];
}

export function registerDapServer(context: ExtensionContext) {
  // One DAP server serves every configuration type: what differs is how a launch is configured, not how the session
  // is spoken.
  const dapServerFactory: DebugAdapterDescriptorFactory = {
    async createDebugAdapterDescriptor(session: DebugSession) {
      const port: number = await commands.executeCommand(
        'start_debug_server',
        session.workspaceFolder?.uri.toString(),
      );
      return new DebugAdapterServer(port);
    },
  };

  const debugConfigProvider: DebugConfigurationProvider = {
    /**
     * Puts the configuration into the vocabulary the adapter reads, and asks the server nothing: the server finds
     * the module, its tool and its class path from the launch itself, in the one `launch` request.
     */
    async resolveDebugConfigurationWithSubstitutedVariables(
      _folder: WorkspaceFolder | undefined,
      debugConfiguration: DebugConfiguration,
    ) {
      if (debugConfiguration.request !== 'launch') return debugConfiguration;
      const config = debugConfiguration as CommonLaunchConfig;
      if (!config.mainClass) {
        void window.showErrorMessage(`The "${config.type}" configuration requires "mainClass"`);
        return undefined;
      }
      return withConsoleDefaults(toLaunchArguments(config));
    },
  };

  // Only the types this product's manifest declares. VS Code lets an extension register a descriptor factory only
  // for a debugger it contributes, and throws otherwise; a product without a Bazel import (the Kotlin server)
  // declares no `intellij_bazel`, and that throw would end activation before the language client starts.
  const declared = declaredDebugTypes(context);
  // The server runs every program itself and streams the output as `output` events; for a terminal `console` the
  // tracker renders that stream in an integrated terminal, since nothing runs on the client to put there.
  const runOutputTerminals = createRunOutputTerminalFactory();
  for (const type of [JVM_DEBUG_TYPE, LEGACY_JVM_DEBUG_TYPE, ...Object.values(DEBUG_TYPE_BY_TOOL)]) {
    if (!declared.has(type)) continue;
    context.subscriptions.push(
      debug.registerDebugAdapterDescriptorFactory(type, dapServerFactory),
      debug.registerDebugConfigurationProvider(type, debugConfigProvider),
      debug.registerDebugAdapterTrackerFactory(type, runOutputTerminals),
    );
  }

  registerRunMainCodeLens(context);
}

/**
 * Registers the editor-side handling of the `intellij.jvm.runMain` code lens command emitted by the server-side
 * CodeLens provider, and declares to the server that this client can handle the command via the `runMainCodeLens`
 * initialization option.
 */
function registerRunMainCodeLens(context: ExtensionContext) {
  registerInitializationOptionsContributor(() => ({ runMainCodeLens: true }));
  context.subscriptions.push(
    commands.registerCommand(RUN_MAIN_COMMAND, (arg: RunMainArgs) => runMainFromLens(arg)),
  );
}

/**
 * Starts the launch of a lens. The lens carries the tool of the module, so the configuration type is known without
 * a request, and the server does the rest in the `launch` request: no build task, no resolution, one round trip.
 */
async function runMainFromLens(arg: RunMainArgs): Promise<void> {
  const folder = window.activeTextEditor
    ? workspace.getWorkspaceFolder(window.activeTextEditor.document.uri)
    : workspace.workspaceFolders?.[0];
  const config = lensLaunchConfig(arg, DEBUG_TYPE_BY_TOOL, JVM_DEBUG_TYPE, (uri) => Uri.parse(uri).fsPath);
  try {
    await debug.startDebugging(folder, config, { noDebug: arg.noDebug ?? false });
  } catch (e) {
    const message = errorMessage(e);
    getOutputChannel().appendLine(`[lens] launch failed: ${message}`);
    void window.showErrorMessage(`Cannot start debugging: ${message}`);
  }
}

/**
 * The configuration in the adapter's vocabulary: `tool` from the type, the tool's own fields as `projectPath`,
 * `sourceSet` and `toolArgs`. A JVM configuration's `classPaths`, `modulePaths`, `moduleName` and `javaExec` pass
 * through as they are: what the configuration spells, the server runs.
 */
function toLaunchArguments(config: CommonLaunchConfig): CommonLaunchConfig {
  const tool = TOOL_BY_DEBUG_TYPE[config.type];
  if (tool !== undefined) config.tool = tool;
  switch (config.type) {
    case GRADLE_DEBUG_TYPE: {
      const gradle = config as GradleLaunchConfig;
      config.toolArgs = gradle.gradleArgs;
      break;
    }
    case BAZEL_DEBUG_TYPE: {
      const bazel = config as BazelLaunchConfig;
      config.projectPath = bazel.target;
      config.toolArgs = bazel.bazelArgs;
      break;
    }
    default:
      break;
  }
  return config;
}

/**
 * Hands the resolved [config] to the DAP server. The server runs the program itself for every `console`: a terminal
 * console shows the streamed output, the internal console the Debug Console. The default matches java-debug's.
 */
function withConsoleDefaults(config: CommonLaunchConfig): DebugConfiguration {
  config.console = config.console ?? DEFAULT_CONSOLE;
  // Keep VSCode from popping the Debug Console (its default `internalConsoleOptions`) on top of the
  // console the user actually launched into, so focus follows `console` instead.
  config.internalConsoleOptions =
    config.internalConsoleOptions ?? internalConsoleOptionsFor(config.console);
  return config;
}
