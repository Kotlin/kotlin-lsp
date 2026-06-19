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
    WorkspaceFolder,
} from 'vscode';
import type { LanguageClient } from 'vscode-languageclient/node';
import { getLspClient } from './lspClient';
import { getOutputChannel } from './extension';

const DEBUG_TYPE = 'intellij_debugger';
const LSP_REQUEST_TIMEOUT_MS = 30_000;

interface ClassDocumentResponse {
    uri: string;
}

interface ClasspathResponse {
    classpath: string[];
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
        const uri = config.file ? client.code2ProtocolConverter.asUri(Uri.file(config.file)) : (
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

        if (!config.javaExec) {
            const java = await sendCommand<JavaExecutableResponse>(client, 'intellij.java.resolveJavaExecutable', [
                { uri },
            ]);
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

async function sendCommand<T>(client: LanguageClient, command: string, args: unknown[]): Promise<T> {
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
