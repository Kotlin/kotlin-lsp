import {type ExtensionContext, Range, Selection, TextDocumentChangeReason, window, workspace} from 'vscode';
import type {DocumentParser} from "./DocumentParser"
import {isIdentityKeyResult, type KeyHandler} from './types';

const HANDLED_KEYS = new Set(['(', '[', '{', '<', '\'', '"', ')', ']', '}', '>', '\n']);
const ENTER_KEY_PATTERN = /^\r?\n[ \t]*$/;

export function registerHandleKeyType(context: ExtensionContext, parser: DocumentParser, handler: KeyHandler): void {
    context.subscriptions.push(workspace.onDidChangeTextDocument(async (event) => {
        if (event.document.languageId !== parser.languageId
                || event.reason === TextDocumentChangeReason.Undo
                || event.reason === TextDocumentChangeReason.Redo) {
            return;
        }

        const editor = window.activeTextEditor;
        if (editor?.document !== event.document) {
            return;
        }
        const change = event.contentChanges[0];
        const key = ENTER_KEY_PATTERN.test(change.text) ? '\n' : change.text;
        if (!HANDLED_KEYS.has(key) || change.rangeLength !== 0) {
            return;
        }

        const text = event.document.getText();
        const tree = parser.parseDocument(event.document);
        if (tree === null) {
            return;
        }

        const indentUnit = editor.options.insertSpaces === false ? '\t' : ' '.repeat(editor.options.indentSize as number);
        const result = handler(text, tree, key, change.rangeOffset, indentUnit);
        if (isIdentityKeyResult(change.rangeOffset, key, result)) {
            return;
        }

        await editor.edit((editBuilder) => {
            for (const edit of result.edits) {
                editBuilder.replace(
                        new Range(
                                event.document.positionAt(edit.startOffset),
                                event.document.positionAt(edit.endOffset),
                        ),
                        edit.newText,
                );
            }
        }, {undoStopBefore: false, undoStopAfter: false});

        const position = editor.document.positionAt(result.caretOffset);
        editor.selection = new Selection(position, position);
    }, null, context.subscriptions));
}
