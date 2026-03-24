import { Tree } from 'web-tree-sitter';

export type KeyHandler = (tree: Tree, key: string, offset: number) => KeyResult;

export interface KeyResult {
    text: string
    offset: number
}

export function keyResult(text: string, offset: number): KeyResult {
    return {text, offset};
}