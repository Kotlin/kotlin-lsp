import * as vscode from "vscode";
import {State} from "vscode-languageclient/node";
import { getContext } from "./extension";
import { getLspClient, subscribeToClientEvent } from "./lspClient";

const TITLE = 'Kotlin'
const LSP_TITLE = 'LSP'

let statusBarItem: vscode.StatusBarItem | undefined;

export function registerStatusBarItem() {
    statusBarItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 100);
    statusBarItem.text = TITLE;
    statusBarItem.show();
    updateView();
    getContext().subscriptions.push(statusBarItem);
    subscribeToClientEvent(() => updateView());
}

function updateView() {
    if (!statusBarItem) return;
    statusBarItem.tooltip = computeTooltip();
    statusBarItem.text = computeText();
}

function computeTooltip(): vscode.MarkdownString {
    const text = new vscode.MarkdownString()
    text.isTrusted = true;
    text.supportThemeIcons = true;
    text.supportHtml = true;

    const lspState = `<div>${getLspClientStatus()}</div>`
    text.appendMarkdown(`
<div>
<h4>Kotlin</h4>
${lspState}
</div>
        `   
    )
    return text;
}

function computeText(): string {
    const clientState = getLspClient()?.state ?? State.Stopped;

    switch (clientState) {
        case State.Running:
            return `\$(check) ${TITLE}`;
        case State.Starting:
            return `\$(sync) ${TITLE}`;
        default:
            return `\$(stop) ${TITLE}`;
    }
}

function getLspClientStatus(): string {
    const clientState = getLspClient()?.state ?? State.Stopped;
    const restartButton = `<a href="command:jetbrains.kotlin.restartLsp" title="Restart">$(sync)</a>`
    switch (clientState) {
        case State.Running:
            return `\$(check) ${LSP_TITLE}: Running&nbsp;&nbsp;${restartButton}`;
        case State.Starting:
            return `\$(sync) ${LSP_TITLE}: Starting`;
        default:
            return `\$(stop) ${LSP_TITLE}: Stopped&nbsp;&nbsp;${restartButton}`;
    }
}