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

const DEBUG_TYPE = 'intellij_debugger';
const RUN_MAIN_COMMAND = 'intellij_debugger.runMain';
const LSP_REQUEST_TIMEOUT_MS = 30_000;

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
  file?: string;
  args?: string[];
  vmArgs?: string[];
  classPaths?: string[];
  modulePaths?: string[];
  javaExec?: string;
  cwd?: string;
  env?: Record<string, string>;
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

async function resolveLaunchConfig(config: LaunchConfig): Promise<LaunchConfig | undefined> {
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

    if (!config.classPaths || config.classPaths.length === 0) {
      const cp = await sendCommand<ClasspathResponse>(client, 'intellij.java.resolveClasspath', [
        { uri },
      ]);
      config.classPaths = cp.classpath;
    }

    if (!config.cwd) {
      // Default the working directory to the module's project directory. Without it the launched process
      // inherits the language server's directory, so e.g. Spring Boot's docker-compose lookup fails.
      const wd = await sendCommand<WorkingDirectoryResponse>(
        client,
        'intellij.java.resolveWorkingDirectory',
        [{ uri }],
      );
      if (wd.workingDirectory) config.cwd = wd.workingDirectory;
    }

    if (!config.javaExec) {
      const java = await sendCommand<JavaExecutableResponse>(
        client,
        'intellij.java.resolveJavaExecutable',
        [{ uri }],
      );
      config.javaExec = java.javaExec;
    }
  } catch (e) {
    const message = errorMessage(e);
    getOutputChannel().appendLine(`[launch.json] resolution failed: ${message}`);
    void window.showErrorMessage(`Cannot start debugging: ${message}`);
    return undefined;
  }

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
