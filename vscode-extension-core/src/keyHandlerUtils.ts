import type { Node, Tree } from 'web-tree-sitter';

export interface KeyResult {
  text: string;
  startOffset: number;
  endOffset: number;
  caretOffset: number;
}

export type KeyHandler = (
  text: string,
  tree: Tree,
  key: string,
  index: number,
  indentUnit: string,
) => KeyResult;

export interface BlockCommentContext {
  start: number;
  end: number | null;
  isDoc: boolean;
}

export interface PairedDelimiterContext {
  opening: string;
  openIndex: number;
  between: string;
}

export interface OpeningDelimiterOptions {
  skipPairingWhenNextCharEqualsClosing?: boolean;
  extraAutoCloseChars?: Iterable<string>;
  commentPrefixes?: string[];
}

interface EmptyPairedDelimiterOptions {
  shouldInsertClosingLine?: (context: PairedDelimiterContext) => boolean;
}

type SpecialNodeKeyHandler = (
  node: Node,
  text: string,
  key: string,
  index: number,
) => KeyResult | null;
type EnterResultHandler = (node: Node) => KeyResult | null;

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

export function keyResult(
  text: string,
  startOffset: number,
  endOffset: number,
  caretOffset: number,
): KeyResult {
  return {
    text,
    startOffset,
    endOffset,
    caretOffset,
  };
}

export function withSpecialNodeResult(
  tree: Tree,
  key: string,
  index: number,
  specialNodeHandler: (node: Node) => KeyResult | null,
  fallback: (node: Node) => KeyResult,
): KeyResult {
  const node = tree.rootNode.descendantForIndex(index);
  if (node === null) {
    return keyResult(key, index, index + 1, index + key.length);
  }
  return specialNodeHandler(node) ?? fallback(node);
}

export function handleKeyWithSpecialNode(
  text: string,
  tree: Tree,
  key: string,
  index: number,
  specialNodeHandler: SpecialNodeKeyHandler,
  fallback: (node: Node) => KeyResult,
): KeyResult {
  return withSpecialNodeResult(
    tree,
    key,
    index,
    (node) => specialNodeHandler(node, text, key, index),
    fallback,
  );
}

export function handleOpeningDelimiterKey(
  text: string,
  tree: Tree,
  index: number,
  opening: string,
  closing: string,
  specialNodeHandler: SpecialNodeKeyHandler,
  options: OpeningDelimiterOptions = {},
): KeyResult {
  return handleKeyWithSpecialNode(text, tree, opening, index, specialNodeHandler, () =>
    getOpeningDelimiterResult(text, index, opening, closing, options),
  );
}

export function handleStandardClosingDelimiterKey(
  text: string,
  tree: Tree,
  key: string,
  index: number,
  specialNodeHandler: SpecialNodeKeyHandler,
  ignoredAncestorTypes: Set<string>,
): KeyResult {
  return handleKeyWithSpecialNode(text, tree, key, index, specialNodeHandler, (node) => {
    return shouldSkipClosingDelimiter(text, node, key, index, ignoredAncestorTypes)
      ? keyResult('', index, index + 1, index + 1)
      : keyResult(key, index, index + 1, index + 1);
  });
}

export function handlePairedClosingDelimiterKey(
  text: string,
  tree: Tree,
  key: string,
  index: number,
  opening: string,
  specialNodeHandler: SpecialNodeKeyHandler,
): KeyResult {
  return handleKeyWithSpecialNode(text, tree, key, index, specialNodeHandler, (node) => {
    return shouldSkipPairedClosingDelimiter(node, text, key, index, opening)
      ? keyResult('', index, index + 1, index + 1)
      : keyResult(key, index, index + 1, index + 1);
  });
}

export function getOpeningDelimiterResult(
  text: string,
  index: number,
  opening: string,
  closing: string,
  options: OpeningDelimiterOptions = {},
): KeyResult {
  const nextChar = text[index + 1];
  if (options.skipPairingWhenNextCharEqualsClosing && nextChar === closing) {
    return keyResult(opening, index, index + 1, index + 1);
  }
  if (shouldAutoCloseOpeningDelimiter(text, index, nextChar, options)) {
    return keyResult(`${opening}${closing}`, index, index + 1, index + 1);
  }
  return keyResult(opening, index, index + 1, index + 1);
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

export function getPreviousLeaf(node: Node | null): Node | null {
  for (let current: Node | null = node; current !== null; current = current.parent) {
    const previousSibling = current.previousSibling;
    if (previousSibling !== null) {
      return getLastLeaf(previousSibling);
    }
  }
  return null;
}

export function getNextLeaf(node: Node | null): Node | null {
  for (let current: Node | null = node; current !== null; current = current.parent) {
    const nextSibling = current.nextSibling;
    if (nextSibling !== null) {
      return getFirstLeaf(nextSibling);
    }
  }
  return null;
}

export function countTokenBalance(node: Node, opening: string, closing: string): number {
  let balance = 0;
  for (
    let current: Node | null = getFirstLeaf(node);
    current !== null && current.startIndex < node.endIndex;
    current = getNextLeaf(current)
  ) {
    switch (current.type) {
      case opening:
        balance++;
        break;
      case closing:
        balance--;
        break;
      default:
        break;
    }
  }
  return balance;
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

  return (
    findAncestor(node.tree.rootNode.namedDescendantForIndex(previousIndex), 'ERROR') ??
    findLastErrorBeforeIndex(node.tree.rootNode, previousIndex)
  );
}

export function getLineStart(text: string, index: number): number {
  let current = index;
  if (
    current > 0 &&
    current < text.length &&
    text[current] === '\n' &&
    text[current - 1] === '\r'
  ) {
    current--;
  }
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

export function handleEnterWithHandlers(
  text: string,
  tree: Tree,
  index: number,
  indentUnit: string,
  handlers: ReadonlyArray<EnterResultHandler>,
  fallback: (node: Node) => KeyResult,
  missingNodeFallback?: () => KeyResult,
): KeyResult {
  const node = tree.rootNode.descendantForIndex(index);
  if (node === null) {
    return missingNodeFallback?.() ?? handleRegularEnter(text, index, indentUnit);
  }

  for (const handler of handlers) {
    const result = handler(node);
    if (result !== null) {
      return result;
    }
  }
  return fallback(node);
}

export function handleRegularEnter(
  text: string,
  index: number,
  indentUnit: string,
  continuationIndentOverride?: string | null,
): KeyResult {
  const previousLineIndent = getPreviousNonEmptyLineIndent(text, index);
  const continuationIndent =
    continuationIndentOverride ??
    (previousLineIndent === null ? null : `${previousLineIndent}${indentUnit}`);
  return getRegularEnterResult(text, index, previousLineIndent, continuationIndent);
}

export function getRegularEnterResult(
  text: string,
  index: number,
  previousLineIndent: string | null,
  continuationIndent: string | null,
): KeyResult {
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

  const indent =
    shouldUseContinuationIndent && continuationIndent !== null
      ? continuationIndent
      : previousLineIndent;
  const replacement = `\n${indent}`;
  return keyResult(replacement, index, index + 1, index + replacement.length);
}

export function getAlignedAncestorListContinuationIndent(
  text: string,
  node: Node | null,
  index: number,
  ancestorTypes: Iterable<string>,
): string | null {
  if (node === null || getPreviousSignificantChar(text, index) !== ',') {
    return null;
  }

  for (const ancestorType of ancestorTypes) {
    const listNode = findAncestorAtEnter(node, index, ancestorType);
    if (listNode !== null) {
      return getAlignedListIndent(text, listNode, index);
    }
  }
  return null;
}

export function getAlignedListIndent(text: string, listNode: Node, index: number): string | null {
  const lineStart = getLineStart(text, listNode.startIndex);
  const lineEnd = getLineEnd(text, listNode.startIndex);
  const firstItemStart = skipIndent(text, listNode.startIndex + 1, lineEnd);
  if (firstItemStart >= lineEnd || firstItemStart >= index) {
    return null;
  }

  const baseIndent = getIndent(text, lineStart);
  const alignmentWidth = firstItemStart - lineStart - baseIndent.length;
  return alignmentWidth <= 0 ? baseIndent : `${baseIndent}${' '.repeat(alignmentWidth)}`;
}

export function getMultilineListItemIndent(
  text: string,
  listNode: Node,
  index: number,
): string | null {
  const firstItem = listNode.namedChild(0);
  if (firstItem === null || firstItem.startIndex >= index) {
    return null;
  }

  const listLineStart = getLineStart(text, listNode.startIndex);
  const firstItemLineStart = getLineStart(text, firstItem.startIndex);
  return firstItemLineStart === listLineStart
    ? getAlignedListIndent(text, listNode, index)
    : getIndent(text, firstItemLineStart);
}

export function getExistingMultilineItemIndent(
  text: string,
  containerStartIndex: number,
  firstItem: Node | null,
  index: number,
): string | null {
  if (firstItem === null || firstItem.startIndex >= index) {
    return null;
  }

  const currentLineTail = text.slice(index + 1, getLineEnd(text, index + 1));
  if (currentLineTail.length === 0 || currentLineTail.trim().length !== 0) {
    return null;
  }

  const containerLineStart = getLineStart(text, containerStartIndex);
  const firstItemLineStart = getLineStart(text, firstItem.startIndex);
  return firstItemLineStart === containerLineStart ? null : getIndent(text, firstItemLineStart);
}

export function getExistingMultilineListItemIndent(
  text: string,
  listNode: Node,
  index: number,
): string | null {
  return getExistingMultilineItemIndent(text, listNode.startIndex, listNode.namedChild(0), index);
}

export function keyResultWithOptionalBlockIndent(
  previousLineIndent: string | null,
  index: number,
): KeyResult {
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

export function getCommaSeparatedBodyContinuationIndent(
  text: string,
  node: Node | null,
  index: number,
  ancestorTypes: readonly string[],
): string | null {
  if (node === null || getPreviousSignificantChar(text, index) !== ',') {
    return null;
  }

  return ancestorTypes.some(
    (ancestorType) => findAncestorAtEnter(node, index, ancestorType) !== null,
  )
    ? getPreviousNonEmptyLineIndent(text, index)
    : null;
}

export function getPreviousNonEmptyLine(
  text: string,
  index: number,
): { start: number; end: number; text: string } | null {
  let lineEnd = index;
  while (lineEnd > 0) {
    const lineStart = getLineStart(text, lineEnd - 1);
    const line = text.slice(lineStart, lineEnd);
    if (line.trim().length !== 0) {
      return { start: lineStart, end: lineEnd, text: line };
    }
    lineEnd = lineStart;
  }
  return null;
}

export function getLeadingNavigationContinuationIndent(
  text: string,
  index: number,
  continuationIndent: string,
  prefixes: ReadonlyArray<string>,
): string {
  const previousLine = getPreviousNonEmptyLine(text, index);
  if (previousLine === null) {
    return continuationIndent;
  }

  const trimmedPreviousLine = previousLine.text.trimStart();
  return prefixes.some((prefix) => trimmedPreviousLine.startsWith(prefix))
    ? getIndent(text, previousLine.start)
    : continuationIndent;
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
  options: EmptyPairedDelimiterOptions = {},
): KeyResult | null {
  const context = findEnclosingPairedDelimiter(node, text, index);
  if (context === null) {
    return null;
  }

  const baseIndent = getIndent(text, getLineStart(text, context.openIndex));
  const bodyIndent = `${baseIndent}${indentUnit}`;
  if (/^[ \t]*\r?\n[ \t]*$/.test(context.between)) {
    const shouldInsertClosingLine = options.shouldInsertClosingLine?.(context) ?? true;
    const replacement = shouldInsertClosingLine
      ? `\n${bodyIndent}\n${baseIndent}`
      : `\n${bodyIndent}`;
    return keyResult(replacement, index, index + 1, index + `\n${bodyIndent}`.length);
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
  return start === null ? null : { start, end: null, isDoc: text.startsWith('/**', start) };
}

export function handleSimpleBlockCommentEnter(
  text: string,
  context: BlockCommentContext,
  index: number,
  shouldIgnoreLine: (trimmedLine: string) => boolean,
): KeyResult {
  if (context.end !== null) {
    const currentLineTail = text.slice(index + 1, getLineEnd(text, index + 1));
    if (currentLineTail.trim().length === 0) {
      const bodyIndent = getClosedNodeBodyIndent(
        text,
        context.start,
        context.end,
        index,
        shouldIgnoreLine,
      );
      if (bodyIndent !== null) {
        const replacement = `\n${bodyIndent}`;
        return keyResult(
          replacement,
          index,
          index + 1 + currentLineTail.length,
          index + replacement.length,
        );
      }
    }
    return keyResult('\n', index, index + 1, index + 1);
  }

  const commentIndent = getIndent(text, getLineStart(text, context.start));
  const prefixLine = `\n${commentIndent} `;
  return keyResult(
    `${prefixLine}\n${commentIndent} */`,
    index,
    index + 1,
    index + prefixLine.length,
  );
}

export function handleSimpleBlockCommentOrRegularEnter(
  text: string,
  node: Node,
  index: number,
  indentUnit: string,
  ancestorType: string,
  shouldIgnoreLine: (trimmedLine: string) => boolean,
  continuationIndent?: string | null,
): KeyResult {
  const context = findBlockCommentContext(node, text, index, ancestorType);
  if (context === null) {
    return handleRegularEnter(text, index, indentUnit, continuationIndent);
  }
  return handleSimpleBlockCommentEnter(text, context, index, shouldIgnoreLine);
}

export function handleLineCommentEnter(
  text: string,
  node: Node,
  index: number,
  ancestorType: string,
  marker: string,
  continueOnBlankLine: boolean = false,
): KeyResult | null {
  const lineComment = findLineCommentAtEnter(text, node, index, ancestorType, marker);
  if (lineComment === null) {
    return null;
  }

  let lineEnd = index + 1;
  while (lineEnd < text.length && text[lineEnd] !== '\n' && text[lineEnd] !== '\r') {
    lineEnd++;
  }
  if (!continueOnBlankLine && text.slice(index + 1, lineEnd).trim().length === 0) {
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

export function getLineBreakAtIndex(
  text: string,
  index: number,
): { startOffset: number; endOffset: number; text: string } {
  if (text[index] === '\n' && index > 0 && text[index - 1] === '\r') {
    return {
      startOffset: index - 1,
      endOffset: index + 1,
      text: '\r\n',
    };
  }
  if (text[index] === '\r' && text[index + 1] === '\n') {
    return {
      startOffset: index,
      endOffset: index + 2,
      text: '\r\n',
    };
  }
  return {
    startOffset: index,
    endOffset: index + 1,
    text: text[index],
  };
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

  const lineBreak = getLineBreakAtIndex(text, index);
  const continuationIndent = `${getIndent(text, getLineStart(text, stringLiteral.startIndex))}${indentUnit}${indentUnit}`;
  const replacement = `${delimiter} ${concatenationOperator} ${lineBreak.text}${continuationIndent}${delimiter}`;
  return keyResult(
    replacement,
    lineBreak.startOffset,
    lineBreak.endOffset,
    lineBreak.startOffset + replacement.length - delimiter.length,
  );
}

export function handleMultilineClosedNodeEnter(
  text: string,
  node: Node,
  index: number,
  ancestorType: string,
  shouldHandleNode: (targetNode: Node) => boolean,
  shouldIgnoreLine: (trimmedLine: string) => boolean,
): KeyResult | null {
  const targetNode = findAncestorAtEnter(node, index, ancestorType);
  if (targetNode === null || !shouldHandleNode(targetNode)) {
    return null;
  }
  const hasOtherLineBreak =
    text.slice(targetNode.startIndex, index).includes('\n') ||
    text.slice(index + 1, targetNode.endIndex).includes('\n');
  if (!hasOtherLineBreak) {
    return null;
  }

  const bodyIndent = getClosedNodeBodyIndent(
    text,
    targetNode.startIndex,
    targetNode.endIndex,
    index,
    shouldIgnoreLine,
  );
  if (bodyIndent === null) {
    return null;
  }

  const replacement = `\n${bodyIndent}`;
  return keyResult(replacement, index, index + 1, index + replacement.length);
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
  for (let currentNode: Node | null = getPreviousLeaf(node); currentNode !== null; ) {
    const ignoredAncestor = findAncestorInSet(currentNode, ignoredAncestorTypes);
    if (ignoredAncestor !== null) {
      currentNode = getPreviousLeaf(ignoredAncestor);
      continue;
    }

    if (OPENING_BY_CLOSING.has(currentNode.type)) {
      stack.push(currentNode.type);
      currentNode = getPreviousLeaf(currentNode);
      continue;
    }

    const closing = CLOSING_BY_OPENING.get(currentNode.type);
    if (closing === undefined) {
      currentNode = getPreviousLeaf(currentNode);
      continue;
    }
    if (stack.at(-1) !== closing) {
      return false;
    }

    stack.pop();
    if (stack.length === 0) {
      return currentNode.type === expectedOpening;
    }
    currentNode = getPreviousLeaf(currentNode);
  }

  return false;
}

function isAfterStandaloneBlockCommentLine(text: string, index: number): boolean {
  const previousLine = getPreviousNonEmptyLine(text, index)?.text;
  return previousLine !== undefined && isStandaloneBlockCommentLine(previousLine);
}

function shouldAutoCloseOpeningDelimiter(
  text: string,
  index: number,
  nextChar: string | undefined,
  options: OpeningDelimiterOptions,
): boolean {
  if (
    nextChar === undefined ||
    nextChar === ' ' ||
    nextChar === '\t' ||
    nextChar === '\n' ||
    nextChar === '\r'
  ) {
    return true;
  }
  if (
    nextChar === ',' ||
    nextChar === ';' ||
    nextChar === ')' ||
    nextChar === ']' ||
    nextChar === '}' ||
    nextChar === '{'
  ) {
    return true;
  }
  for (const char of options.extraAutoCloseChars ?? []) {
    if (nextChar === char) {
      return true;
    }
  }
  return (options.commentPrefixes ?? []).some((prefix) => text.startsWith(prefix, index + 1));
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

function getLastLeaf(node: Node): Node {
  let current = node;
  while (current.lastChild !== null) {
    current = current.lastChild;
  }
  return current;
}

function getFirstLeaf(node: Node): Node {
  let current = node;
  while (current.firstChild !== null) {
    current = current.firstChild;
  }
  return current;
}

export function findEnclosingPairedDelimiter(
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

    const opening = text[startIndex];
    if (!isMatchingPair(opening, text[endIndex - 1])) {
      continue;
    }

    const between = text.slice(startIndex + 1, endIndex - 1);
    if (!/^[ \t\r\n]*$/.test(between)) {
      continue;
    }

    return { opening, openIndex: startIndex, between };
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
  return OPENING_BY_CLOSING.get(closing) === opening;
}

function shouldSkipPairedClosingDelimiter(
  node: Node,
  text: string,
  key: string,
  index: number,
  opening: string,
): boolean {
  if (text[index + 1] !== key) {
    return false;
  }

  const stack: string[] = [key];
  for (
    let currentNode: Node | null = getPreviousLeaf(node);
    currentNode !== null;
    currentNode = getPreviousLeaf(currentNode)
  ) {
    if (currentNode.type === key) {
      stack.push(key);
      continue;
    }
    if (currentNode.type !== opening) {
      continue;
    }

    stack.pop();
    if (stack.length === 0) {
      return true;
    }
  }
  return false;
}

function findUnterminatedBlockCommentStart(
  errorNode: Node,
  text: string,
  index: number,
): number | null {
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
