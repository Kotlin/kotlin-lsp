import * as path from 'path';
import {commands, type ExtensionContext, Uri, window, workspace} from "vscode"
import {registerDecompiler} from "./decompiler"
import { initLspClient, startLspClient } from './lspClient';
import { registerStatusBarItem } from './statusBar';


export const extensionId = 'kotlin'

let _context : ExtensionContext | undefined

export function getContext(): ExtensionContext {
    return _context!;
}

function registerExportWorkspaceToJsonCommand(context: ExtensionContext) {
    context.subscriptions.push(commands.registerCommand('jetbrains.exportWorkspaceToJson', async () => {
        if (workspace.rootPath === undefined) {
            await window.showErrorMessage('No workspace opened');
            return;
        }
        const rootPath = workspace.rootPath as string;
        await commands.executeCommand('exportWorkspace', rootPath);
        const choice = await window.showInformationMessage('Exported workspace structure to workspace.json.', 'Open');
        if (choice === 'Open') {
            const uri = Uri.file(path.join(rootPath, 'workspace.json'));
            const doc = await workspace.openTextDocument(uri);
            await window.showTextDocument(doc);
        }
    }));
}


export async function activate(context: ExtensionContext) {
    _context = context
    registerDecompiler(context)
    registerExportWorkspaceToJsonCommand(context)
    registerActivateLspForJavaCommand(context)
    restartOnConfigurationChange(context)
    registerStatusBarItem()
    initLspClient()
    await startLspClient()
}


function restartOnConfigurationChange(context: ExtensionContext) {
    context.subscriptions.push(
        workspace.onDidChangeConfiguration(async (event) => {
            if (event.affectsConfiguration('kotlinLSP')) {
                console.log('Configuration changed, attempting to restart language server.');
                await startLspClient();
            }
        })
    );
}

function registerActivateLspForJavaCommand(context: ExtensionContext) {
    context.subscriptions.push(commands.registerCommand('jetbrains.activateJavaLsp', async () => {
        // do nothing, lsp is already started here
    }));
}

