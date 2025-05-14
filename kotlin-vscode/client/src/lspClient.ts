import * as vscode from "vscode"
import * as path from "node:path"
import {promisify} from 'util';
import {exec} from 'child_process';
import * as fs from "node:fs"
import {ExtensionContext, workspace} from "vscode"
import {Disposable, LanguageClient, LanguageClientOptions, ServerOptions, State, StateChangeEvent, StreamInfo, TransportKind} from 'vscode-languageclient/node';
import * as net from "node:net"
import * as os from 'os';
import {extensionId, getContext} from "./extension"
import { runWithJavaSupport } from "./java";

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
 * We cannot subscibe to the client events directly, because the client instance may be changed
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
    const runClient = await createLpsClient()
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
const minimumSupportedJavaVersion = 17



function getJrePathForKotlinLSP() {
    return vscode.workspace.getConfiguration().get<string>(jrePathForLspSettingName)
}

async function getJavaPath(): Promise<string | null> {
    let jrePath = getJrePathForKotlinLSP();

    if (!jrePath) {
        return 'java'
    }

    const javaExecutable = os.platform() === 'win32' ? 'java.exe' : 'java';
    return path.join(jrePath, 'bin', javaExecutable);
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
            vscode.window.showErrorMessage(`Java version ${minimumSupportedJavaVersion} or higher is required to run Kotlin LSP.\n
             Current version: ${versionMatch[1]}.\n
             Please change the \`${jrePathForLspSettingName}\` setting to point to a JRE installation with version ${minimumSupportedJavaVersion} or higher.`,
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
                `Please ensure that \`${jrePathForLspSettingName}\` option is set correctly to to the JRE installation path.` +
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

async function createLpsClient(): Promise<LanguageClient | null> {
    const clientOptions: LanguageClientOptions = {
        documentSelector: [
            {scheme: 'file', language: 'kotlin'},
            {scheme: 'file', language: 'java'},
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

    const extractPath = getContext().asAbsolutePath(path.join('server', 'extracted', 'lib'));

    const args: string[] = []
    args.push(...jvmOptions)
    args.push(
        '-classpath', extractPath + path.sep + '*',
        'com.jetbrains.ls.kotlinLsp.KotlinLspServerKt', '--client',
    );
    if (runWithJavaSupport()) {
        args.push('--with-java')
    }
    return <ServerOptions>{
        command: javaCommand,
        args: args,
        transport: {
            kind: TransportKind.socket,
            port: 9998
        }
    };
}

const jvmOptions = [
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