import * as path from 'path';
import {commands, type ExtensionContext, extensions, type OutputChannel, Uri, window, workspace,} from 'vscode'
import {registerDecompiler, registerOpeningJars} from './decompiler'
import {initLspClient, startLspClient} from './lspClient';
import {registerStatusBarItem} from './statusBar';
import {registerDapServer} from './dap'
import {registerDatabase} from './database'
import {registerFileTemplates} from './fileTemplates'

let _context: ExtensionContext | undefined
let _outputChannel: OutputChannel | undefined;

export function getContext(): ExtensionContext {
    return _context!;
}

export function getOutputChannel(): OutputChannel {
    return _outputChannel!;
}

export function logInfo(text: string) {
    if (_outputChannel) {
        _outputChannel.appendLine(text)
    } else {
        console.log(text);
    }
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
const dynamicModulesContext = (require as any).context('.', true, /^\.\/[A-Za-z0-9_-]+\/module\.ts$/);

export async function activate(context: ExtensionContext) {
    _context = context;
    initOutputChannel(context);
    registerDecompiler(context);
    registerOpeningJars();
    registerDapServer(context);
    registerDatabase(context);
    registerExportWorkspaceToJsonCommand(context);
    registerStatusBarItem();
    registerFileTemplates(context);

    for (let key of dynamicModulesContext.keys()) {
        const module = dynamicModulesContext(key) as any;
        await module.default(context);
    }

    initLspClient();
    await startLspClient();
}

function initOutputChannel(context: ExtensionContext) {
    const extension = extensions.getExtension(context.extension.id);
    const pkg = extension?.packageJSON as { displayName?: string } | undefined;
    _outputChannel = window.createOutputChannel(pkg?.displayName ?? 'JetBrains LSP');
}