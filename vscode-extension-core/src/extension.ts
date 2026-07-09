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
import {
  buildInitializationOptions,
  getLspClient,
  initLspClient,
  startLspClient,
  stopLspClient,
} from './lspClient';
export {
  getLspClient,
  prepareBundledServerLauncher,
  registerInitializationOptionsContributor,
  type InitializationOptionsContributor,
  stopLspClient,
  subscribeToClientEvent,
} from './lspClient';
import { LSPErrorCodes, RequestType, ResponseError } from 'vscode-languageclient/node';
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
let serverActivated = false;

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

interface ReloadWorkspaceParams {
  initializationOptions: Record<string, unknown>;
}

const ReloadWorkspaceRequest = new RequestType<ReloadWorkspaceParams, null, void>(
  'intellij/reloadWorkspace',
);

export async function reloadWorkspace(
  getAcceptedEulaHash: AcceptedEulaHashProvider,
): Promise<void> {
  const client = getLspClient();
  if (client === undefined || client.initializeResult === undefined) {
    await window.showErrorMessage('Language server is not running');
    return;
  }
  try {
    // Re-read settings now so changes (e.g. `intellij.projects`) take effect on reload.
    await client.sendRequest(ReloadWorkspaceRequest, {
      initializationOptions: buildInitializationOptions(getAcceptedEulaHash),
    });
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

function registerReloadWorkspaceCommand(
  context: ExtensionContext,
  getAcceptedEulaHash: AcceptedEulaHashProvider,
): void {
  context.subscriptions.push(
    commands.registerCommand('jetbrains.kotlin.reloadWorkspace', () =>
      reloadWorkspace(getAcceptedEulaHash),
    ),
  );
}
export async function activateExtension(
  context: ExtensionContext,
  options: ActivationOptions,
): Promise<void> {
  _context = context;
  const getAcceptedEulaHash: AcceptedEulaHashProvider =
    options.getAcceptedEulaHash ?? defaultGetAcceptedEulaHash;
  if (!serverActivated) {
    initOutputChannel(context);
  }

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

  // One-time contribution setup runs once per active extension context; geo and consent gates above
  // still run on every activation attempt.
  if (!serverActivated) {
    const enableDecompiler = options.enableDecompiler ?? false;
    const enableDapServer = options.enableDapServer ?? false;

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
    registerReloadWorkspaceCommand(context, getAcceptedEulaHash);
    registerAutoReloadWorkspace(context, getAcceptedEulaHash);
    registerShowBuildLogCommand(context);
    registerStatusBarItem();
    registerFileTemplates(context);

    for (const module of options.modules) {
      await module(context);
    }

    initLspClient(getAcceptedEulaHash);
    serverActivated = true;
  }

  await startLspClient({ getAcceptedEulaHash });
}

export async function deactivateExtension(): Promise<void> {
  await stopLspClient();
  serverActivated = false;
  _context = undefined;
  _outputChannel = undefined;
  _buildOutputChannel = undefined;
}

function initOutputChannel(context: ExtensionContext): void {
  if (context.extensionMode === ExtensionMode.Test) {
    return;
  }
  if (_outputChannel !== undefined && _buildOutputChannel !== undefined) {
    return;
  }

  const extension = extensions.getExtension(context.extension.id);
  const pkg = extension?.packageJSON as ExtensionPackageJson | undefined;
  const displayName = pkg?.displayName ?? 'JetBrains LSP';
  _outputChannel = window.createOutputChannel(displayName, { log: true });
  _buildOutputChannel = window.createOutputChannel(`${displayName} — Build`);
  context.subscriptions.push(_outputChannel, _buildOutputChannel);
}
