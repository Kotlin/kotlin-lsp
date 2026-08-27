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
import type { LanguageClient } from 'vscode-languageclient/node';
import { getLspClient, registerInitializationOptionsContributor } from './lspClient';
import { getOutputChannel } from './extension';
import { internalConsoleOptionsFor } from './consoleOptions';
import {
  BUILD_TASK_LABEL,
  buildTargetUri,
  hasPendingBuild,
  resolveBuildCommand,
  setPendingBuild,
} from './buildTask';
import { type BuildToRun, buildToRun, errorMessage, launchBuildTargetOf } from './buildTaskModel';

/**
 * The launch configuration types, one per way of running a program.
 *
 * They exist as separate types, with separate schemas, because they are configured in different vocabularies: a JVM
 * launch is a class path, a module path and a `java` binary, while a build-tool launch is a project, a source set and
 * the tool's own arguments — and neither set means anything to the other. One type carrying both would have to decide
 * which fields win, which is how setting `javaExec` on a Gradle module used to silently stop Gradle from building it
 * at all.
 *
 * The names say *how the program runs*, which is the axis that has to stay one-dimensional: a kind of program (a main
 * class, a test) belongs in the schema, or the next feature turns these into a tool-by-kind matrix.
 *
 * Which type a module *can* use is a project fact (`intellij.java.resolveBuildToolLaunch`), not a preference; which
 * one it *does* use is the user's choice of configuration, and the Run/Debug lens picks the build tool's whenever
 * there is one.
 */
const JVM_DEBUG_TYPE = 'intellij_jvm';
const GRADLE_DEBUG_TYPE = 'intellij_gradle';

/**
 * The name [JVM_DEBUG_TYPE] used to have, still accepted so that launch configurations written against it keep
 * working. It resolves exactly like the JVM type — it *is* the JVM type — and nothing new is ever written with it:
 * the lens does not use it, and its schema contributes no snippets.
 *
 * It was renamed because it stopped describing anything once a second type existed: `intellij_debugger` says what it
 * is, `intellij_gradle` says how it runs, and only the second axis distinguishes the two. `jvm` rather than `java`
 * because Kotlin main classes launch through it too.
 */
const LEGACY_JVM_DEBUG_TYPE = 'intellij_debugger';

/** Build tool ids, as `intellij.java.resolveBuildToolLaunch` reports them, mapped to their configuration type. */
const DEBUG_TYPE_BY_TOOL: Record<string, string> = { gradle: GRADLE_DEBUG_TYPE };

const RUN_MAIN_COMMAND = 'intellij.jvm.runMain';
const LSP_REQUEST_TIMEOUT_MS = 30_000;
const DEFAULT_CONSOLE = 'integratedTerminal';

export type ConsoleKind = 'internalConsole' | 'integratedTerminal' | 'externalTerminal';

interface RunMainArgs {
  mainClass: string;
  uri?: string;
  noDebug?: boolean;
}

interface ClassDocumentResponse {
  uri: string;
}

/**
 * The runtime paths of a JVM launch, from `intellij.java.resolveLaunch`, with this config's own overrides already
 * merged in — so it is copied rather than combined with anything.
 *
 * The server composes it in one read action precisely so the client does not have to assemble a launch out of
 * separately-resolved fragments. Resist adding a second request here: every field a client has to remember to copy is
 * a field it can silently drop. The per-fragment commands this replaced (`resolveClasspath`, `resolveWorkingDirectory`,
 * `resolveJavaExecutable`) no longer exist, so there is nothing to fall back to.
 */
interface JvmLaunchPaths {
  javaExec?: string;
  classpath?: string[];
  modulePath?: string[];
  /**
   * Output roots the adapter weaves into `moduleName` with `--patch-module`.
   *
   * Forwarded rather than acted on here: a build tool may compile one module into several directories and only the
   * one with `module-info.class` is the module, so the adapter picks from these once the build has produced them.
   */
  moduleContentPaths?: string[];
  moduleName?: string;
  workingDirectory?: string;
}

/**
 * Whether a build tool launches this module, from `intellij.java.resolveBuildToolLaunch`.
 *
 * `tool` is absent when none does — a plain JPS module, or a Maven one, where no single invocation can both build the
 * reactor and exec just the target module. `scopeClassPaths` is what the debug session scopes breakpoints to; see
 * where it is written below.
 */
interface BuildToolLaunchResponse {
  tool?: string;
  moduleName?: string;
  scopeClassPaths?: string[];
}

/**
 * What the launch configuration itself says about how the JVM runs. Sent with the resolution request so the server
 * answers what will actually run; the rules for combining them are its business, not each client's (an *empty* array
 * is not an override — it is what this hook writes back into a config it has already resolved).
 */
interface JvmLaunchOverrides {
  classPaths?: string[];
  modulePaths?: string[];
  moduleName?: string;
  javaExec?: string;
}

/**
 * What a build-tool launch runs, handed to the adapter: the file whose module to launch, the JPMS module owning the
 * main class, and whatever the configuration said in the tool's own vocabulary.
 *
 * A target, not a command. The adapter resolves the tool's compile-and-run invocation at launch time, because only
 * then are `noDebug` and the debug port known and the debug agent can go into it.
 */
interface BuildToolTarget {
  uri: string;
  moduleName?: string;
  projectPath?: string;
  sourceSet?: string;
  toolArgs?: string[];
}

/** What both launch configurations have in common: what to run, and where its output goes. */
interface CommonLaunchConfig extends DebugConfiguration {
  request: 'launch';
  mainClass?: string;
  file?: string;
  args?: string[];
  vmArgs?: string[];
  cwd?: string;
  env?: Record<string, string>;
  console?: ConsoleKind;
  internalConsoleOptions?: 'neverOpen' | 'openOnSessionStart' | 'openOnFirstSessionStart';
  /** Label of a task VS Code runs before the session; ours is [BUILD_TASK_LABEL]. */
  preLaunchTask?: string;
}

/** A launch that runs the program as a plain `java` process; no build tool is involved at any point. */
interface JvmLaunchConfig extends CommonLaunchConfig {
  classPaths?: string[];
  modulePaths?: string[];
  moduleName?: string;
  javaExec?: string;
  /** Server-resolved, not user-authored: output roots the adapter patches into `moduleName`. */
  moduleContentPaths?: string[];
}

/**
 * A launch that runs the program *through Gradle*, which compiles it as part of running.
 *
 * Nothing here says how the JVM runs — no class path, no module path, no `java` binary — because Gradle decides all
 * three from the source set and the project's toolchain. What a user can say is which project and source set to run,
 * and what to pass to the build.
 */
interface GradleLaunchConfig extends CommonLaunchConfig {
  projectPath?: string;
  sourceSet?: string;
  gradleArgs?: string[];
  /** Server-resolved, not user-authored: what the adapter turns into Gradle's own command. */
  buildToolTarget?: BuildToolTarget;
  /** Server-resolved, not user-authored: the breakpoint scope of the debug session. */
  classPaths?: string[];
}

export function registerDapServer(context: ExtensionContext) {
  // One DAP server serves both configuration types: what differs is how a launch is *configured*, not how the
  // session is spoken. The adapter tells them apart by the arguments that arrive.
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
     * Hands the build task what this launch is about, and changes nothing else.
     *
     * This hook exists for the ordering: VS Code resolves configurations through *two* hooks and runs
     * `preLaunchTask` between them (`DebugService.createSession`: `resolveDebugConfiguration` → substitute variables
     * → run the task → `resolveDebugConfigurationWithSubstitutedVariables`). So this is the last moment at which a
     * user-authored `"preLaunchTask": "intellij: build"` can be told which module to compile — by the time the
     * configuration is fully resolved below, the build has already run.
     *
     * Without it the task has only the active editor to go on, which compiles whatever is focused and nothing at
     * all when that is launch.json itself: a Gradle or Maven module then launched against classes no one had
     * compiled.
     */
    async resolveDebugConfiguration(
      _folder: WorkspaceFolder | undefined,
      debugConfiguration: DebugConfiguration,
    ) {
      await prepareBuildForTask(debugConfiguration);
      return debugConfiguration;
    },

    async resolveDebugConfigurationWithSubstitutedVariables(
      _folder: WorkspaceFolder | undefined,
      debugConfiguration: DebugConfiguration,
    ) {
      if (debugConfiguration.request === 'attach') return debugConfiguration;
      if (debugConfiguration.request !== 'launch') return debugConfiguration;
      // The task has run by now and took its build out of the slot. Clearing here covers the launch that never
      // reached it — nothing to build, or a `preLaunchTask` pointing elsewhere — so no later task inherits it.
      // Only for a launch, which is the only request the slot is ever filled for.
      setPendingBuild(undefined);
      // Anything that is not the build tool's type is a JVM launch, which is what makes
      // [LEGACY_JVM_DEBUG_TYPE] an alias rather than a second code path.
      return debugConfiguration.type === GRADLE_DEBUG_TYPE
        ? await resolveGradleLaunchConfig(debugConfiguration as GradleLaunchConfig)
        : await resolveJvmLaunchConfig(debugConfiguration as JvmLaunchConfig);
    },
  };

  for (const type of [JVM_DEBUG_TYPE, LEGACY_JVM_DEBUG_TYPE, GRADLE_DEBUG_TYPE]) {
    context.subscriptions.push(
      debug.registerDebugAdapterDescriptorFactory(type, dapServerFactory),
      debug.registerDebugConfigurationProvider(type, debugConfigProvider),
    );
  }

  registerRunMainCodeLens(context);
}

/**
 * Registers the editor-side handling of the `intellij.jvm.runMain` code lens command
 * emitted by the server-side CodeLens provider, and declares to the server that this client
 * can handle the command via the `runMainCodeLens` initialization option. The server only
 * emits run/debug lenses when this option is set.
 */
function registerRunMainCodeLens(context: ExtensionContext) {
  registerInitializationOptionsContributor(() => ({ runMainCodeLens: true }));
  context.subscriptions.push(
    commands.registerCommand(RUN_MAIN_COMMAND, (arg: RunMainArgs) => runMainFromLens(arg)),
  );
}

/**
 * Serializes lens launches. The pending build is a single slot the injected build task consumes, so a second launch
 * must not overwrite it before the first launch's task has read it. `startDebugging` resolves only after
 * `preLaunchTask` has run, so awaiting the previous launch is enough to keep writer and reader in step.
 */
let lensLaunches: Promise<unknown> = Promise.resolve();

function runMainFromLens(arg: RunMainArgs): Promise<void> {
  const launch = lensLaunches.then(
    () => startLensLaunch(arg),
    () => startLensLaunch(arg),
  );
  lensLaunches = launch;
  return launch;
}

async function startLensLaunch(arg: RunMainArgs): Promise<void> {
  const folder = window.activeTextEditor
    ? workspace.getWorkspaceFolder(window.activeTextEditor.document.uri)
    : workspace.workspaceFolders?.[0];
  // Prefer the build tool's own configuration whenever one can run this module: it compiles as part of running, so a
  // lens launch through it needs no build step and no build terminal. The lens has no user to ask, which is why this
  // is the one place the *server's* answer picks the configuration type.
  const tool = arg.uri ? await lensBuildTool(arg.uri, arg.mainClass) : undefined;
  const config: DebugConfiguration = {
    type: (tool && DEBUG_TYPE_BY_TOOL[tool]) ?? JVM_DEBUG_TYPE,
    request: 'launch',
    name: arg.mainClass.split('.').pop() ?? 'Run main',
    mainClass: arg.mainClass,
  };
  if (arg.uri) config.file = Uri.parse(arg.uri).fsPath;

  // A JVM launch compiles nothing by itself, so it gets the build task — but only when there is something to
  // compile. Deciding it here rather than in the task is what keeps a launch from opening a terminal that has
  // nothing to do: VS Code runs `preLaunchTask` *before* `resolveDebugConfigurationWithSubstitutedVariables`, so a
  // task injected unconditionally has already run by the time the launch is resolved.
  // The lens always carries the file it was shown in (the server's `RunMainArgs.uri`); without one there is nothing
  // to resolve against, and the launch runs whatever is already compiled.
  const build = config.type === JVM_DEBUG_TYPE && arg.uri ? await lensBuild(arg.uri) : undefined;
  if (build) {
    // `preLaunchTask` is a label reference and carries no launch context, so hand the task its build out of band.
    setPendingBuild(build);
    config.preLaunchTask = BUILD_TASK_LABEL;
  }
  try {
    await debug.startDebugging(folder, config, { noDebug: arg.noDebug ?? false });
  } finally {
    // A launch that never reached the task (cancelled, or failed to start) must not leave its build behind for
    // the next task to pick up — a user-authored `preLaunchTask` would then build some unrelated module.
    setPendingBuild(undefined);
  }
}

/**
 * The build tool that would launch [uri]'s module, or `undefined` for none — and also for a failure to ask, which
 * falls back to a JVM launch rather than refusing: that path resolves everything again and reports properly, so a
 * transient failure here should not be the end of the launch.
 */
async function lensBuildTool(uri: string, mainClass: string): Promise<string | undefined> {
  const client = getLspClient();
  if (!client) return undefined;
  try {
    const response = await sendCommand<BuildToolLaunchResponse>(
      client,
      'intellij.java.resolveBuildToolLaunch',
      [{ uri, mainClass }],
    );
    return response.tool;
  } catch (e) {
    getOutputChannel().appendLine(
      `[lens] launching as a JVM program, the build tool could not be resolved: ${errorMessage(e)}`,
    );
    return undefined;
  }
}

/**
 * Resolves what [config]'s own build task should compile and leaves it in the task's slot.
 *
 * Only for a launch that references *this* extension's build task: any other `preLaunchTask` is someone else's, and
 * a task of the user's own with an explicit `file` in tasks.json already knows its target and takes precedence over
 * the slot.
 *
 * Resolving here rather than in the task is what gives the task a target at all, and it stays silent about failure
 * for the same reason the task does: a build that could not even be determined must not refuse a launch the user
 * asked for. The task then falls back to the active editor, as it did before this hook existed.
 */
async function prepareBuildForTask(config: DebugConfiguration): Promise<void> {
  if (config.request !== 'launch') return;
  if (config.preLaunchTask !== BUILD_TASK_LABEL) return;
  // The lens resolved its own build before starting the session; that answer is this launch's.
  if (hasPendingBuild()) return;
  const client = getLspClient();
  if (!client) return;
  try {
    const uri = await buildTargetUri(client, launchBuildTargetOf(config));
    if (!uri) return;
    setPendingBuild(buildToRun(await resolveBuildCommand(client, uri)));
  } catch (e) {
    getOutputChannel().appendLine(
      `[launch] the build task will resolve its own target, this launch's could not be resolved: ${errorMessage(e)}`,
    );
  }
}

/**
 * The build to run before a lens launch of [uri], or `undefined` when there is nothing to run.
 *
 * A failure to *resolve* the build never refuses the launch, matching the build task's own rule: only a build that
 * ran and failed should stop one. The launch then proceeds against whatever is already compiled, which is what the
 * output-channel line is for.
 */
async function lensBuild(uri: string): Promise<BuildToRun | undefined> {
  const client = getLspClient();
  if (!client) return undefined;
  try {
    return buildToRun(await resolveBuildCommand(client, uri));
  } catch (e) {
    getOutputChannel().appendLine(
      `[lens] launching without building, the build command could not be resolved: ${errorMessage(e)}`,
    );
    return undefined;
  }
}

/** The document URI of the launched class: the config's own file, or the file the server finds for the main class. */
async function launchTargetUri(
  client: LanguageClient,
  config: CommonLaunchConfig,
): Promise<string> {
  if (config.file) return client.code2ProtocolConverter.asUri(Uri.file(config.file));
  const response = await sendCommand<ClassDocumentResponse>(
    client,
    'intellij.java.resolveClassDocument',
    [{ fqn: config.mainClass }],
  );
  return response.uri;
}

/**
 * The client and main class every launch needs, or `undefined` once the reason it cannot start has been shown.
 *
 * Reported with a toast rather than thrown, and deliberately not awaited: awaiting blocks the config resolver until
 * the message is dismissed, which keeps VS Code in the "starting" state and prevents launching anything else.
 */
function launchPrerequisites(config: CommonLaunchConfig): LanguageClient | undefined {
  const client = getLspClient();
  if (!client) {
    void window.showErrorMessage('The language server for Java and Kotlin is not running');
    return undefined;
  }
  if (!config.mainClass) {
    void window.showErrorMessage(`The "${config.type}" configuration requires "mainClass"`);
    return undefined;
  }
  return client;
}

/** Fills in a JVM launch: the runtime paths to spawn `java` with, with this config's own values taking precedence. */
async function resolveJvmLaunchConfig(
  config: JvmLaunchConfig,
): Promise<DebugConfiguration | undefined> {
  const client = launchPrerequisites(config);
  if (!client) return undefined;
  try {
    const paths = await sendCommand<JvmLaunchPaths>(client, 'intellij.java.resolveLaunch', [
      {
        uri: await launchTargetUri(client, config),
        cwd: config.cwd,
        // The server merges these and answers what will actually run, so nothing about how to combine them is
        // decided here.
        overrides: {
          classPaths: config.classPaths,
          modulePaths: config.modulePaths,
          moduleName: config.moduleName,
          javaExec: config.javaExec,
        } satisfies JvmLaunchOverrides,
      },
    ]);
    config.classPaths = paths.classpath ?? [];
    // For a JPMS launch the answer carries the module path and the owning module name, so the main class is run from
    // the module path (`-m moduleName/mainClass`) instead of the class path.
    config.modulePaths = paths.modulePath ?? [];
    if (paths.moduleName) config.moduleName = paths.moduleName;
    // Gradle compiles a module into several directories and only the one with `module-info.class` is the module, so
    // the rest have to be patched back in or the module cannot read its own resources. Which ones to pass is the
    // adapter's call, since it needs them to exist on disk.
    config.moduleContentPaths = paths.moduleContentPaths ?? [];
    // The module's project directory, unless the config named one. Without either the launched process inherits the
    // language server's directory, so e.g. Spring Boot's docker-compose lookup fails.
    if (paths.workingDirectory) config.cwd = paths.workingDirectory;
    // Never absent: a launch with neither a configured nor a project JDK is refused by the server, which is the only
    // side that can tell the two apart.
    config.javaExec = paths.javaExec;
  } catch (e) {
    return failedToResolve(e);
  }
  return withConsoleDefaults(config);
}

/**
 * Fills in a Gradle launch: the target the adapter turns into Gradle's own compile-and-run command, plus the paths
 * the debug session is scoped to.
 */
async function resolveGradleLaunchConfig(
  config: GradleLaunchConfig,
): Promise<DebugConfiguration | undefined> {
  const client = launchPrerequisites(config);
  if (!client) return undefined;
  try {
    const uri = await launchTargetUri(client, config);
    const response = await sendCommand<BuildToolLaunchResponse>(
      client,
      'intellij.java.resolveBuildToolLaunch',
      [{ uri, mainClass: config.mainClass }],
    );
    if (response.tool === undefined) {
      // Named the wrong configuration type for this module: say so instead of quietly running it some other way,
      // which is the whole reason the two types are separate.
      throw new Error(
        `No build tool can launch "${config.mainClass}"; use a "${JVM_DEBUG_TYPE}" configuration instead`,
      );
    }
    config.buildToolTarget = {
      uri,
      moduleName: response.moduleName,
      projectPath: config.projectPath,
      sourceSet: config.sourceSet,
      toolArgs: config.gradleArgs,
    };
    // Gradle runs the debuggee itself, so nothing here says how — but the debug session still has to be scoped to
    // the module being run, or it falls back to the whole project and can resolve a breakpoint against a same-named
    // class in an unrelated module (LSP-1421).
    config.classPaths = response.scopeClassPaths ?? [];
  } catch (e) {
    return failedToResolve(e);
  }
  return withConsoleDefaults(config);
}

/** Reports why a launch could not be resolved, and refuses it. */
function failedToResolve(e: unknown): undefined {
  const message = errorMessage(e);
  getOutputChannel().appendLine(`[launch] resolution failed: ${message}`);
  void window.showErrorMessage(`Cannot start debugging: ${message}`);
  return undefined;
}

/**
 * Hands the resolved [config] to the DAP server, which decides where to run based on `console`:
 *  - integratedTerminal / externalTerminal → the server issues a `runInTerminal` request and VS Code runs the
 *    program inside a terminal shell (no "terminal process terminated" alert; real TTY; VS Code does quoting).
 *  - internalConsole → the server spawns the process itself and streams output to the Debug Console.
 *
 * The default matches java-debug's.
 */
function withConsoleDefaults(config: CommonLaunchConfig): DebugConfiguration {
  config.console = config.console ?? DEFAULT_CONSOLE;
  // Keep VSCode from popping the Debug Console (its default `internalConsoleOptions`) on top of the
  // console the user actually launched into, so focus follows `console` instead.
  config.internalConsoleOptions = internalConsoleOptionsFor(config.console);
  return config;
}

async function sendCommand<T>(
  client: LanguageClient,
  command: string,
  args: unknown[],
): Promise<T> {
  return await withTimeout(
    client.sendRequest('workspace/executeCommand', { command, arguments: args }) as Promise<T>,
    LSP_REQUEST_TIMEOUT_MS,
    command,
  );
}

function withTimeout<T>(promise: Promise<T>, ms: number, label: string): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const t = setTimeout(() => reject(new Error(`${label} timed out after ${ms}ms`)), ms);
    promise.then(
      (v) => {
        clearTimeout(t);
        resolve(v);
      },
      (e) => {
        clearTimeout(t);
        reject(e);
      },
    );
  });
}
