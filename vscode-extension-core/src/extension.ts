import * as path from 'node:path';
import {
    commands,
    type Extension,
    type ExtensionContext,
    ExtensionMode,
    extensions,
    type LogOutputChannel,
    type OutputChannel,
    Uri,
    window,
    workspace,
} from 'vscode';
import { registerDecompiler, registerOpeningJars } from './decompiler';
import { getLspClient, initLspClient, startLspClient } from './lspClient';
export {
    registerInitializationOptionsContributor,
    type InitializationOptionsContributor,
} from './lspClient';
import { LSPErrorCodes, RequestType0, ResponseError } from 'vscode-languageclient/node';
import { registerStatusBarItem } from './statusBar';
import { registerDapServer } from './dap';
import { registerFileTemplates } from './fileTemplates';
import { checkLegacyKotlinExtensionConflict } from './legacyKotlinExtensionConflict';
import { registerAutoReloadWorkspace } from './autoReloadWorkspace';

export type ExtensionModule = (context: ExtensionContext) => void | Promise<void>;
export type GeoRestrictionCheck = (extension: Extension<unknown>) => Promise<boolean>;
export type EulaAcceptanceCheck = (context: ExtensionContext) => Promise<boolean>;
export type AcceptedEulaHashProvider = (context: ExtensionContext) => string | undefined;

const defaultCheckGeoRestricted: GeoRestrictionCheck = async () => false;
const defaultCheckEulaAccepted: EulaAcceptanceCheck = async () => true;
const defaultGetAcceptedEulaHash: AcceptedEulaHashProvider = () => undefined;

export interface ActivationOptions {
    modules: ExtensionModule[];
    /** Defaults to false. */
    enableDecompiler?: boolean;
    /** Defaults to false. */
    enableDapServer?: boolean;
    checkGeoRestricted?: GeoRestrictionCheck;
    checkEulaAccepted?: EulaAcceptanceCheck;
    getAcceptedEulaHash?: AcceptedEulaHashProvider;
}

interface ExtensionPackageJson {
    displayName?: string;
}

let _context: ExtensionContext | undefined;
let _outputChannel: LogOutputChannel | undefined;
let _buildOutputChannel: OutputChannel | undefined;

export function getContext(): ExtensionContext {
    return _context!;
}

export function getOutputChannel(): LogOutputChannel {
    return _outputChannel!;
}

export function getBuildOutputChannel(): OutputChannel {
    return _buildOutputChannel!;
}

export function logInfo(text: string): void {
    if (_outputChannel) {
        _outputChannel.appendLine(text);
    } else {
        console.log(text);
    }
}

function registerExportWorkspaceToJsonCommand(context: ExtensionContext): void {
    context.subscriptions.push(
        commands.registerCommand('jetbrains.exportWorkspaceToJson', async () => {
            if (workspace.rootPath === undefined) {
                await window.showErrorMessage('No workspace opened');
                return;
            }
            const rootPath = workspace.rootPath as string;
            await commands.executeCommand('exportWorkspace', rootPath);
            const choice = await window.showInformationMessage(
                'Exported workspace structure to workspace.json.',
                'Open',
            );
            if (choice === 'Open') {
                const uri = Uri.file(path.join(rootPath, 'workspace.json'));
                const doc = await workspace.openTextDocument(uri);
                await window.showTextDocument(doc);
            }
        }),
    );
}

const ReloadWorkspaceRequest = new RequestType0<null, void>('intellij/reloadWorkspace');

export async function reloadWorkspace(): Promise<void> {
    const client = getLspClient();
    if (client === undefined || client.initializeResult === undefined) {
        await window.showErrorMessage('Language server is not running');
        return;
    }
    try {
        await client.sendRequest(ReloadWorkspaceRequest);
        await window.showInformationMessage('Workspace reloaded');
    } catch (e) {
        if (e instanceof ResponseError && e.code === LSPErrorCodes.RequestCancelled) return;
        const message = e instanceof Error ? e.message : String(e);
        await window.showErrorMessage(`Failed to reload workspace: ${message}`);
    }
}

function registerShowBuildLogCommand(context: ExtensionContext): void {
    context.subscriptions.push(
        commands.registerCommand('jetbrains.showBuildLog', () => {
            getBuildOutputChannel().show(true);
            setTimeout(() => {
                void commands.executeCommand('workbench.action.output.action.scrollToBottom');
            }, 0);
        }),
    );
}

function registerReloadWorkspaceCommand(context: ExtensionContext): void {
    context.subscriptions.push(
        commands.registerCommand('jetbrains.kotlin.reloadWorkspace', () => reloadWorkspace()),
    );
}
export async function activateExtension(
    context: ExtensionContext,
    options: ActivationOptions,
): Promise<void> {
    _context = context;
    const getAcceptedEulaHash: AcceptedEulaHashProvider =
        options.getAcceptedEulaHash ?? defaultGetAcceptedEulaHash;
    const enableDecompiler = options.enableDecompiler ?? false;
    const enableDapServer = options.enableDapServer ?? false;
    initOutputChannel(context);

    const checkGeoRestrictedFn: GeoRestrictionCheck =
        options.checkGeoRestricted ?? defaultCheckGeoRestricted;
    if (await checkGeoRestrictedFn(context.extension)) {
        return;
    }
    const checkEulaAcceptedFn: EulaAcceptanceCheck =
        options.checkEulaAccepted ?? defaultCheckEulaAccepted;
    if (!(await checkEulaAcceptedFn(context))) {
        return;
    }

    if (checkLegacyKotlinExtensionConflict(context)) {
        return;
    }

    if (enableDecompiler) {
        registerDecompiler(context);
        registerOpeningJars();
    }
    if (enableDapServer) {
        registerDapServer(context);
    }
    registerExportWorkspaceToJsonCommand(context);
    registerReloadWorkspaceCommand(context);
    registerAutoReloadWorkspace(context);
    registerShowBuildLogCommand(context);
    registerStatusBarItem();
    registerFileTemplates(context);

    for (const module of options.modules) {
        await module(context);
    }

    initLspClient(getAcceptedEulaHash);
    await startLspClient(getAcceptedEulaHash);
}

function initOutputChannel(context: ExtensionContext): void {
    if (context.extensionMode === ExtensionMode.Test) {
        return;
    }

    const extension = extensions.getExtension(context.extension.id);
    const pkg = extension?.packageJSON as ExtensionPackageJson | undefined;
    const displayName = pkg?.displayName ?? 'JetBrains LSP';
    _outputChannel = window.createOutputChannel(displayName, { log: true });
    _buildOutputChannel = window.createOutputChannel(`${displayName} — Build`);
    context.subscriptions.push(_outputChannel, _buildOutputChannel);
}
