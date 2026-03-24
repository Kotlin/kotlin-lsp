import * as vscode from 'vscode';
import {type KeyHandler} from './types';
import {DocumentParser} from "./DocumentParser"

const SYMBOLS = ['(', '[', '{', '<', '\'', '"', ')', ']', '}', '>'];

export async function registerHandleKeyType(context: vscode.ExtensionContext, parser: DocumentParser, handler: KeyHandler): Promise<void> {
    context.subscriptions.push(vscode.workspace.onDidChangeTextDocument((event) => {
        if (event.document.languageId !== parser.languageId
                || event.reason === vscode.TextDocumentChangeReason.Undo
                || event.reason === vscode.TextDocumentChangeReason.Redo
                || event.contentChanges.length !== 1) {
            return;
        }

        const editor = vscode.window.activeTextEditor;
        if (editor?.document !== event.document) {
            return;
        }
        const change = event.contentChanges[0];
        const key = change.text;

        if (key.length !== 1 || !SYMBOLS.includes(key) || change.rangeLength !== 0) {
            return;
        }

        const tree = parser.parseDocument(event.document);
        if (tree === null) {
            return;
        }

        const result = handler(tree, key, change.rangeOffset);

        if (result.text === key && result.offset === 1) {
            return;
        }

        const insertedRange = new vscode.Range(
                change.range.start,
                event.document.positionAt(event.document.offsetAt(change.range.start) + 1)
        );

        editor.edit((editBuilder) => {
            editBuilder.replace(insertedRange, result.text);
        }, {undoStopBefore: false, undoStopAfter: false});

        const newPosition = event.document.positionAt(event.document.offsetAt(change.range.start) + result.offset);
        editor.selection = new vscode.Selection(newPosition, newPosition);
    }, null, context.subscriptions));
}
