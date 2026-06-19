import * as vscode from 'vscode';
import { State } from 'vscode-languageclient/node';
import { getContext } from './extension';
import { getLspClient, packageJson, subscribeToClientEvent } from './lspClient';

const STATUS_MENU_COMMAND = 'jetbrains.kotlin.showLspStatusMenu';

interface LspAction extends vscode.QuickPickItem {
    command: string;
}

function lspActions(): LspAction[] {
    return [
        { label: '$(sync) Restart Language Server', command: 'jetbrains.kotlin.restartLsp' },
        {
            label: '$(clear-all) Clear Caches and Restart Language Server',
            command: 'jetbrains.kotlin.clearCachesAndRestartLsp',
        },
    ];
}

/** Product name for the status bar, derived from the active extension's display name. */
function productTitle(options?: { shorten?: boolean }): string {
    const name = packageJson()?.displayName;
    return (options?.shorten ? name?.split(' ')[0] : name) ?? 'IntelliJ';
}

let statusBarItem: vscode.StatusBarItem | undefined;
let buildStatusBarItem: vscode.StatusBarItem | undefined;

export function registerStatusBarItem() {
    statusBarItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 100);
    statusBarItem.text = productTitle({ shorten: true });
    statusBarItem.command = STATUS_MENU_COMMAND;
    statusBarItem.show();
    updateView();

    // Keep build failures separate from the LSP-state item.
    buildStatusBarItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 99);
    buildStatusBarItem.command = 'jetbrains.showBuildLog';

    getContext().subscriptions.push(
        statusBarItem,
        buildStatusBarItem,
        vscode.commands.registerCommand(STATUS_MENU_COMMAND, showLspStatusMenu),
    );
    subscribeToClientEvent(() => updateView());
}

export function setBuildError(tool: string): void {
    if (!buildStatusBarItem) return;
    buildStatusBarItem.text = `$(warning) ${tool}: Build Error`;
    buildStatusBarItem.tooltip = 'Click to open the build log';
    buildStatusBarItem.backgroundColor = new vscode.ThemeColor('statusBarItem.warningBackground');
    buildStatusBarItem.show();
}

export function clearBuildError(): void {
    buildStatusBarItem?.hide();
}

function updateView() {
    if (!statusBarItem) return;
    statusBarItem.tooltip = computeTooltip();
    statusBarItem.text = computeText();
}

function computeTooltip(): vscode.MarkdownString {
    // Shown on hover; clicking the item opens the same actions as a QuickPick (STATUS_MENU_COMMAND).
    const text = new vscode.MarkdownString();
    text.isTrusted = true;
    text.supportThemeIcons = true;

    const actions = lspActions().map((a) => `[${a.label}](command:${a.command})`);
    text.appendMarkdown(
        [`**${productTitle()}**&nbsp;&nbsp;${stateText()}`, ...actions].join('\n\n'),
    );
    return text;
}

function computeText(): string {
    const clientState = getLspClient()?.state ?? State.Stopped;
    const title = productTitle({ shorten: true });
    switch (clientState) {
        case State.Running:
            return `$(check) ${title}`;
        case State.Starting:
            return `$(sync) ${title}`;
        default:
            return `$(stop) ${title}`;
    }
}

function stateText(): string {
    const clientState = getLspClient()?.state ?? State.Stopped;
    switch (clientState) {
        case State.Running:
            return '$(check) Running';
        case State.Starting:
            return '$(sync) Starting';
        default:
            return '$(stop) Stopped';
    }
}

async function showLspStatusMenu(): Promise<void> {
    const picked = await vscode.window.showQuickPick(lspActions(), {
        placeHolder: `${productTitle()} — language server actions`,
    });
    if (picked) {
        await vscode.commands.executeCommand(picked.command);
    }
}
