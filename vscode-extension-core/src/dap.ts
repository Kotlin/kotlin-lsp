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

const DEBUG_TYPE = 'intellij_debugger';
const RUN_MAIN_COMMAND = 'intellij_debugger.runMain';
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

interface ClasspathResponse {
  classpath: string[];
  modulePath?: string[];
  moduleName?: string;
}

interface WorkingDirectoryResponse {
  workingDirectory?: string;
}

interface JavaExecutableResponse {
  javaExec: string;
}

interface LaunchConfig extends DebugConfiguration {
  request: 'launch';
  mainClass?: string;
  moduleName?: string;
  file?: string;
  args?: string[];
  vmArgs?: string[];
  classPaths?: string[];
  modulePaths?: string[];
  javaExec?: string;
  cwd?: string;
  env?: Record<string, string>;
  console?: ConsoleKind;
  internalConsoleOptions?: 'neverOpen' | 'openOnSessionStart' | 'openOnFirstSessionStart';
}

export function registerDapServer(context: ExtensionContext) {
  const dapServerFactory: DebugAdapterDescriptorFactory = {
    async createDebugAdapterDescriptor(session: DebugSession) {
      const port: number = await commands.executeCommand(
        'start_debug_server',
        session.workspaceFolder?.uri.toString(),
      );
      return new DebugAdapterServer(port);
    },
  };
  context.subscriptions.push(
    debug.registerDebugAdapterDescriptorFactory(DEBUG_TYPE, dapServerFactory),
  );

  const debugConfigProvider: DebugConfigurationProvider = {
    async resolveDebugConfigurationWithSubstitutedVariables(
      _folder: WorkspaceFolder | undefined,
      debugConfiguration: DebugConfiguration,
    ) {
      if (debugConfiguration.type !== DEBUG_TYPE) return debugConfiguration;
      if (debugConfiguration.request === 'attach') return debugConfiguration;
      if (debugConfiguration.request !== 'launch') return debugConfiguration;
      return await resolveLaunchConfig(debugConfiguration as LaunchConfig);
    },
  };

  context.subscriptions.push(
    debug.registerDebugConfigurationProvider(DEBUG_TYPE, debugConfigProvider),
  );

  registerRunMainCodeLens(context);
}

/**
 * Registers the editor-side handling of the `intellij_debugger.runMain` code lens command
 * emitted by the server-side CodeLens provider, and declares to the server that this client
 * can handle the command via the `runMainCodeLens` initialization option. The server only
 * emits run/debug lenses when this option is set.
 */
function registerRunMainCodeLens(context: ExtensionContext) {
  registerInitializationOptionsContributor(() => ({ runMainCodeLens: true }));
  context.subscriptions.push(
    commands.registerCommand(RUN_MAIN_COMMAND, async (arg: RunMainArgs) => {
      const folder = window.activeTextEditor
        ? workspace.getWorkspaceFolder(window.activeTextEditor.document.uri)
        : workspace.workspaceFolders?.[0];
      const config: DebugConfiguration = {
        type: DEBUG_TYPE,
        request: 'launch',
        name: arg.mainClass.split('.').pop() ?? 'Run main',
        mainClass: arg.mainClass,
      };
      if (arg.uri) config.file = Uri.parse(arg.uri).fsPath;
      await debug.startDebugging(folder, config, { noDebug: arg.noDebug ?? false });
    }),
  );
}

async function resolveLaunchConfig(config: LaunchConfig): Promise<DebugConfiguration | undefined> {
  const client = getLspClient();
  if (!client) {
    // Do not await: awaiting blocks the config resolver until the toast is dismissed,
    // which keeps VSCode in the "starting" state and prevents launching another config.
    void window.showErrorMessage('IntelliJ LSP is not running');
    return undefined;
  }

  if (!config.mainClass) {
    void window.showErrorMessage("launch.json must specify 'mainClass'");
    return undefined;
  }
  try {
    const uri = config.file
      ? client.code2ProtocolConverter.asUri(Uri.file(config.file))
      : (
          await sendCommand<ClassDocumentResponse>(client, 'intellij.java.resolveClassDocument', [
            { fqn: config.mainClass },
          ])
        ).uri;

    // These three lookups are independent once we have the URI, so run them concurrently instead of
    // sequentially — three back-to-back LSP round-trips were a big chunk of the startup latency.
    const needsClasspath = !config.classPaths || config.classPaths.length === 0;
    const needsCwd = !config.cwd;
    const needsJavaExec = !config.javaExec;
    // classPaths and javaExec are required to launch, so failing to resolve them aborts the launch (the
    // rejection propagates to the catch below). The working directory is optional — the server falls back to
    // the module/project directory — so tolerate its failure instead of failing the whole launch.
    const [cp, wd, java] = await Promise.all([
      needsClasspath
        ? sendCommand<ClasspathResponse>(client, 'intellij.java.resolveClasspath', [{ uri }])
        : Promise.resolve(undefined),
      needsCwd
        ? sendCommand<WorkingDirectoryResponse>(client, 'intellij.java.resolveWorkingDirectory', [
            { uri },
          ]).catch((e) => {
            getOutputChannel().appendLine(
              `[launch.json] working directory resolution failed, using default: ${errorMessage(e)}`,
            );
            return undefined;
          })
        : Promise.resolve(undefined),
      needsJavaExec
        ? sendCommand<JavaExecutableResponse>(client, 'intellij.java.resolveJavaExecutable', [
            { uri },
          ])
        : Promise.resolve(undefined),
    ]);
    if (cp) {
      config.classPaths = cp.classpath;
      // For a JPMS launch the server also returns the module path and the owning module name, so the main
      // class is run from the module path (`-m moduleName/mainClass`) instead of the class path.
      if (cp.modulePath && cp.modulePath.length > 0) config.modulePaths = cp.modulePath;
      if (cp.moduleName) config.moduleName = cp.moduleName;
    }
    // Default the working directory to the module's project directory. Without it the launched process
    // inherits the language server's directory, so e.g. Spring Boot's docker-compose lookup fails.
    if (wd?.workingDirectory) config.cwd = wd.workingDirectory;
    if (java) config.javaExec = java.javaExec;
  } catch (e) {
    const message = errorMessage(e);
    getOutputChannel().appendLine(`[launch.json] resolution failed: ${message}`);
    void window.showErrorMessage(`Cannot start debugging: ${message}`);
    return undefined;
  }

  // Default the console to the integrated terminal (matching java-debug) and hand the resolved config to the
  // DAP server, which decides where to run based on `console`:
  //  - integratedTerminal / externalTerminal → the server issues a `runInTerminal` request and VS Code runs the
  //    program inside a terminal shell (no "terminal process terminated" alert; real TTY; VS Code does quoting).
  //  - internalConsole → the server spawns the process itself and streams output to the Debug Console.
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

function errorMessage(e: unknown): string {
  if (e instanceof Error) return e.message;
  if (typeof e === 'string') return e;
  return String(e);
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
