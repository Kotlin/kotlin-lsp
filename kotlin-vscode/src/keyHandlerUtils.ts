import type {Node, Tree} from 'web-tree-sitter';

export interface KeyResult {
    text: string
    startOffset: number
    endOffset: number
    caretOffset: number
}

export type KeyHandler = (text: string, tree: Tree, key: string, index: number, indentUnit: string) => KeyResult;

export interface BlockCommentContext {
    start: number
    end: number | null
    isDoc: boolean
}

interface PairedDelimiterContext {
    openIndex: number
    between: string
}

const OPENING_BY_CLOSING = new Map<string, string>([
    [')', '('],
    [']', '['],
    ['}', '{'],
]);

const CLOSING_BY_OPENING = new Map<string, string>([
    ['(', ')'],
    ['[', ']'],
    ['{', '}'],
]);

export function keyResult(text: string, startOffset: number, endOffset: number, caretOffset: number): KeyResult {
    return {
        text,
        startOffset,
        endOffset,
        caretOffset,
    };
}

export function hasAncestor(node: Node | null, types: Set<string>): boolean {
    for (let current: Node | null = node; current !== null; current = current.parent) {
        if (types.has(current.type)) {
            return true;
        }
    }
    return false;
}

export function findAncestor(node: Node | null, type: string): Node | null {
    for (let current: Node | null = node; current !== null; current = current.parent) {
        if (current.type === type) {
            return current;
        }
    }
    return null;
}

export function findAncestorAtEnter(node: Node, index: number, ancestorType: string): Node | null {
    const currentAncestor = findAncestor(node, ancestorType);
    if (currentAncestor !== null) {
        return currentAncestor;
    }

    const previousIndex = index - 1;
    if (previousIndex < 0) {
        return null;
    }

    return findAncestor(node.tree.rootNode.descendantForIndex(previousIndex), ancestorType);
}

export function findEnclosingErrorNode(node: Node, index: number): Node | null {
    const directError = findAncestor(node, 'ERROR');
    if (directError !== null) {
        return directError;
    }

    const previousIndex = index - 1;
    if (previousIndex < 0) {
        return null;
    }

    return findAncestor(node.tree.rootNode.namedDescendantForIndex(previousIndex), 'ERROR')
            ?? findLastErrorBeforeIndex(node.tree.rootNode, previousIndex);
}

export function getLineStart(text: string, index: number): number {
    let current = index;
    while (current > 0) {
        const char = text[current - 1];
        if (char === '\n' || char === '\r') {
            break;
        }
        current--;
    }
    return current;
}

export function getLineEnd(text: string, index: number): number {
    let current = index;
    while (current < text.length) {
        const char = text[current];
        if (char === '\n' || char === '\r') {
            break;
        }
        current++;
    }
    return current;
}

export function getNextLineStart(text: string, index: number): number | null {
    if (index >= text.length) {
        return null;
    }

    if (text[index] === '\n') {
        return index + 1;
    }
    if (text[index] === '\r') {
        return index + 1 + (text[index + 1] === '\n' ? 1 : 0);
    }
    return null;
}

export function getIndent(text: string, lineStart: number): string {
    let current = lineStart;
    while (current < text.length) {
        const char = text[current];
        if (char !== ' ' && char !== '\t') {
            break;
        }
        current++;
    }
    return text.slice(lineStart, current);
}

export function skipIndent(text: string, start: number, end: number): number {
    let current = start;
    while (current < end) {
        const char = text[current];
        if (char !== ' ' && char !== '\t') {
            break;
        }
        current++;
    }
    return current;
}

export function handleRegularEnter(text: string, index: number, indentUnit: string): KeyResult {
    const previousLineIndent = getPreviousNonEmptyLineIndent(text, index);
    const continuationIndent = previousLineIndent === null ? null : `${previousLineIndent}${indentUnit}`;
    const shouldUseContinuationIndent = shouldApplyContinuationIndent(text, index);

    const nextLineStart = getNextLineStart(text, index + 1);
    if (nextLineStart === null) {
        if (!shouldUseContinuationIndent || continuationIndent === null) {
            return keyResultWithOptionalBlockIndent(previousLineIndent, index);
        }
        const replacement = `\n${continuationIndent}`;
        return keyResult(replacement, index, index + 1, index + replacement.length);
    }

    const nextLineEnd = getLineEnd(text, nextLineStart);
    const nextLineContentStart = skipIndent(text, nextLineStart, nextLineEnd);
    const nextChar = text[nextLineContentStart];
    if (nextChar !== ')' && nextChar !== ']' && nextChar !== '}') {
        if (!shouldUseContinuationIndent || continuationIndent === null) {
            return keyResultWithOptionalBlockIndent(previousLineIndent, index);
        }
        const replacement = `\n${continuationIndent}`;
        return keyResult(replacement, index, index + 1, index + replacement.length);
    }

    if (previousLineIndent === null) {
        return keyResult('\n', index, index + 1, index + 1);
    }

    const indent = shouldUseContinuationIndent && continuationIndent !== null
            ? continuationIndent
            : previousLineIndent;
    const replacement = `\n${indent}`;
    return keyResult(replacement, index, index + 1, index + replacement.length);
}

export function keyResultWithOptionalBlockIndent(previousLineIndent: string | null, index: number): KeyResult {
    if (previousLineIndent === null || previousLineIndent.length === 0) {
        return keyResult('\n', index, index + 1, index + 1);
    }
    const replacement = `\n${previousLineIndent}`;
    return keyResult(replacement, index, index + 1, index + replacement.length);
}

export function shouldApplyContinuationIndent(text: string, index: number): boolean {
    if (isAfterStandaloneBlockCommentLine(text, index)) {
        return false;
    }
    const c = getPreviousSignificantChar(text, index);
    return c !== null && '([{,+-*/%&|^=?:'.includes(c);
}

export function isStandaloneBlockCommentLine(text: string): boolean {
    const trimmedText = text.trim();
    return trimmedText === '*/' || (trimmedText.startsWith('/*') && trimmedText.endsWith('*/'));
}

export function getPreviousNonEmptyLineIndent(text: string, index: number): string | null {
    const previousLine = getPreviousNonEmptyLine(text, index);
    return previousLine === null ? null : getIndent(text, previousLine.start);
}

export function getPreviousNonEmptyLine(text: string, index: number): { start: number, end: number, text: string } | null {
    let lineEnd = index;
    while (lineEnd > 0) {
        const lineStart = getLineStart(text, lineEnd - 1);
        const line = text.slice(lineStart, lineEnd);
        if (line.trim().length !== 0) {
            return {start: lineStart, end: lineEnd, text: line};
        }
        lineEnd = lineStart;
    }
    return null;
}

export function getClosedNodeBodyIndent(
        text: string,
        start: number,
        end: number | null,
        index: number,
        shouldIgnoreLine: (trimmedLine: string) => boolean,
): string | null {
    if (end === null) {
        return null;
    }

    const startLine = getLineStart(text, start);
    const nextLineStart = getNextLineStart(text, index + 1);
    if (nextLineStart !== null && nextLineStart < end) {
        const nextLine = text.slice(nextLineStart, getLineEnd(text, nextLineStart)).trim();
        if (nextLine.length !== 0 && !shouldIgnoreLine(nextLine)) {
            return getIndent(text, nextLineStart);
        }
    }

    const previousLine = getPreviousNonEmptyLine(text, index);
    if (previousLine === null || previousLine.start < startLine) {
        return null;
    }

    const previousLineText = previousLine.text.trim();
    if (shouldIgnoreLine(previousLineText)) {
        return null;
    }

    return getIndent(text, previousLine.start);
}

export function getPreviousSignificantChar(text: string, index: number): string | null {
    for (let current = index - 1; current >= 0; current--) {
        const char = text[current];
        if (char === ' ' || char === '\t' || char === '\n' || char === '\r') {
            continue;
        }
        return char;
    }
    return null;
}

export function handleEmptyPairedDelimiterEnter(
        node: Node,
        text: string,
        index: number,
        indentUnit: string,
): KeyResult | null {
    const context = findEnclosingPairedDelimiter(node, text, index);
    if (context === null) {
        return null;
    }

    const baseIndent = getIndent(text, getLineStart(text, context.openIndex));
    const bodyIndent = `${baseIndent}${indentUnit}`;
    if (/^[ \t]*\r?\n[ \t]*$/.test(context.between)) {
        return keyResult(`\n${bodyIndent}\n${baseIndent}`, index, index + 1, index + `\n${bodyIndent}`.length);
    }
    if (/^\r?\n\r?\n[ \t]*$/.test(context.between)) {
        return keyResult(`\n${bodyIndent}`, index, index + 1, index + `\n${bodyIndent}`.length);
    }
    return null;
}

export function findBlockCommentContext(
        node: Node,
        text: string,
        index: number,
        ancestorType: string,
): BlockCommentContext | null {
    const blockComment = findAncestor(node, ancestorType);
    if (blockComment?.text.startsWith('/*')) {
        return {
            start: blockComment.startIndex,
            end: blockComment.endIndex,
            isDoc: text.startsWith('/**', blockComment.startIndex),
        };
    }

    const errorNode = findEnclosingErrorNode(node, index);
    if (errorNode === null) {
        return null;
    }

    const start = findUnterminatedBlockCommentStart(errorNode, text, index);
    return start === null ? null : {start, end: null, isDoc: text.startsWith('/**', start)};
}

export function handleLineCommentEnter(
        text: string,
        node: Node,
        index: number,
        ancestorType: string,
        marker: string,
): KeyResult | null {
    const lineComment = findLineCommentAtEnter(text, node, index, ancestorType, marker);
    if (lineComment === null) {
        return null;
    }

    let lineEnd = index + 1;
    while (lineEnd < text.length && text[lineEnd] !== '\n' && text[lineEnd] !== '\r') {
        lineEnd++;
    }
    if (text.slice(index + 1, lineEnd).trim().length === 0) {
        const indent = getIndent(text, getLineStart(text, lineComment.startIndex));
        const replacement = `\n${indent}`;
        return keyResult(replacement, index, index + 1, index + replacement.length);
    }

    const lineStart = getLineStart(text, lineComment.startIndex);
    let prefixEnd = lineComment.startIndex + marker.length;
    while (prefixEnd < lineComment.endIndex) {
        const char = text[prefixEnd];
        if (char !== ' ' && char !== '\t') {
            break;
        }
        prefixEnd++;
    }
    const prefix = `${getIndent(text, lineStart)}${marker}${text.slice(lineComment.startIndex + marker.length, prefixEnd)}`;
    return keyResult(`\n${prefix}`, index, index + 1, index + prefix.length + 1);
}

export function handleStringLiteralEnter(
        text: string,
        node: Node,
        index: number,
        ancestorType: string,
        delimiter: string,
        concatenationOperator: string,
        indentUnit: string,
        excludedPrefixes: string[] = [],
): KeyResult | null {
    const stringLiteral = findAncestorAtEnter(node, index, ancestorType);
    if (stringLiteral === null || !text.startsWith(delimiter, stringLiteral.startIndex)) {
        return null;
    }
    if (excludedPrefixes.some((prefix) => text.startsWith(prefix, stringLiteral.startIndex))) {
        return null;
    }
    if (index >= stringLiteral.endIndex - delimiter.length) {
        return null;
    }

    const continuationIndent = `${getIndent(text, getLineStart(text, stringLiteral.startIndex))}${indentUnit}${indentUnit}`;
    const replacement = `${delimiter} ${concatenationOperator} \n${continuationIndent}${delimiter}`;
    return keyResult(replacement, index, index + 1, index + replacement.length - delimiter.length);
}

export function shouldSkipClosingDelimiter(
        text: string,
        node: Node,
        key: string,
        index: number,
        ignoredAncestorTypes: Set<string>,
): boolean {
    const expectedOpening = OPENING_BY_CLOSING.get(key);
    if (expectedOpening === undefined || text[index + 1] !== key) {
        return false;
    }

    const stack: string[] = [key];
    const root = node.tree.rootNode;
    for (let i = index - 1; i >= 0; i--) {
        const currentNode = root.descendantForIndex(i);
        if (currentNode === null) {
            continue;
        }

        const ignoredAncestor = findAncestorInSet(currentNode, ignoredAncestorTypes);
        if (ignoredAncestor !== null) {
            i = ignoredAncestor.startIndex;
            continue;
        }

        const char = text[i];
        if (OPENING_BY_CLOSING.has(char)) {
            stack.push(char);
            continue;
        }

        const closing = CLOSING_BY_OPENING.get(char);
        if (closing === undefined) {
            continue;
        }
        if (stack.at(-1) !== closing) {
            return false;
        }

        stack.pop();
        if (stack.length === 0) {
            return char === expectedOpening;
        }
    }

    return false;
}

function isAfterStandaloneBlockCommentLine(text: string, index: number): boolean {
    const previousLine = getPreviousNonEmptyLine(text, index)?.text;
    return previousLine !== undefined && isStandaloneBlockCommentLine(previousLine);
}

function findLastErrorBeforeIndex(node: Node, index: number): Node | null {
    for (let i = node.childCount - 1; i >= 0; i--) {
        const child = node.child(i);
        if (child === null || child.startIndex > index) {
            continue;
        }

        const error = findLastErrorBeforeIndex(child, index);
        if (error !== null) {
            return error;
        }
    }

    return node.type === 'ERROR' ? node : null;
}

function findEnclosingPairedDelimiter(
        node: Node,
        text: string,
        index: number,
): PairedDelimiterContext | null {
    for (let current: Node | null = node; current !== null; current = current.parent) {
        const startIndex = current.startIndex;
        const endIndex = current.endIndex;
        if (endIndex - startIndex < 2 || index < startIndex + 1 || index + 1 > endIndex - 1) {
            continue;
        }

        if (!isMatchingPair(text[startIndex], text[endIndex - 1])) {
            continue;
        }

        const between = text.slice(startIndex + 1, endIndex - 1);
        if (!/^[ \t\r\n]*$/.test(between)) {
            continue;
        }

        return {openIndex: startIndex, between};
    }

    return null;
}

function findAncestorInSet(node: Node | null, types: Set<string>): Node | null {
    for (let current: Node | null = node; current !== null; current = current.parent) {
        if (types.has(current.type)) {
            return current;
        }
    }
    return null;
}

function isMatchingPair(opening: string, closing: string): boolean {
    switch (opening) {
        case '(':
            return closing === ')';
        case '[':
            return closing === ']';
        case '{':
            return closing === '}';
        default:
            return false;
    }
}

function findUnterminatedBlockCommentStart(errorNode: Node, text: string, index: number): number | null {
    const errorStartIndex = errorNode.startIndex;
    const localLimit = index - errorStartIndex;
    if (localLimit < 2) {
        return null;
    }

    // Some grammars recover only the leading '/' of an unfinished block comment
    // as ERROR and attach the following '*' + code elsewhere, so scan up to the
    // caret instead of stopping at the error node's own end offset.
    const errorText = text.slice(errorStartIndex, index);
    const stack: number[] = [];

    for (let i = 0; i < localLimit; i++) {
        if (errorText.startsWith('/*', i)) {
            stack.push(i);
            i++;
            continue;
        }
        if (stack.length > 0 && errorText.startsWith('*/', i)) {
            stack.pop();
            i++;
        }
    }

    const openComment = stack.at(-1);
    return openComment === undefined ? null : errorStartIndex + openComment;
}

function findLineCommentAtEnter(
        text: string,
        node: Node,
        index: number,
        ancestorType: string,
        marker: string,
): Node | null {
    const lineComment = findAncestorAtEnter(node, index, ancestorType);
    if (lineComment !== null && text.startsWith(marker, lineComment.startIndex)) {
        return lineComment;
    }
    return null;
}
