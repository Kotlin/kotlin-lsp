import {
    EndOfLine,
    type ExtensionContext,
    Range,
    Selection,
    TextDocumentChangeReason,
    window,
    workspace,
} from 'vscode';
import type { DocumentParser } from './DocumentParser';
import { type KeyHandler } from './keyHandlerUtils';
import { toCrLfLineEndings } from './lineEndings';

export const DEFAULT_HANDLED_KEYS: ReadonlySet<string> = new Set([
    '(',
    '[',
    '{',
    '<',
    "'",
    '"',
    ')',
    ']',
    '}',
    '>',
    '\n',
]);
const ENTER_KEY_PATTERN = /^\r?\n[ \t]*$/;

export function registerHandleKeyType(
    context: ExtensionContext,
    parser: DocumentParser,
    handler: KeyHandler,
    handledKeys: ReadonlySet<string> = DEFAULT_HANDLED_KEYS,
): void {
    context.subscriptions.push(
        workspace.onDidChangeTextDocument(
            async (event) => {
                if (
                    event.document.languageId !== parser.languageId ||
                    event.reason === TextDocumentChangeReason.Undo ||
                    event.reason === TextDocumentChangeReason.Redo
                ) {
                    return;
                }

                const editor = window.activeTextEditor;
                if (editor?.document !== event.document) {
                    return;
                }

                if (event.contentChanges.length !== 1) {
                    return;
                }

                const change = event.contentChanges[0];

                if (change.rangeLength !== 0) {
                    return;
                }

                const key = ENTER_KEY_PATTERN.test(change.text) ? '\n' : change.text;
                if (!handledKeys.has(key)) {
                    return;
                }

                const text = event.document.getText();
                const tree = parser.parseDocument(event.document);
                if (tree === null) {
                    return;
                }

                const indentUnit =
                    editor.options.insertSpaces === false
                        ? '\t'
                        : ' '.repeat(editor.options.indentSize as number);
                const offset = change.text.includes(`\r\n`)
                    ? change.rangeOffset + 1
                    : change.rangeOffset;
                const result = handler(text, tree, key, offset, indentUnit);
                let insertText = result.text;
                if (editor.document.eol === EndOfLine.CRLF) {
                    insertText = toCrLfLineEndings(result.text);
                }
                if (
                    result.startOffset !== change.rangeOffset ||
                    result.endOffset !== change.rangeOffset + 1 ||
                    insertText !== change.text
                ) {
                    await editor.edit(
                        (editBuilder) => {
                            editBuilder.replace(
                                new Range(
                                    event.document.positionAt(result.startOffset),
                                    event.document.positionAt(result.endOffset),
                                ),
                                insertText,
                            );
                        },
                        { undoStopBefore: false, undoStopAfter: false },
                    );
                    const position = editor.document.positionAt(result.caretOffset);
                    editor.selection = new Selection(position, position);
                }
            },
            null,
            context.subscriptions,
        ),
    );
}
