import { Tree } from 'web-tree-sitter';

export interface KeyResult {
    text: string
    startOffset: number
    endOffset: number
    caretOffset: number
}

export type KeyHandler = (text: string, tree: Tree, key: string, offset: number, indentUnit: string) => KeyResult;

export function keyResult(text: string, startOffset: number, endOffset: number, caretOffset: number): KeyResult {
    return {
        text,
        startOffset,
        endOffset,
        caretOffset,
    };
}