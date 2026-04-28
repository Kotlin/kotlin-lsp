import {Tree, Node} from 'web-tree-sitter';
import {type KeyResult, keyResult} from '../types';

const DEFAULT_TRIM_MARGIN_CHAR = '|';
const MULTILINE_QUOTE = '"""';
const TEXT_NODE_TYPES = new Set([
    'block_comment',
    'line_comment',
    'string_content',
]);
const ENTER_LITERAL_ANCESTOR_TYPES = new Set([
    'string_content',
    'string_literal',
    'character_literal',
]);

export default (text: string, tree: Tree, key: string, index: number, indentUnit: string): KeyResult => {
    switch (key) {
        case '\n':
            return handleEnter(text, tree, index, indentUnit);
        case '(':
            return handleLeftParenthesis(text, tree, index);
        case '[':
            return handleLeftBracket(text, tree, index);
        case '{':
            return handleLeftBrace(text, tree, index);
        case `'`:
            return handleSingleQuote(tree, index);
        case '"':
            return handleDoubleQuoteKey(text, tree, index);
        case '<':
            return handleLeftAngle(tree, index);
        case ')':
        case ']':
        case '>':
        case '}':
            return handleClosingDelimiter(text, tree, key, index);
        default:
            return keyResult(key, index, index + 1, index + key.length);
    }
}

function handleLeftParenthesis(text: string, tree: Tree, index: number): KeyResult {
    return handleOpeningDelimiter(text, tree, index, '(', ')');
}

function handleLeftBracket(text: string, tree: Tree, index: number): KeyResult {
    return handleOpeningDelimiter(text, tree, index, '[', ']');
}

function handleOpeningDelimiter(text: string, tree: Tree, index: number, opening: string, closing: string): KeyResult {
    const root = tree.rootNode;
    const node = root.descendantForIndex(index);
    if (node === null) return keyResult(opening, index, index + 1, index + opening.length);
    if (node.type === 'block_comment') return handleBlockComment(node, opening, text, index);
    return isTextNode(node) ? keyResult(opening, index, index + 1, index + 1) : getOpenBraceResult(text, index, opening, closing);
}

function handleLeftBrace(text: string, tree: Tree, index: number): KeyResult {
    const root = tree.rootNode;
    const node = root.descendantForIndex(index);
    if (node === null) return keyResult('{', index, index + 1, index + 1);
    if (isTextNode(node)) {
        return keyResult('{', index, index + 1, index + 1);
    }
    // `${...}` templates are the one place where '{' inside a string should
    // still behave like a paired delimiter.
    if (text[index - 1] === '$' && text[index + 1] === '}') {
        return keyResult('{', index, index + 1, index + 1);
    }
    if (text[index - 1] === '$') {
        return keyResult('{}', index, index + 1, index + 1);
    }
    return getOpenBraceResult(text, index, '{', '}');
}

function handleSingleQuote(tree: Tree, index: number): KeyResult {
    const node = tree.rootNode.descendantForIndex(index);
    if (node === null) return keyResult(`'`, index, index + 1, index + 1);
    return isTextNode(node)
            ? keyResult(`'`, index, index + 1, index + 1)
            : node.parent?.type === 'character_literal'
                    ? keyResult('', index, index + 1, index + 1)
                    : keyResult(`''`, index, index + 1, index + 1);
}

function handleDoubleQuoteKey(text: string, tree: Tree, index: number): KeyResult {
    const root = tree.rootNode;
    const node = root.descendantForIndex(index);
    if (node === null) return keyResult('"', index, index + 1, index + 1);

    // Kotlin overtypes an existing closing quote instead of inserting a duplicate one.
    if (node.type !== '"""' && text[index + 1] === '"') {
        return keyResult('', index, index + 1, index + 1);
    }

    if (isTextNode(node)) {
        return keyResult('"', index, index + 1, index + 1);
    }
    switch (node.type) {
        case '"':
            return handleDoubleQuote(node, index);
        case '"""':
            return handleTripleQuote(node, index);
        default:
            return keyResult('"', index, index + 1, index + 1);
    }
}

function handleTripleQuote(node: Node, index: number): KeyResult {
    switch (index - node.startIndex) {
        case 0:
            return keyResult('"', index, index + 1, index + 1);
        case 1:
            return keyResult('', index, index + 1, index + 1);
        default:
            return keyResult('""""', index, index + 1, index + 1);
    }
}

function handleDoubleQuote(node: Node, index: number): KeyResult {
    const parent = node.parent;
    if (parent === null) return keyResult('"', index, index + 1, index + 1);

    switch (parent.type) {
        case 'string_literal':
            // The Kotlin grammar may eagerly attach this quote to a later closing quote,
            // even though the user just started a new string. If this quote is the
            // opening delimiter of that literal, keep pairing it as an opening quote.
            return node.startIndex === parent.startIndex ? keyResult('""', index, index + 1, index + 1) : keyResult('"', index, index + 1, index + 1);
        case 'string_content':
            return keyResult('"', index, index + 1, index + 1);
        default:
            return keyResult('""', index, index + 1, index + 1);
    }
}

function handleEnter(text: string, tree: Tree, index: number, indentUnit: string): KeyResult {
    const node = tree.rootNode.descendantForIndex(index);
    if (node === null) return keyResult('\n', index, index + 1, index + 1);

    const lineCommentEnterResult = handleLineCommentEnter(text, node, index);
    if (lineCommentEnterResult !== null) {
        return lineCommentEnterResult;
    }

    const stringLiteralEnterResult = handleStringLiteralEnter(text, node, index, indentUnit);
    if (stringLiteralEnterResult !== null) {
        return stringLiteralEnterResult;
    }

    if (hasAncestor(node, ENTER_LITERAL_ANCESTOR_TYPES)) {
        return keyResult('\n', index, index + 1, index + 1);
    }

    const lambdaEnterResult = handleEmptyLambdaBodyEnter(node, text, index, indentUnit);
    if (lambdaEnterResult !== null) {
        return lambdaEnterResult;
    }

    const commentContext = findBlockCommentContext(node, text, index);
    if (commentContext === null) {
        return keyResult('\n', index, index + 1, index + 1);
    }

    const commentIndent = getIndent(text, getLineStart(text, commentContext.start));
    const currentLineTail = getCurrentLineTail(text, index + 1);
    const tailStartsWithAsterisk = /^\s*\*/.test(currentLineTail);
    const whitespaceSinceCommentStart = /^\s*$/.test(text.slice(commentContext.start + (commentContext.isDoc ? 3 : 2), index));
    const hasAsteriskLineBefore = hasLineWithPrefix(text, commentContext.start, index, '*');
    const useLinePrefix = commentContext.isDoc ||
            hasAsteriskLineBefore ||
            tailStartsWithAsterisk ||
            (whitespaceSinceCommentStart && (commentContext.end === null || currentLineTail.trim().length === 0));

    if (!useLinePrefix && commentContext.end !== null) {
        return keyResult('\n', index, index + 1, index + 1);
    }

    const prefixLine = `\n${commentIndent} ${useLinePrefix ? '* ' : ''}`;
    const rewrittenTailResult = rewriteCommentLineTail(commentContext, commentIndent, currentLineTail, prefixLine, index, useLinePrefix);
    if (rewrittenTailResult !== null) {
        return rewrittenTailResult;
    }

    if (commentContext.end === null) {
        return keyResult(`${prefixLine}\n${commentIndent} */`, index, index + 1, index + prefixLine.length);
    }
    return keyResult(prefixLine, index, index + 1, index + prefixLine.length);
}

function handleEmptyLambdaBodyEnter(
        node: Node,
        text: string,
        index: number,
        indentUnit: string,
): KeyResult | null {
    const lambda = findAncestor(node, 'lambda_literal');
    if (lambda === null) {
        return null;
    }

    const arrow = findChild(lambda, '->');
    const closingBrace = findChild(lambda, '}');
    if (arrow === null || closingBrace === null) {
        return null;
    }

    const arrowEndIndex = arrow.endIndex;
    const closingBraceStartIndex = closingBrace.startIndex;
    if (index < arrowEndIndex || index + 1 > closingBraceStartIndex) {
        return null;
    }

    if (findNamedChildInRange(lambda, arrow.endIndex, closingBrace.startIndex) !== null) {
        return null;
    }

    const whitespaceAfterArrow = text.slice(arrowEndIndex, index);
    if (!/^[ \t]*$/.test(whitespaceAfterArrow)) {
        return null;
    }

    const baseIndent = getIndent(text, getLineStart(text, lambda.startIndex));
    const bodyIndent = `${baseIndent}${indentUnit}`;
    const replacement = `\n${bodyIndent}\n${baseIndent}`;
    return keyResult(replacement, index - whitespaceAfterArrow.length, index + 1, index - whitespaceAfterArrow.length + `\n${bodyIndent}`.length);
}

function handleLeftAngle(tree: Tree, index: number): KeyResult {
    const root = tree.rootNode;
    const node = root.descendantForIndex(index);
    if (node === null) return keyResult('<', index, index + 1, index + 1);
    if (isTextNode(node)) {
        return keyResult('<', index, index + 1, index + 1);
    }
    const prev = prevNode(node);
    if (prev === null) return keyResult('<', index, index + 1, index + 1);
    switch (prev.type) {
        case 'identifier': {
            const looksLikeAClass = prev.endIndex === node.startIndex && prev.text[0] === prev.text[0].toUpperCase();
            return looksLikeAClass ? keyResult('<>', index, index + 1, index + 1) : keyResult('<', index, index + 1, index + 1);
        }
        case 'fun':
            return keyResult('<>', index, index + 1, index + 1);
        default:
            return keyResult('<', index, index + 1, index + 1);
    }
}

function handleClosingDelimiter(text: string, tree: Tree, key: string, index: number): KeyResult {
    const root = tree.rootNode;
    const node = root.descendantForIndex(index);
    if (node === null) return keyResult(key, index, index + 1, index + key.length);

    if (isTextNode(node)) {
        return keyResult(key, index, index + 1, index + 1);
    }
    if (index < root.endIndex - 1 && text[index + 1] === key) {
        // Only skip a closer while we are still inside the same broken
        // parser subtree; otherwise treat the key as a literal character.
        for (let i = index + 1; i < root.endIndex; i++) {
            const nextNode = root.descendantForIndex(i);
            if (nextNode?.isError) {
                return keyResult('', index, index + 1, index + 1);
            }
            switch (nextNode?.type) {
                case node.type:
                    if (nextNode.parent?.isError) {
                        return keyResult('', index, index + 1, index + 1);
                    }
                    break;
                case 'source_file':
                    if (text[i] === key) {
                        return keyResult('', index, index + 1, index + 1);
                    }
                    break;
                default:
                    return keyResult(key, index, index + 1, index + 1);
                }
            }
        }
    return keyResult(key, index, index + 1, index + 1);
}

function handleBlockComment(node: Node, key: string, text: string, index: number): KeyResult {
    // tree-sitter-kotlin does not expose dedicated KDoc tokens, so detect KDoc
    // by its opening marker and keep the extra () / [] pairing there.
    if (!node.text.startsWith('/**')) {
        return keyResult(key, index, index + 1, index + 1);
    }

    switch (key) {
        case '(':
            return getOpenBraceResult(text, index, '(', ')');
        case '[':
            return getOpenBraceResult(text, index, '[', ']');
        default:
            return keyResult(key, index, index + 1, index + 1);
    }
}

function getOpenBraceResult(text: string, textIndex: number, opening: string, closing: string): KeyResult {
    switch (text[textIndex + 1]) {
        case undefined:
        case ' ':
        case '\t':
        case '\n':
        case ':':
        case ';':
        case ',':
        case ')':
        case ']':
        case '}':
        case '{':
            return keyResult(opening + closing, textIndex, textIndex + 1, textIndex + 1);
        case '/':
            switch (text[textIndex + 2]) {
                case '/':
                case '*':
                    return keyResult(opening + closing, textIndex, textIndex + 1, textIndex + 1);
            }
    }
    return keyResult(opening, textIndex, textIndex + 1, textIndex + 1);
}

function prevNode(node: Node): Node | null {
    for (let i = node.startIndex - 1; i >= 0; i--) {
        const n = node.tree.rootNode.descendantForIndex(i);
        if (n && n.childCount === 0) return n;
    }
    return null;
}

function hasAncestor(node: Node | null, types: Set<string>): boolean {
    for (let current: Node | null = node; current !== null; current = current.parent) {
        if (types.has(current.type)) {
            return true;
        }
    }
    return false;
}

function isTextNode(node: Node | null): boolean {
    return node !== null && TEXT_NODE_TYPES.has(node.type);
}

function findAncestor(node: Node | null, type: string): Node | null {
    for (let current: Node | null = node; current !== null; current = current.parent) {
        if (current.type === type) {
            return current;
        }
    }
    return null;
}

function findChild(node: Node, type: string): Node | null {
    for (let i = 0; i < node.childCount; i++) {
        const child = node.child(i);
        if (child?.type === type) {
            return child;
        }
    }
    return null;
}

function findNamedChildInRange(node: Node, startIndex: number, endIndex: number): Node | null {
    for (let i = 0; i < node.namedChildCount; i++) {
        const child = node.namedChild(i);
        if (child !== null && child.startIndex >= startIndex && child.endIndex <= endIndex) {
            return child;
        }
    }
    return null;
}

function getLineStart(text: string, index: number): number {
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

function getLineEnd(text: string, index: number): number {
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

function getIndent(text: string, lineStart: number): string {
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

function getCurrentLineTail(text: string, index: number): string {
    return text.slice(index, getLineEnd(text, index));
}

function hasLineWithPrefix(text: string, start: number, end: number, prefix: string): boolean {
    let lineStart = getLineStart(text, start);
    while (lineStart < end) {
        const lineEnd = getLineEnd(text, lineStart);
        const contentStart = skipIndent(text, lineStart, lineEnd);
        if (contentStart < lineEnd && text.startsWith(prefix, contentStart)) {
            return true;
        }
        if (lineEnd >= end) {
            return false;
        }
        lineStart = lineEnd + 1;
        if (text[lineEnd] === '\r' && text[lineStart] === '\n') {
            lineStart++;
        }
    }
    return false;
}

function skipIndent(text: string, start: number, end: number): number {
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

interface BlockCommentContext {
    start: number
    end: number | null
    isDoc: boolean
}

function handleLineCommentEnter(text: string, node: Node, index: number): KeyResult | null {
    const lineComment = findLineCommentAtEnter(node, index);
    if (lineComment === null) {
        return null;
    }

    const currentLineTail = getCurrentLineTail(text, index + 1);
    if (currentLineTail.trim().length === 0) {
        return keyResult('\n', index, index + 1, index + 1);
    }

    const prefix = getLineCommentPrefix(text, lineComment);
    const replacement = `\n${prefix}`;
    return keyResult(replacement, index, index + 1, index + replacement.length);
}

function handleStringLiteralEnter(
        text: string,
        node: Node,
        index: number,
        indentUnit: string,
): KeyResult | null {
    const multilineStringLiteral = findAncestorAtEnter(node, index, 'multiline_string_literal');
    if (multilineStringLiteral !== null && text.startsWith('"""', multilineStringLiteral.startIndex)) {
        return handleMultilineStringLiteralEnter(text, multilineStringLiteral, index, indentUnit);
    }

    const stringLiteral = findAncestorAtEnter(node, index, 'string_literal');
    if (stringLiteral === null || index >= stringLiteral.endIndex - 1) {
        return null;
    }

    const continuationIndent = `${getIndent(text, getLineStart(text, stringLiteral.startIndex))}${indentUnit}${indentUnit}`;
    const wrapWithParens = shouldWrapQualifiedStringReceiver(stringLiteral);
    const wrapPrefix = wrapWithParens ? '(' : '';
    const wrapSuffix = wrapWithParens ? ')' : '';
    const before = text.slice(stringLiteral.startIndex + 1, index);
    const after = text.slice(index + 1, stringLiteral.endIndex - 1);
    const replacement = `${wrapPrefix}"${before}" + \n${continuationIndent}"${after}"${wrapSuffix}`;
    const offset = `${wrapPrefix}"${before}" + \n${continuationIndent}`.length;
    return keyResult(replacement, stringLiteral.startIndex, stringLiteral.endIndex, stringLiteral.startIndex + offset);
}

function rewriteCommentLineTail(
        commentContext: BlockCommentContext,
        commentIndent: string,
        currentLineTail: string,
        prefixLine: string,
        index: number,
        useLinePrefix: boolean,
): KeyResult | null {
    if (currentLineTail.length === 0) {
        return null;
    }

    const trimmedTail = currentLineTail.trimStart();
    if (trimmedTail.length === 0) {
        if (commentContext.end === null) {
            return keyResult(`${prefixLine}\n${commentIndent} */`, index, index + 1 + currentLineTail.length, index + prefixLine.length);
        }
        return useLinePrefix ? keyResult(prefixLine, index, index + 1 + currentLineTail.length, index + prefixLine.length) : null;
    }

    if (!useLinePrefix) {
        return null;
    }

    if (trimmedTail.startsWith('*/')) {
        return keyResult(`${prefixLine}\n${commentIndent} */`, index, index + 1 + currentLineTail.length, index + prefixLine.length);
    }

    if (commentContext.end === null) {
        return keyResult(`${prefixLine}\n${commentIndent} */\n${currentLineTail}`, index, index + 1 + currentLineTail.length, index + prefixLine.length);
    }

    const shiftedLine = getCommentBodyLine(currentLineTail, commentIndent);
    return keyResult(`${prefixLine}\n${shiftedLine}`, index, index + 1 + currentLineTail.length, index + prefixLine.length);
}

function getCommentBodyLine(currentLineTail: string, commentIndent: string): string {
    let body = currentLineTail.trimStart();
    if (body.startsWith('*')) {
        body = body.slice(1);
        if (body.startsWith(' ')) {
            body = body.slice(1);
        }
    }

    return body.length === 0 ? `${commentIndent} * ` : `${commentIndent} * ${body}`;
}

function findLineCommentAtEnter(node: Node, index: number): Node | null {
    const lineComment = findAncestor(node, 'line_comment');
    if (lineComment !== null) {
        return lineComment;
    }

    const previousIndex = index - 1;
    if (previousIndex < 0) {
        return null;
    }

    const previousNode = node.tree.rootNode.descendantForIndex(previousIndex);
    const previousLineComment = findAncestor(previousNode, 'line_comment');
    return previousLineComment?.endIndex === index ? previousLineComment : null;
}

function handleMultilineStringLiteralEnter(
        text: string,
        multilineStringLiteral: Node,
        index: number,
        indentUnit: string,
): KeyResult | null {
    const contentEnd = multilineStringLiteral.endIndex - MULTILINE_QUOTE.length;
    if (index > contentEnd) {
        return null;
    }

    const baseIndent = getIndent(text, getLineStart(text, multilineStringLiteral.startIndex));
    const trimCallInfo = getTrimCallInfo(text, multilineStringLiteral);
    const marginChar = trimCallInfo.kind === 'trimIndent'
            ? null
            : trimCallInfo.marginChar ?? getMarginCharFromLiteral(text, multilineStringLiteral);
    const tail = text.slice(index + 1, contentEnd);
    const whitespaceAfterCaret = tail.match(/^[ \t]*/)?.[0] ?? '';
    const tailAfterCaret = tail.slice(whitespaceAfterCaret.length);
    const originalLiteralText = text.slice(multilineStringLiteral.startIndex, index) + text.slice(index + 1, multilineStringLiteral.endIndex);
    if (!originalLiteralText.includes('\n')) {
        const shouldUseTrimIndent = trimCallInfo.kind === 'trimIndent' ||
                (marginChar === null && isStartOfMultilineString(text, multilineStringLiteral));
        const linePrefix = getMultilineStringLinePrefix(baseIndent, indentUnit, shouldUseTrimIndent ? null : marginChar ?? DEFAULT_TRIM_MARGIN_CHAR);
        const trimCall = getInsertedTrimCall(text, multilineStringLiteral, trimCallInfo.kind !== null, shouldUseTrimIndent ? null : marginChar ?? DEFAULT_TRIM_MARGIN_CHAR);
        const replacement = tail.length === 0
                ? `\n${linePrefix}\n${baseIndent}${MULTILINE_QUOTE}${trimCall}`
                : `\n${linePrefix}${tail}${MULTILINE_QUOTE}${trimCall}`;
        return keyResult(replacement, index, multilineStringLiteral.endIndex, index + `\n${linePrefix}`.length);
    }

    const previousLineStart = getLineStart(text, index);
    const linePrefix = getExistingMultilineStringLinePrefix(text, multilineStringLiteral, previousLineStart, indentUnit, marginChar);
    if (isBraceEnter(text[index - 1], tailAfterCaret[0])) {
        return keyResult(`\n${linePrefix}${whitespaceAfterCaret}\n${linePrefix}${tailAfterCaret}`, index, contentEnd, index + `\n${linePrefix}${whitespaceAfterCaret}`.length);
    }

    return keyResult(`\n${linePrefix}`, index, index + 1, index + `\n${linePrefix}`.length);
}

function findAncestorAtEnter(node: Node, index: number, type: string): Node | null {
    const currentAncestor = findAncestor(node, type);
    if (currentAncestor !== null) {
        return currentAncestor;
    }

    if (index === 0) {
        return null;
    }
    return findAncestor(node.tree.rootNode.descendantForIndex(index - 1), type);
}

function shouldWrapQualifiedStringReceiver(stringLiteral: Node): boolean {
    let expression: Node = stringLiteral;
    for (let current: Node | null = stringLiteral.parent; current !== null; current = current.parent) {
        switch (current.type) {
            case 'parenthesized_expression':
                return false;
            case 'navigation_expression':
                return current.namedChild(0)?.startIndex === expression.startIndex &&
                        current.namedChild(0)?.endIndex === expression.endIndex;
            default:
                if (current.type.endsWith('_expression')) {
                    expression = current;
                }
                break;
        }
    }
    return false;
}

function getTrimCallInfo(
        text: string,
        multilineStringLiteral: Node,
): { kind: 'trimIndent', marginChar: null } | { kind: 'trimMargin', marginChar: string } | { kind: null, marginChar: null } {
    for (const callExpression of getLiteralCallChain(multilineStringLiteral)) {
        switch (getCallName(text, callExpression)) {
            case 'trimIndent':
                return {kind: 'trimIndent', marginChar: null};
            case 'trimMargin':
                return {kind: 'trimMargin', marginChar: getTrimMarginMarginChar(text, callExpression)};
        }
    }
    return {kind: null, marginChar: null};
}

function getLiteralCallChain(multilineStringLiteral: Node): Node[] {
    const calls: Node[] = [];
    let current: Node = multilineStringLiteral;
    while (true) {
        const navigationExpression = current.parent;
        if (navigationExpression?.type !== 'navigation_expression' ||
                navigationExpression.namedChild(0)?.startIndex !== current.startIndex ||
                navigationExpression.namedChild(0)?.endIndex !== current.endIndex) {
            return calls;
        }

        const callExpression = navigationExpression.parent;
        if (callExpression?.type !== 'call_expression' ||
                callExpression.namedChild(0)?.startIndex !== navigationExpression.startIndex ||
                callExpression.namedChild(0)?.endIndex !== navigationExpression.endIndex) {
            return calls;
        }

        calls.push(callExpression);
        current = callExpression;
    }
}

function getCallName(text: string, callExpression: Node): string | null {
    const navigationExpression = callExpression.namedChild(0);
    const identifier = navigationExpression?.type === 'navigation_expression' ? navigationExpression.namedChild(1) : null;
    return identifier === null ? null : text.slice(identifier.startIndex, identifier.endIndex);
}

function getTrimMarginMarginChar(text: string, callExpression: Node): string {
    const valueArguments = callExpression.namedChild(1);
    const valueArgument = valueArguments?.type === 'value_arguments' ? valueArguments.namedChild(0) : null;
    const argumentExpression = valueArgument?.namedChild(0);
    const stringContent = argumentExpression?.type === 'string_literal' ? argumentExpression.namedChild(0) : null;
    return stringContent?.type === 'string_content' && stringContent.endIndex - stringContent.startIndex === 1
            ? text[stringContent.startIndex]
            : DEFAULT_TRIM_MARGIN_CHAR;
}

function getMarginCharFromLiteral(text: string, multilineStringLiteral: Node): string | null {
    const lines = text.slice(multilineStringLiteral.startIndex, multilineStringLiteral.endIndex).split(/\r?\n/);
    if (lines.length <= 2) {
        return null;
    }

    const middleNonBlankLines = lines.slice(1, -1).filter((line) => line.trim().length !== 0);
    return middleNonBlankLines.length !== 0 && middleNonBlankLines.every((line) => line.trimStart().startsWith(DEFAULT_TRIM_MARGIN_CHAR))
            ? DEFAULT_TRIM_MARGIN_CHAR
            : null;
}

function isStartOfMultilineString(text: string, multilineStringLiteral: Node): boolean {
    const firstLine = text.slice(multilineStringLiteral.startIndex, Math.min(getLineEnd(text, multilineStringLiteral.startIndex), multilineStringLiteral.endIndex));
    return firstLine.trim().replace(/^\$+/, '') === MULTILINE_QUOTE;
}

function getInsertedTrimCall(text: string, multilineStringLiteral: Node, hasTrimCall: boolean, marginChar: string | null): string {
    if (hasTrimCall || isTrimCallSuppressed(text, multilineStringLiteral)) {
        return '';
    }
    if (marginChar === null) {
        return '.trimIndent()';
    }
    return marginChar === DEFAULT_TRIM_MARGIN_CHAR ? '.trimMargin()' : `.trimMargin("${marginChar}")`;
}

function isTrimCallSuppressed(text: string, multilineStringLiteral: Node): boolean {
    for (let current: Node | null = multilineStringLiteral.parent; current !== null; current = current.parent) {
        if (current.type === 'annotation') {
            return true;
        }
        if (current.type === 'property_declaration' && hasConstModifier(text, current)) {
            return true;
        }
    }
    return false;
}

function hasConstModifier(text: string, propertyDeclaration: Node): boolean {
    const modifiers = findChild(propertyDeclaration, 'modifiers');
    return modifiers !== null && text.slice(modifiers.startIndex, modifiers.endIndex).includes('const');
}

function getExistingMultilineStringLinePrefix(
        text: string,
        multilineStringLiteral: Node,
        previousLineStart: number,
        indentUnit: string,
        marginChar: string | null,
): string {
    const literalLineStart = getLineStart(text, multilineStringLiteral.startIndex);
    const baseIndent = getIndent(text, literalLineStart);
    if (previousLineStart === literalLineStart) {
        return getMultilineStringLinePrefix(baseIndent, indentUnit, getMarginCharToInsert(text, multilineStringLiteral, text.slice(previousLineStart, getLineEnd(text, previousLineStart)), marginChar));
    }

    const previousLine = text.slice(previousLineStart, getLineEnd(text, previousLineStart));
    const indent = getIndent(text, previousLineStart);
    const insertedMarginChar = getMarginCharToInsert(text, multilineStringLiteral, previousLine, marginChar);
    if (insertedMarginChar === null) {
        return indent;
    }

    const trimmedLine = previousLine.slice(indent.length);
    const whitespaceAfterMargin = trimmedLine.startsWith(insertedMarginChar)
            ? trimmedLine.slice(insertedMarginChar.length).match(/^[ \t]*/)?.[0] ?? ''
            : '';
    return `${indent}${insertedMarginChar}${whitespaceAfterMargin}`;
}

function getMarginCharToInsert(text: string, multilineStringLiteral: Node, previousLine: string, marginChar: string | null): string | null {
    if (marginChar === null) {
        return null;
    }

    const prefixStripped = previousLine.trimStart();
    const lines = text.slice(multilineStringLiteral.startIndex, multilineStringLiteral.endIndex).split(/\r?\n/);
    const nonBlankNotFirstLines = lines.slice(1).filter((line) => {
        const trimmed = line.trim();
        return trimmed.length !== 0 && trimmed !== MULTILINE_QUOTE;
    });
    return !prefixStripped.startsWith(marginChar) &&
            nonBlankNotFirstLines.length !== 0 &&
            nonBlankNotFirstLines.every((line) => !line.trimStart().startsWith(marginChar))
            ? null
            : marginChar;
}

function getMultilineStringLinePrefix(baseIndent: string, indentUnit: string, marginChar: string | null): string {
    return `${baseIndent}${indentUnit}${marginChar ?? ''}`;
}

function isBraceEnter(charBefore: string | undefined, charAfter: string | undefined): boolean {
    return (charBefore === '{' && charAfter === '}') ||
            (charBefore === '(' && charAfter === ')') ||
            (charBefore === '>' && charAfter === '<');
}

function getLineCommentPrefix(text: string, lineComment: Node): string {
    const lineStart = getLineStart(text, lineComment.startIndex);
    const indent = text.slice(lineStart, lineComment.startIndex);
    const leadingSpaces = lineComment.text.slice(2).match(/^\s*/)?.[0] ?? '';
    return `${indent}//${leadingSpaces}`;
}

function findBlockCommentContext(
        node: Node,
        text: string,
        index: number,
): BlockCommentContext | null {
    const blockComment = findAncestor(node, 'block_comment');
    if (blockComment !== null) {
        return {
            start: blockComment.startIndex,
            end: blockComment.endIndex,
            isDoc: blockComment.text.startsWith('/**'),
        };
    }

    const errorNode = findEnclosingErrorNode(node, index);
    if (errorNode === null) {
        return null;
    }

    return findUnterminatedBlockCommentContext(errorNode, text, index);
}

function findEnclosingErrorNode(node: Node, index: number): Node | null {
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

function findUnterminatedBlockCommentContext(
        errorNode: Node,
        text: string,
        index: number,
): BlockCommentContext | null {
    const errorStartIndex = errorNode.startIndex;
    const localLimit = index - errorStartIndex;
    if (localLimit < 2) {
        return null;
    }

    // In incomplete surrounding constructs, tree-sitter-kotlin may report only the
    // token that first broke recovery (for example a class body's `{`) as ERROR and
    // drop the following unfinished comment from the tree entirely.
    const errorText = text.slice(errorStartIndex, index);
    const stack: Array<{ start: number, isDoc: boolean }> = [];

    // tree-sitter-kotlin represents unfinished block comments as a single ERROR
    // leaf, so inspect only that local slice instead of rescanning the document.
    for (let i = 0; i < localLimit; i++) {
        if (errorText.startsWith('/*', i)) {
            stack.push({
                start: i,
                isDoc: errorText.startsWith('/**', i),
            });
            i += errorText.startsWith('/**', i) ? 2 : 1;
            continue;
        }
        if (stack.length > 0 && errorText.startsWith('*/', i)) {
            stack.pop();
            i++;
        }
    }

    const openComment = stack.at(-1);
    if (openComment === undefined) {
        return null;
    }

    return {
        start: errorStartIndex + openComment.start,
        end: null,
        isDoc: openComment.isDoc,
    };
}
