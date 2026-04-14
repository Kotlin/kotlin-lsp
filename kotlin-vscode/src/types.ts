import { Tree } from 'web-tree-sitter';

export interface KeyEdit {
    startOffset: number
    endOffset: number
    newText: string
}

export interface KeyResult {
    edits: KeyEdit[]
    caretOffset: number
}

export type KeyHandler = (text: string, tree: Tree, key: string, offset: number, indentUnit: string) => KeyResult;

export function keyResult(text: string, offset: number, index: number, deleteLeft = 0, deleteRight = 0): KeyResult {
    const startOffset = index - deleteLeft;
    const endOffset = index + 1 + deleteRight;
    return {
        edits: [{
            startOffset,
            endOffset,
            newText: text,
        }],
        caretOffset: startOffset + offset,
    };
}

export function isIdentityKeyResult(index: number, key: string, result: KeyResult): boolean {
    return result.edits.length === 1 &&
            result.edits[0].startOffset === index &&
            result.edits[0].endOffset === index + key.length &&
            result.edits[0].newText === key &&
            result.caretOffset === index + key.length;
}
