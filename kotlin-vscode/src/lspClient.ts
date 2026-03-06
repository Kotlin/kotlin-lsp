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
import * as net from "node:net"
import * as os from 'node:os';
import {spawn} from 'node:child_process';
import {getContext, getOutputChannel, logInfo} from "./extension"
import {middleware} from "./middleware";
import * as readline from 'node:readline';

let _client: LanguageClient | undefined;

const clientSubscriptions: ((client: LanguageClient, stateChange: StateChangeEvent) => void)[] = [];

export function initLspClient() {
    getContext().subscriptions.push(
         Disposable.create(async () => await stopLspClient()),
         vscode.commands.registerCommand('jetbrains.kotlin.restartLsp', async () => {
            await startLspClient();
            await vscode.window.showInformationMessage('Kotlin LSP restarted');
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

function getLauncherPath(): string {
    const relative = path.join('server', 'bin')
    const launcherName = os.platform() === 'win32'
            ? 'languageServer64.exe'
            : 'languageServer'
    const launcherPath = path.join(getContext().asAbsolutePath(relative), launcherName);
    if (os.platform() !== 'win32') {
        chmodSync(launcherPath, 0o755);
    }
    return launcherPath
}

async function createServerOptions(): Promise<ServerOptions | null> {
    const config = workspace.getConfiguration('kotlinLSP.dev');
    const predefinedPort = config.get<number>('serverPort', -1);
    if (predefinedPort != -1) {
        return await connectToLocalLspServer(predefinedPort);
    } else {
        return await getRunningJavaServerLspOptions()
    }
}

/**
 * Connects to an LSP server on the specified port with retry logic.
 * Waits for the server to become available, retrying multiple times if necessary.
 *
 * @param port - The port number to connect to
 * @returns A function that returns a Promise resolving to StreamInfo, or null if connection fails
 */
async function connectToLocalLspServer(port: number): Promise<(() => Promise<StreamInfo>) | null> {
    const maxRetries = 50;
    const retryDelayMs = 1000;

    for (let attempt = 0; attempt < maxRetries; attempt++) {
        try {
            const socket = net.connect({port});
            await new Promise<void>((resolve, reject) => {
                socket.once('connect', () => resolve());
                socket.once('error', (err) => reject(err));
            });
            const result: StreamInfo = {
                writer: socket,
                reader: socket
            };
            return () => Promise.resolve(result);
        } catch (error) {
            if (attempt < maxRetries - 1) {
                logInfo(`Waiting for server on port ${port}... (attempt ${attempt + 1}/${maxRetries})`);
                await new Promise(resolve => setTimeout(resolve, retryDelayMs));
            } else {
                vscode.window.showErrorMessage(
                        `Failed to connect to LSP server on port ${port} after ${maxRetries} attempts. ` +
                        `Please ensure the server is running.`
                );
                return null;
            }
        }
    }
    return null;
}

function buildDocumentSelector(): LanguageClientOptions['documentSelector'] {
    const ext = vscode.extensions.getExtension(getContext().extension.id);
    const contributedLanguageIds: string[] = (ext?.packageJSON?.contributes?.languages ?? [])
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
    const clientOptions: LanguageClientOptions = {
        documentSelector: buildDocumentSelector(),
        progressOnInitialization: true,
        outputChannel: getOutputChannel(),
        initializationOptions: {
            defaultJdk: workspace.getConfiguration().get('kotlinLSP.jdkForSymbolResolution')
        },
        middleware: middleware,
        markdown: {
            supportHtml: true,
        }
    };
    let serverOptions = await createServerOptions()
    if (!serverOptions) return null
    const displayName = vscode.extensions.getExtension(getContext().extension.id)?.packageJSON?.displayName ?? 'Kotlin LSP (fallback)'
    return new LanguageClient('kotlinLSP', displayName, serverOptions, clientOptions);
}


async function getRunningJavaServerLspOptions(): Promise<ServerOptions | null> {
    const launcherPath = getLauncherPath();

    const context = getContext()
    const args: string[] = []
    args.push('run', '--socket', '0');
    if (context.storageUri) {
        args.push('--system-path', context.storageUri.fsPath)
    }
    const userJvmOptions = getUserJvmOptions()
    const env = buildJvmOptionsEnv(process.env, userJvmOptions)

    logInfo('Starting language server');
    logInfo(`  command: ${launcherPath}`);
    logInfo(`  args   : ${JSON.stringify(args)}`);
    logInfo(`  VM opts: ${JSON.stringify(userJvmOptions)}`);
    logInfo('');

    const serverProcess = spawn(launcherPath, args, {
        env,
        stdio: ['ignore', 'pipe', 'ignore'],
    });

    const port = await new Promise<number>((resolve, reject) => {
        const timeoutMs = 10_000;

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
        }, timeoutMs);

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

    logInfo(`Language server is listening on port ${port}`);

    return await connectToLocalLspServer(port);
}

const jvmOptionsSettingName = 'kotlinLSP.additionalJvmArgs';

function getUserJvmOptions() : string[] {
    const settings = vscode.workspace.getConfiguration().get<string[]>(jvmOptionsSettingName)
    return settings ?? []
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
