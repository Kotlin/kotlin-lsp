import * as vscode from "vscode"
import {workspace} from "vscode"
import * as path from "node:path"
import {
    Disposable,
    LanguageClient,
    LanguageClientOptions,
    ServerOptions,
    State,
    StateChangeEvent,
    StreamInfo
} from 'vscode-languageclient/node';
import {chmodSync} from 'fs';
import * as net from 'node:net'
import * as os from 'node:os';
import {type ChildProcessByStdio, spawn} from 'node:child_process';
import {getContext, getOutputChannel, logInfo} from "./extension"
import {middleware} from "./middleware";
import * as readline from 'node:readline';
import {type Readable} from 'node:stream';

const BUNDLED_SERVER_START_TIMEOUT_MS = 60_000;
const BUNDLED_SERVER_CONNECTION_TIMEOUT_MS = 10_000;
const LOCAL_SERVER_CONNECTION_TIMEOUT_MS = 10_000;
const CONNECTION_RETRY_DELAY_MS = 100;

const LANGUAGE_CLIENT_ID = 'intellij';
const OPT_DEV_SERVER_PORT = 'intellij.dev.serverPort';
const OPT_JVM_ARGS = 'intellij.additionalJvmArgs';
const OPT_DEFAULT_WORKSPACE_SDK = 'intellij.jdkForSymbolResolution';
const OPT_BUILD_TOOL = 'intellij.buildTool';

let _client: LanguageClient | undefined;

const clientSubscriptions: ((client: LanguageClient, stateChange: StateChangeEvent) => void)[] = [];

export function initLspClient() {
    getContext().subscriptions.push(
         Disposable.create(async () => await stopLspClient()),
         vscode.commands.registerCommand('jetbrains.kotlin.restartLsp', async () => {
            await startLspClient();
            await vscode.window.showInformationMessage(extensionDisplayName() + ' restarted');
        }),
    );
}

/**
 * Subscribes to the LSP client events. The subscription will be called on every state change.
 *
 * We cannot subscribe to the client events directly because the client instance may be changed
 *
 * @param subscription - function to call on state change
 */
export function subscribeToClientEvent(subscription: (client: LanguageClient, stateChange: StateChangeEvent) => void) {
    clientSubscriptions.push(subscription)
    if (_client) {
        const client = _client
        getContext().subscriptions.push(
            client.onDidChangeState(e => subscription(client, e)),
        );
    }
}

/**
 * LSP client if it's running, undefined otherwise. The results should not be cached, because
 * it may be changed on restarts
 */
export function getLspClient(): LanguageClient | undefined {
    return _client
}

/**
 * Starts the LSP client applying all user options. If the client is already running, restarts it.
 */
export async function startLspClient(): Promise<void> {
    const runClient = await createLspClient()
    if (!runClient) return;
    await stopLspClient()
    _client = runClient;
    getContext().subscriptions.push(
        _client.onDidChangeState(e =>
            clientSubscriptions.forEach(s => s(runClient, e))
        )
   );

    await runClient.start()
}

/**
 * Stops LSP, if it's not running, does nothing.
 */
export async function stopLspClient(): Promise<void> {
    if (!_client) return
    if (_client.state == State.Running) {
        await _client.stop();
    }
    _client = undefined
}

function packageJson(): any | undefined {
    return vscode.extensions.getExtension(getContext().extension.id)?.packageJSON
}

function extensionDisplayName(): string {
    return packageJson()?.displayName ?? 'IntelliJ Language Server (fallback)'
}

function configOption<T>(name: string, scope?: vscode.ConfigurationScope): T | undefined {
    return workspace.getConfiguration(undefined, scope).get(name)
            ?? workspace.getConfiguration(undefined, scope).get( // TODO drop fallback
                    name.replace('intellij.', 'kotlinLSP.'))
}

function getLauncherPath(): string {
    const relative = path.join('server', 'bin')
    const launcherName = os.platform() === 'win32'
            ? 'languageServer.exe'
            : 'languageServer'
    const launcherPath = path.join(getContext().asAbsolutePath(relative), launcherName);
    if (os.platform() !== 'win32') {
        chmodSync(launcherPath, 0o755);
    }
    return launcherPath
}

function getServerOptions(): ServerOptions {
    const predefinedPort = configOption<number>(OPT_DEV_SERVER_PORT) ?? -1;
    if (predefinedPort == -1) {
        return getStreamInfoForBundledServer
    } else {
        return () => getStreamInfoForRunningServer(predefinedPort, LOCAL_SERVER_CONNECTION_TIMEOUT_MS);
    }
}

async function getStreamInfoForBundledServer(): Promise<StreamInfo> {
    const serverProcess = startBundledServer();
    try {
        const port = await getPortForBundledServer(serverProcess);
        return await getStreamInfoForRunningServer(port, BUNDLED_SERVER_CONNECTION_TIMEOUT_MS);
    } catch (e) {
        serverProcess.kill();
        throw e;
    }
}

function startBundledServer(): ChildProcessByStdio<null, Readable, null> {
    const debugLaunch = false
    const launcherPath = getLauncherPath();

    const context = getContext()
    const args: string[] = []
    args.push('run', '--socket', '0');
    if (context.storageUri) {
        args.push('--system-path', context.storageUri.fsPath)
    }
    const userJvmOptions = getUserJvmOptions()
    const jvmOptions = buildJvmOptionsEnv(process.env, userJvmOptions)
    const env: NodeJS.ProcessEnv = debugLaunch ? {
        ...jvmOptions,
        IJ_LAUNCHER_DEBUG: '1',
    } : jvmOptions

    logInfo('Starting language server');
    logInfo(`  command: ${launcherPath}`);
    logInfo(`  args   : ${JSON.stringify(args)}`);
    logInfo(`  VM opts: ${JSON.stringify(userJvmOptions)}`);
    if (debugLaunch) {
        logInfo(`  env: ${JSON.stringify(env)}`);
    }
    logInfo('');

    const serverProcess = spawn(launcherPath, args, {
        env,
        stdio: ['ignore', 'pipe', 'ignore'],
    });

    if (debugLaunch) {
        const rl = readline.createInterface({
            input: serverProcess.stdout,
            terminal: false,
        });
        rl.on('line', (line: string) => logInfo(`[launcher] ${line}`));
        serverProcess.once('exit', () => rl.close());
    }
    return serverProcess
}

function getPortForBundledServer(serverProcess: ChildProcessByStdio<null, Readable, null>): Promise<number> {
    return new Promise<number>((resolve, reject) => {

        const cleanup = () => {
            serverProcess.removeAllListeners('exit');
            serverProcess.removeAllListeners('error');

            clearTimeout(timer);
        }

        const onExit = (code: number | null, signal: NodeJS.Signals | null) => {
            reject(new Error(`Language server process exited before announcing port (code=${code}, signal=${signal})`));
        };

        const timer = setTimeout(() => {
            cleanup();
            serverProcess.kill();
            reject(new Error("Timed out waiting for language server port announcement"));
        }, BUNDLED_SERVER_START_TIMEOUT_MS);

        const rl = readline.createInterface({
            input: serverProcess.stdout,
            terminal: false
        });

        rl.on('line', (line: string) => {
            if (line.indexOf('Server is listening on ') >= 0) {
                const pos = line.lastIndexOf(':');
                if (pos > 0) {
                    const portString = line.substring(pos + 1);
                    const parsedPort = Number(portString);
                    if (Number.isInteger(parsedPort)) {
                        cleanup();
                        rl.close();
                        serverProcess.stdout.resume();
                        resolve(parsedPort);
                    }
                }
            }
        });

        serverProcess.once('error', reject);
        serverProcess.once('exit', onExit);
    });
}

async function getStreamInfoForRunningServer(port: number, timeoutMs: number): Promise<StreamInfo> {
    let timeout = timeoutMs;
    const deadline = Date.now() + timeoutMs;

    let error: unknown = null;
    while (timeout > 0) {
        try {
            const socket = await connectToPort(port, timeout);
            return {reader: socket, writer: socket};
        } catch (e) {
            logInfo(`Failed to connect to LSP server on port ${port}: ${e}`);
            error ??= e;
            await new Promise(resolve => setTimeout(resolve, CONNECTION_RETRY_DELAY_MS))
            timeout = deadline - Date.now();
            if (timeout > 0) {
                logInfo(`Retrying connection to LSP server`);
            }
        }
    }

    if (error) {
        throw error;
    }

    throw new Error(`Failed to connect to LSP server on port ${port}`);
}

function buildDocumentSelector(): LanguageClientOptions['documentSelector'] {
    const contributedLanguageIds: string[] = (packageJson()?.contributes?.languages ?? [])
        .map((l: { id: string }) => l.id);
    logInfo(`Serving languages: ${contributedLanguageIds.join(', ')}`);

    let supportedSchemes = ['file', 'jar', 'jrt']
    const selector: NonNullable<LanguageClientOptions['documentSelector']> = [
        {scheme: 'jar', language: 'plaintext'},
        {scheme: 'jrt', language: 'plaintext'},
    ];

    for (const lang of contributedLanguageIds) {
        for (const scheme of supportedSchemes) {
            selector.push({scheme, language: lang});
        }
    }
    return selector;
}

async function createLspClient(): Promise<LanguageClient | null> {
    const folders = workspace.workspaceFolders ?? []
    const clientOptions: LanguageClientOptions = {
        documentSelector: buildDocumentSelector(),
        progressOnInitialization: true,
        outputChannel: getOutputChannel(),
        initializationOptions: {
            defaultSdk: configOption(OPT_DEFAULT_WORKSPACE_SDK),
            buildTools: Object.fromEntries(
                    folders.map(folder => [
                        folder.uri.toString(),
                        configOption<string>(OPT_BUILD_TOOL, folder.uri),
                    ])
            ),
        },
        middleware: middleware,
        markdown: {
            supportHtml: true,
        }
    };
    let serverOptions = getServerOptions()
    if (!serverOptions) return null
    return new LanguageClient(LANGUAGE_CLIENT_ID, extensionDisplayName(), serverOptions, clientOptions);
}

function getUserJvmOptions(): string[] {
    return configOption<string[]>(OPT_JVM_ARGS) ?? []
}

function buildJvmOptionsEnv(baseEnv: NodeJS.ProcessEnv, extraOptions: string[]): NodeJS.ProcessEnv {
    if (extraOptions.length === 0) {
        return baseEnv
    }
    const OPTION = 'IJ_JAVA_OPTIONS'
    const env: NodeJS.ProcessEnv = {...baseEnv}

    const current = env[OPTION] ?? ''
    const extra = extraOptions.map(shellQuoteIfNeeded).join(' ')
    env[OPTION] = current ? `${current} ${extra}` : extra

    return env
}

function shellQuoteIfNeeded(arg: string): string {
    // No quoting needed
    if (/^[a-zA-Z0-9._=:/@-]+$/.test(arg)) {
        return arg
    }
    // Escape special characters
    const escaped = arg.replace(/(["\\$`])/g, '\\$1')
    return `"${escaped}"`
}

function connectToPort(port: number, timeoutMs: number): Promise<net.Socket> {
    return new Promise((resolve, reject) => {
        const socket = net.connect({port});

        const timer = setTimeout(() => {
            socket.destroy();
            reject(new Error(`Timed out connecting to port ${port}`));
        }, timeoutMs);

        const cleanup = () => {
            clearTimeout(timer);
            socket.removeAllListeners();
        };

        socket.once('connect', () => {
            cleanup();
            resolve(socket);
        });

        socket.once('error', (err) => {
            cleanup();
            reject(err);
        });
    });
}