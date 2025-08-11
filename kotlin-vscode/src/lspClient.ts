import * as vscode from "vscode"
import {workspace} from "vscode"
import * as path from "node:path"
import {promisify} from 'util';
import {exec} from 'child_process';
import {
    Disposable,
    LanguageClient,
    LanguageClientOptions,
    ServerOptions,
    State,
    StateChangeEvent,
    StreamInfo,
    TransportKind
} from 'vscode-languageclient/node';
import {chmodSync} from 'fs';
import * as net from "node:net"
import * as os from 'os';
import {extensionId, getContext} from "./extension"

const execAsync = promisify(exec);

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


const jrePathForLspSettingName = 'kotlinLSP.jrePathToRunLsp';
// IMPORTANT: when updating this constant, please update the `kotlinLSP.jrePathToRunLsp` setting description in package.json as well
const minimumSupportedJavaVersion = 21


function getJrePathForKotlinLSP() {
    const configured = vscode.workspace.getConfiguration().get<string>(jrePathForLspSettingName)
    if (configured) return configured
    const relative = os.platform() === 'darwin'
            ? path.join('server', 'jre', 'Contents', 'Home')
            : path.join('server', 'jre')
    return getContext().asAbsolutePath(relative)
}

function getJavaPath(): string {
    let jrePath = getJrePathForKotlinLSP();

    if (!jrePath) {
        return 'java'
    }

    const javaExecutable = os.platform() === 'win32' ? 'java.exe' : 'java';
    const javaBin = path.join(jrePath, 'bin', javaExecutable);
    if (os.platform() !== 'win32') {
        chmodSync(javaBin, 0o755);
    }
    return javaBin
}

async function ensureCorrectJavaVersion(javaCommand: string): Promise<boolean> {
    try {
        const {stdout, stderr} = await execAsync(`"${javaCommand}" -version`);

        // Java version is usually reported in stderr
        const versionOutput = stdout || stderr;
        const versionMatch = versionOutput.match(/version "(\d+)(?:\.(\d+))?/);

        if (!versionMatch) {
            vscode.window.showErrorMessage('Failed to determine Java version.');
            return false;
        }

        const majorVersion = parseInt(versionMatch[1], 10);

        if (isNaN(majorVersion) || majorVersion < minimumSupportedJavaVersion) {
            const openSettingsButtonText = 'Open Settings'
            vscode.window.showErrorMessage(
                    `Kotlin LSP requires Java ${minimumSupportedJavaVersion} or later.
                     You are currently using version ${versionMatch[1]}. 
                     Please update the ${jrePathForLspSettingName} setting to point to a JRE ${minimumSupportedJavaVersion}+ installation`,
                openSettingsButtonText,
            ).then(selection => {
                if (selection === openSettingsButtonText) {
                    vscode.commands.executeCommand('workbench.action.openSettings', `${extensionId}.${jrePathForLspSettingName}`);
                }
            });
            return false;
        }

        return true;
    } catch (error) {
        console.error('Error executing Java command:', error);
        vscode.window.showErrorMessage('Failed to execute Java command to run lsp server. ' +
                `Please ensure that \`${jrePathForLspSettingName}\` option is set correctly to the JRE installation path.` +
                `Current value is \`${getJrePathForKotlinLSP()}\``);
        return false;
    }
}

async function createServerOptions(): Promise<ServerOptions | null> {
    const config = workspace.getConfiguration('kotlinLSP.dev');
    const predefinedPort = config.get<number>('serverPort', -1);
    if (predefinedPort != -1) {
        const socket = net.connect({port: predefinedPort});
        const result: StreamInfo = {
            writer: socket,
            reader: socket
        };
        return () => Promise.resolve(result);
    } else {
        return await getRunningJavaServerLspOptions()
    }
}

async function createLspClient(): Promise<LanguageClient | null> {
    const clientOptions: LanguageClientOptions = {
        documentSelector: [
            {scheme: 'file', language: 'kotlin'}, {scheme: 'jar', language: 'kotlin'},
            {scheme: 'file', language: 'java'  }, {scheme: 'jar', language: 'java'  }, {scheme: 'jrt', language: 'java'},
        ],
        progressOnInitialization: true,
    };
    let serverOptions = await createServerOptions()
    if (!serverOptions) return null
    return new LanguageClient('kotlinLSP', 'Kotlin LSP', serverOptions, clientOptions);
}


async function getRunningJavaServerLspOptions(): Promise<ServerOptions | null> {
    const javaCommand = await getJavaPath();
    if (!javaCommand) return null;

    const isJavaVersionValid = await ensureCorrectJavaVersion(javaCommand);
    if (!isJavaVersionValid) return null;

    const extractPath = getContext().asAbsolutePath(path.join('server', 'lib'));

    const context = getContext()
    const args: string[] = []
    args.push(...defaultJvmOptions)
    args.push(...getUserJvmOptions())
    args.push(
        '-classpath', extractPath + path.sep + '*',
        'com.jetbrains.ls.kotlinLsp.KotlinLspServerKt', '--client',
        '--system-path', (context.storageUri ?? context.globalStorageUri).fsPath,
    );
    return <ServerOptions>{
        command: javaCommand,
        args: args,
        transport: {
            kind: TransportKind.socket,
            port: 9998
        }
    };
}

const jvmOptionsSettingName = 'kotlinLSP.additionalJvmArgs';

function getUserJvmOptions() : string[] {
    const settings = vscode.workspace.getConfiguration().get<string[]>(jvmOptionsSettingName)
    return settings ?? []
}

const defaultJvmOptions = [
    "--add-opens", "java.base/java.io=ALL-UNNAMED",
    "--add-opens", "java.base/java.lang=ALL-UNNAMED",
    "--add-opens", "java.base/java.lang.ref=ALL-UNNAMED",
    "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
    "--add-opens", "java.base/java.net=ALL-UNNAMED",
    "--add-opens", "java.base/java.nio=ALL-UNNAMED",
    "--add-opens", "java.base/java.nio.charset=ALL-UNNAMED",
    "--add-opens", "java.base/java.text=ALL-UNNAMED",
    "--add-opens", "java.base/java.time=ALL-UNNAMED",
    "--add-opens", "java.base/java.util=ALL-UNNAMED",
    "--add-opens", "java.base/java.util.concurrent=ALL-UNNAMED",
    "--add-opens", "java.base/java.util.concurrent.atomic=ALL-UNNAMED",
    "--add-opens", "java.base/java.util.concurrent.locks=ALL-UNNAMED",
    "--add-opens", "java.base/jdk.internal.vm=ALL-UNNAMED",
    "--add-opens", "java.base/sun.net.dns=ALL-UNNAMED",
    "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
    "--add-opens", "java.base/sun.nio.fs=ALL-UNNAMED",
    "--add-opens", "java.base/sun.security.ssl=ALL-UNNAMED",
    "--add-opens", "java.base/sun.security.util=ALL-UNNAMED",
    "--add-opens", "java.desktop/com.apple.eawt=ALL-UNNAMED",
    "--add-opens", "java.desktop/com.apple.eawt.event=ALL-UNNAMED",
    "--add-opens", "java.desktop/com.apple.laf=ALL-UNNAMED",
    "--add-opens", "java.desktop/com.sun.java.swing=ALL-UNNAMED",
    "--add-opens", "java.desktop/com.sun.java.swing.plaf.gtk=ALL-UNNAMED",
    "--add-opens", "java.desktop/java.awt=ALL-UNNAMED",
    "--add-opens", "java.desktop/java.awt.dnd.peer=ALL-UNNAMED",
    "--add-opens", "java.desktop/java.awt.event=ALL-UNNAMED",
    "--add-opens", "java.desktop/java.awt.font=ALL-UNNAMED",
    "--add-opens", "java.desktop/java.awt.image=ALL-UNNAMED",
    "--add-opens", "java.desktop/java.awt.peer=ALL-UNNAMED",
    "--add-opens", "java.desktop/javax.swing=ALL-UNNAMED",
    "--add-opens", "java.desktop/javax.swing.plaf.basic=ALL-UNNAMED",
    "--add-opens", "java.desktop/javax.swing.text=ALL-UNNAMED",
    "--add-opens", "java.desktop/javax.swing.text.html=ALL-UNNAMED",
    "--add-opens", "java.desktop/sun.awt=ALL-UNNAMED",
    "--add-opens", "java.desktop/sun.awt.X11=ALL-UNNAMED",
    "--add-opens", "java.desktop/sun.awt.datatransfer=ALL-UNNAMED",
    "--add-opens", "java.desktop/sun.awt.image=ALL-UNNAMED",
    "--add-opens", "java.desktop/sun.awt.windows=ALL-UNNAMED",
    "--add-opens", "java.desktop/sun.font=ALL-UNNAMED",
    "--add-opens", "java.desktop/sun.java2d=ALL-UNNAMED",
    "--add-opens", "java.desktop/sun.lwawt=ALL-UNNAMED",
    "--add-opens", "java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
    "--add-opens", "java.desktop/sun.swing=ALL-UNNAMED",
    "--add-opens", "java.management/sun.management=ALL-UNNAMED",
    "--add-opens", "jdk.attach/sun.tools.attach=ALL-UNNAMED",
    "--add-opens", "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
    "--add-opens", "jdk.internal.jvmstat/sun.jvmstat.monitor=ALL-UNNAMED",
    "--add-opens", "jdk.jdi/com.sun.tools.jdi=ALL-UNNAMED",
]
