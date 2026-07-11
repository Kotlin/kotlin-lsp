import { Tree, Node } from 'web-tree-sitter';
import {
  type BlockCommentContext,
  type KeyResult,
  findAncestor,
  findAncestorAtEnter,
  findEnclosingErrorNode,
  getAlignedListIndent,
  getAlignedAncestorListContinuationIndent,
  getCommaSeparatedBodyContinuationIndent,
  getExistingMultilineListItemIndent,
  getIndent,
  getLeadingNavigationContinuationIndent,
  getLineBreakAtIndex,
  getLineEnd,
  getLineStart,
  getMultilineListItemIndent,
  getNextLineStart,
  getOpeningDelimiterResult,
  getPreviousLeaf,
  getPreviousNonEmptyLine,
  getPreviousNonEmptyLineIndent,
  getPreviousSignificantChar,
  getRegularEnterResult,
  hasAncestor,
  handleEnterWithHandlers,
  handleKeyWithSpecialNode,
  handleLineCommentEnter,
  handleOpeningDelimiterKey,
  handlePairedClosingDelimiterKey,
  handleStandardClosingDelimiterKey,
  keyResult,
  skipIndent,
} from '@jetbrains/vscode-extension-core/keyHandlerUtils';

const DEFAULT_TRIM_MARGIN_CHAR = '|';
const MULTILINE_QUOTE = '"""';
const TEXT_NODE_TYPES = new Set(['multiline_comment', 'line_comment', 'string_content']);
const ENTER_LITERAL_ANCESTOR_TYPES = new Set([
  'string_content',
  'string_literal',
  'character_literal',
]);
const CONTINUATION_ALIGN_ANCESTOR_TYPES = ['type_parameters'];
const PARAMETER_LIST_ANCESTOR_TYPES = ['function_value_parameters', 'primary_constructor'];
const BLOCK_COMMENT_SCAN_ROOT_TYPES = new Set([
  'statements',
  'class_body',
  'enum_class_body',
  'source_file',
]);
const BLOCK_COMMENT_IGNORED_NODE_TYPES = new Set([
  'multiline_comment',
  'line_comment',
  'string_literal',
  'character_literal',
]);
const NON_ANGLE_DELIMITER_ANCESTORS = new Set<string>();
const OPENING_DELIMITER_OPTIONS = {
  extraAutoCloseChars: [':'],
  commentPrefixes: ['//', '/*'],
};

export default (
  text: string,
  tree: Tree,
  key: string,
  index: number,
  indentUnit: string,
): KeyResult => {
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
      return handleSingleQuote(text, tree, index);
    case '"':
      return handleDoubleQuoteKey(text, tree, index);
    case '<':
      return handleLeftAngle(text, tree, index);
    case ')':
    case ']':
    case '>':
    case '}':
      return handleClosingDelimiter(text, tree, key, index);
    default:
      return keyResult(key, index, index + 1, index + key.length);
  }
};

function handleLeftParenthesis(text: string, tree: Tree, index: number): KeyResult {
  return handleOpeningDelimiter(text, tree, index, '(', ')');
}

function handleLeftBracket(text: string, tree: Tree, index: number): KeyResult {
  return handleOpeningDelimiter(text, tree, index, '[', ']');
}

function handleOpeningDelimiter(
  text: string,
  tree: Tree,
  index: number,
  opening: string,
  closing: string,
): KeyResult {
  return handleOpeningDelimiterKey(
    text,
    tree,
    index,
    opening,
    closing,
    handleSpecialNodeKey,
    OPENING_DELIMITER_OPTIONS,
  );
}

function handleLeftBrace(text: string, tree: Tree, index: number): KeyResult {
  return handleKeyWithSpecialNode(text, tree, '{', index, handleSpecialNodeKey, () => {
    // `${...}` templates are the one place where '{' inside a string should
    // still behave like a paired delimiter.
    if (text[index - 1] === '$' && text[index + 1] === '}') {
      return keyResult('{', index, index + 1, index + 1);
    }
    if (text[index - 1] === '$') {
      return keyResult('{}', index, index + 1, index + 1);
    }
    return getOpeningDelimiterResult(text, index, '{', '}', OPENING_DELIMITER_OPTIONS);
  });
}

function handleSingleQuote(text: string, tree: Tree, index: number): KeyResult {
  return handleKeyWithSpecialNode(text, tree, `'`, index, handleSpecialNodeKey, () =>
    keyResult(`''`, index, index + 1, index + 1),
  );
}

function handleDoubleQuoteKey(text: string, tree: Tree, index: number): KeyResult {
  const node = tree.rootNode.descendantForIndex(index);

  // Kotlin overtypes an existing closing quote instead of inserting a duplicate one.
  // fwcd's grammar hides the '"""' token, so detect triple-quote positions by text.
  const isAtTripleQuoteDelimiter =
    text.startsWith('"""', index) ||
    (index >= 1 && text.startsWith('"""', index - 1)) ||
    (index >= 2 && text.startsWith('"""', index - 2));
  if (
    node !== null &&
    !isAtTripleQuoteDelimiter &&
    text[index + 1] === '"' &&
    (index === 0 || text[index - 1] !== '\\')
  ) {
    return keyResult('', index, index + 1, index + 1);
  }

  // fwcd parses an incomplete `"""` (no closing delimiter) as an ERROR node, not
  // string_literal, so handleSpecialNodeKey can't dispatch to handleStringLiteralKey.
  // Use text-based logic for the opening-delimiter position.
  if (isAtTripleQuoteDelimiter && (node === null || node.type !== 'string_literal')) {
    const tripleStart = text.startsWith('"""', index)
      ? index
      : index >= 1 && text.startsWith('"""', index - 1)
        ? index - 1
        : index - 2;
    const offsetFromTripleStart = index - tripleStart;
    switch (offsetFromTripleStart) {
      case 0:
        return keyResult('"', index, index + 1, index + 1);
      case 1:
        return keyResult('', index, index + 1, index + 1);
      default:
        return keyResult('""""', index, index + 1, index + 1);
    }
  }

  // Default fallback: pair quotes when outside any string context.
  return handleKeyWithSpecialNode(text, tree, `"`, index, handleSpecialNodeKey, () =>
    keyResult('""', index, index + 1, index + 1),
  );
}

function handleSpecialNodeKey(
  node: Node,
  text: string,
  key: string,
  index: number,
): KeyResult | null {
  if (node.type === 'multiline_comment') {
    return handleBlockComment(node, key, text, index);
  }
  if (isTextNode(node)) {
    return keyResult(key, index, index + 1, index + 1);
  }
  // fwcd hides the '"' and '"""' delimiter tokens; descendantForIndex at a delimiter
  // position returns the enclosing string_literal (or a child of it) instead.
  if (key === '"') {
    const stringLiteralNode =
      node.type === 'string_literal'
        ? node
        : node.parent?.type === 'string_literal'
          ? node.parent
          : null;
    if (stringLiteralNode !== null) {
      return handleStringLiteralKey(stringLiteralNode, text, index);
    }
  }
  if (key === `'` && node.parent?.type === 'character_literal') {
    return getCharacterLiteralQuoteResult(text, index);
  }
  return null;
}

function isMultilineStringLiteral(text: string, node: Node): boolean {
  // Handles plain `"""` and fwcd master's multi-dollar `$$"""` / `$$$"""` etc.
  const slice = text.slice(node.startIndex, node.startIndex + 256);
  return /^\$*"""/.test(slice);
}

function getCharacterLiteralQuoteResult(text: string, index: number): KeyResult {
  return text[index + 1] === `'` && (index === 0 || text[index - 1] !== '\\')
    ? keyResult('', index, index + 1, index + 1)
    : keyResult(`'`, index, index + 1, index + 1);
}

/**
 * Handles a `"` keystroke at a position inside or at the delimiter of a
 * `string_literal` node, replicating the old per-token behavior now that
 * fwcd's grammar hides the `"` and `"""` delimiter tokens.
 */
function handleStringLiteralKey(node: Node, text: string, index: number): KeyResult {
  // Multi-dollar strings ($$""", $$$""", ...) prefix `"""` with $ signs; skip past them.
  const dollarCount = text.slice(node.startIndex).match(/^\$*/)?.[0].length ?? 0;
  const tripleQuoteStart = node.startIndex + dollarCount;
  if (text.startsWith('"""', tripleQuoteStart)) {
    // Triple-quoted string: replicate handleTripleQuote for the opening delimiter,
    // and mirror the same logic for the closing delimiter.
    const offsetFromTripleQuote = index - tripleQuoteStart;
    if (offsetFromTripleQuote < 3) {
      // Inside the opening """ delimiter
      switch (offsetFromTripleQuote) {
        case 0:
          return keyResult('"', index, index + 1, index + 1);
        case 1:
          if (dollarCount > 0) {
            return keyResult('""', index, index + 1, index + 1);
          }
          return keyResult('', index, index + 1, index + 1);
        default:
          return keyResult('""""', index, index + 1, index + 1);
      }
    }
    const offsetFromEnd = node.endIndex - index; // 3=first char of closing """, 1=last
    if (offsetFromEnd <= 3) {
      // Inside the closing """ delimiter
      const offsetInClosing = 3 - offsetFromEnd; // 0, 1, 2
      switch (offsetInClosing) {
        case 0:
          return keyResult('"', index, index + 1, index + 1);
        case 1:
          return keyResult('', index, index + 1, index + 1);
        default:
          return keyResult('""""', index, index + 1, index + 1);
      }
    }
    // Inside content
    return keyResult('"', index, index + 1, index + 1);
  }
  // Single-quoted string: if at the opening delimiter, pair it; otherwise just insert.
  return index === node.startIndex
    ? keyResult('""', index, index + 1, index + 1)
    : keyResult('"', index, index + 1, index + 1);
}

function handleEnter(text: string, tree: Tree, index: number, indentUnit: string): KeyResult {
  return handleEnterWithHandlers(
    text,
    tree,
    index,
    indentUnit,
    [
      (node) => handleLineCommentEnter(text, node, index, 'line_comment', '//'),
      (node) => handleStringLiteralEnter(text, node, index, indentUnit),
      (node) =>
        hasAncestor(node, ENTER_LITERAL_ANCESTOR_TYPES)
          ? keyResult('\n', index, index + 1, index + 1)
          : null,
      (node) => handleEmptyLambdaBodyEnter(node, text, index, indentUnit),
    ],
    (node) => {
      const commentContext = findBlockCommentContext(node, text, index);
      if (commentContext === null) {
        return handleRegularEnter(
          text,
          node,
          index,
          indentUnit,
          getPreviousBlockCommentIndent(node, text, index),
        );
      }

      const commentIndent = getIndent(text, getLineStart(text, commentContext.start));
      const currentLineTail = getCurrentLineTail(text, index + 1);
      const tailStartsWithAsterisk = /^\s*\*/.test(currentLineTail);
      const whitespaceSinceCommentStart = /^\s*$/.test(
        text.slice(commentContext.start + (commentContext.isDoc ? 3 : 2), index),
      );
      const hasAsteriskLineBefore = hasLineWithPrefix(text, commentContext.start, index, '*');
      const omitLeadingStarOnIndentedFirstLine = shouldOmitLeadingStarOnIndentedFirstLine(
        text,
        commentContext,
        commentIndent,
        index,
        hasAsteriskLineBefore,
        tailStartsWithAsterisk,
      );
      const useLinePrefix =
        commentContext.isDoc ||
        hasAsteriskLineBefore ||
        tailStartsWithAsterisk ||
        (!omitLeadingStarOnIndentedFirstLine &&
          whitespaceSinceCommentStart &&
          (commentContext.end === null || currentLineTail.trim().length === 0));

      if (!useLinePrefix && commentContext.end !== null) {
        if (currentLineTail.trim() === '') {
          const bodyIndent = getClosedBlockCommentBodyIndent(text, commentContext, index);
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

      const docCommentBodyPrefix =
        commentContext.isDoc && currentLineTail.trim().length === 0
          ? getDocCommentBodyPrefix(text, commentContext, index)
          : null;
      const prefixLine = `\n${docCommentBodyPrefix ?? `${commentIndent}${useLinePrefix ? ' * ' : ''}`}`;
      const rewrittenTailResult = rewriteCommentLineTail(
        commentContext,
        commentIndent,
        currentLineTail,
        prefixLine,
        index,
        useLinePrefix,
      );
      if (rewrittenTailResult !== null) {
        return rewrittenTailResult;
      }

      if (commentContext.end === null) {
        return keyResult(
          `${prefixLine}\n${commentIndent} */`,
          index,
          index + 1,
          index + prefixLine.length,
        );
      }
      return keyResult(prefixLine, index, index + 1, index + prefixLine.length);
    },
    () => keyResult('\n', index, index + 1, index + 1),
  );
}

function shouldOmitLeadingStarOnIndentedFirstLine(
  text: string,
  commentContext: BlockCommentContext,
  commentIndent: string,
  index: number,
  hasAsteriskLineBefore: boolean,
  tailStartsWithAsterisk: boolean,
): boolean {
  if (
    commentContext.isDoc ||
    commentContext.end !== null ||
    commentIndent.length === 0 ||
    hasAsteriskLineBefore ||
    tailStartsWithAsterisk
  ) {
    return false;
  }

  return /^\s*$/.test(text.slice(commentContext.start + 2, index));
}

function handleRegularEnter(
  text: string,
  node: Node,
  index: number,
  indentUnit: string,
  previousLineIndentOverride?: string | null,
): KeyResult {
  const previousLineIndent =
    previousLineIndentOverride ?? getPreviousNonEmptyLineIndent(text, index);
  const classTypeParameterContinuationIndent = getClassTypeParameterContinuationIndent(
    text,
    node,
    index,
    indentUnit,
  );
  const classTypeArgumentContinuationIndent = getClassTypeArgumentContinuationIndent(
    text,
    node,
    index,
    indentUnit,
  );
  const continuationIndent =
    getFunctionParameterContinuationIndent(text, node, index) ??
    getFunctionArgumentContinuationIndent(text, node, index) ??
    getCommaSeparatedBodyContinuationIndent(text, node, index, ['enum_class_body']) ??
    classTypeParameterContinuationIndent ??
    classTypeArgumentContinuationIndent ??
    getAlignedAncestorListContinuationIndent(
      text,
      node,
      index,
      CONTINUATION_ALIGN_ANCESTOR_TYPES,
    ) ??
    getTypeArgumentContinuationIndent(text, node, index) ??
    (previousLineIndent === null ? null : `${previousLineIndent}${indentUnit}`);
  const matchingDelimiterEnterResult = handleMatchingDelimiterEnter(
    text,
    index,
    previousLineIndent,
    continuationIndent,
  );
  if (matchingDelimiterEnterResult !== null) {
    return matchingDelimiterEnterResult;
  }
  const leadingNavigationEnterResult = handleLeadingNavigationEnter(
    text,
    index,
    continuationIndent,
  );
  if (leadingNavigationEnterResult !== null) {
    return leadingNavigationEnterResult;
  }
  const directTypeContinuationIndent =
    classTypeParameterContinuationIndent ?? classTypeArgumentContinuationIndent;
  if (directTypeContinuationIndent !== null) {
    const replacement = `\n${directTypeContinuationIndent}`;
    return keyResult(replacement, index, index + 1, index + replacement.length);
  }
  return getRegularEnterResult(text, index, previousLineIndent, continuationIndent);
}

function handleLeadingNavigationEnter(
  text: string,
  index: number,
  continuationIndent: string | null,
): KeyResult | null {
  if (continuationIndent === null) {
    return null;
  }

  if (index + 1 >= text.length) {
    return null;
  }

  const currentLineEnd = getLineEnd(text, index + 1);
  const currentLineContentStart = skipIndent(text, index + 1, currentLineEnd);
  if (!isLeadingNavigationPrefix(text.slice(currentLineContentStart, currentLineEnd))) {
    return null;
  }

  const indent = getLeadingNavigationContinuationIndent(text, index, continuationIndent, [
    '.',
    '?.',
  ]);
  const replacement = `\n${indent}`;
  return keyResult(replacement, index, index + 1, index + replacement.length);
}

function isLeadingNavigationPrefix(line: string): boolean {
  return line.startsWith('.') || line.startsWith('?.');
}

function getClassTypeParameterContinuationIndent(
  text: string,
  node: Node | null,
  index: number,
  indentUnit: string,
): string | null {
  if (node === null || getPreviousSignificantChar(text, index) !== '<') {
    return null;
  }

  const typeParameters = findAncestorAtEnter(node, index, 'type_parameters');
  if (typeParameters?.parent?.type !== 'class_declaration') {
    return null;
  }

  const baseIndent = getIndent(text, getLineStart(text, typeParameters.startIndex));
  return `${baseIndent}${indentUnit}${indentUnit}`;
}

function getClassTypeArgumentContinuationIndent(
  text: string,
  node: Node | null,
  index: number,
  indentUnit: string,
): string | null {
  if (node === null || getPreviousSignificantChar(text, index) !== '<') {
    return null;
  }

  const typeArguments = findAncestorAtEnter(node, index, 'type_arguments');
  const indentAnchor =
    typeArguments?.parent?.type === 'user_type'
      ? typeArguments.parent
      : typeArguments === null
        ? null
        : findAncestor(typeArguments, 'call_expression');
  if (indentAnchor === null) {
    return null;
  }

  const baseIndent = getIndent(text, getLineStart(text, indentAnchor.startIndex));
  return `${baseIndent}${indentUnit}${indentUnit}`;
}

function getFunctionParameterContinuationIndent(
  text: string,
  node: Node | null,
  index: number,
): string | null {
  if (node === null || getPreviousSignificantChar(text, index) !== ',') {
    return null;
  }

  for (const ancestorType of PARAMETER_LIST_ANCESTOR_TYPES) {
    const parameterList = findAncestorAtEnter(node, index, ancestorType);
    if (parameterList !== null) {
      return getMultilineListItemIndent(text, parameterList, index);
    }
  }
  return null;
}

function getFunctionArgumentContinuationIndent(
  text: string,
  node: Node | null,
  index: number,
): string | null {
  if (node === null || getPreviousSignificantChar(text, index) !== ',') {
    return null;
  }

  const valueArguments = findAncestorAtEnter(node, index, 'value_arguments');
  return valueArguments === null
    ? null
    : getExistingMultilineListItemIndent(text, valueArguments, index);
}

function getTypeArgumentContinuationIndent(
  text: string,
  node: Node | null,
  index: number,
): string | null {
  if (node === null || getPreviousSignificantChar(text, index) !== ',') {
    return null;
  }

  const typeArguments = findAncestorAtEnter(node, index, 'type_arguments');
  if (typeArguments === null) {
    return null;
  }

  const callExpression = findAncestor(typeArguments, 'call_expression');
  if (callExpression === null) {
    return getAlignedListIndent(text, typeArguments, index);
  }

  const lineStart = getLineStart(text, callExpression.startIndex);
  const baseIndent = getIndent(text, lineStart);
  const alignmentWidth = callExpression.startIndex - lineStart - baseIndent.length;
  return alignmentWidth <= 0 ? baseIndent : `${baseIndent}${' '.repeat(alignmentWidth)}`;
}

function handleMatchingDelimiterEnter(
  text: string,
  index: number,
  previousLineIndent: string | null,
  continuationIndent: string | null,
): KeyResult | null {
  const matchingDelimiter = getMatchingClosingDelimiter(getPreviousSignificantChar(text, index));
  if (matchingDelimiter === null || index + 1 >= text.length) {
    return null;
  }

  const currentLineEnd = getLineEnd(text, index + 1);
  const currentLineContentStart = skipIndent(text, index + 1, currentLineEnd);
  if (text[currentLineContentStart] !== matchingDelimiter) {
    return null;
  }

  const bodyIndent =
    matchingDelimiter === '}'
      ? getBlockBodyIndent(text, index, previousLineIndent, continuationIndent)
      : (continuationIndent ?? previousLineIndent ?? '');
  const closingIndent = previousLineIndent ?? '';
  const replacement = `\n${bodyIndent}\n${closingIndent}`;
  return keyResult(replacement, index, currentLineContentStart, index + `\n${bodyIndent}`.length);
}

function getBlockBodyIndent(
  text: string,
  index: number,
  previousLineIndent: string | null,
  continuationIndent: string | null,
): string {
  if (previousLineIndent === null) {
    return continuationIndent ?? '';
  }

  const indentStep = getIndentStepFromContext(text, index, previousLineIndent);
  return indentStep === null
    ? (continuationIndent ?? previousLineIndent)
    : `${previousLineIndent}${indentStep}`;
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
  return keyResult(
    replacement,
    index - whitespaceAfterArrow.length,
    index + 1,
    index - whitespaceAfterArrow.length + `\n${bodyIndent}`.length,
  );
}

function handleLeftAngle(text: string, tree: Tree, index: number): KeyResult {
  return handleKeyWithSpecialNode(text, tree, '<', index, handleSpecialNodeKey, (node) => {
    const prev = getPreviousLeaf(node);
    if (prev === null) return keyResult('<', index, index + 1, index + 1);
    switch (prev.type) {
      case 'identifier':
      case 'simple_identifier':
      case 'type_identifier': {
        const looksLikeAClass =
          prev.endIndex === node.startIndex && prev.text[0] === prev.text[0].toUpperCase();
        return looksLikeAClass
          ? keyResult('<>', index, index + 1, index + 1)
          : keyResult('<', index, index + 1, index + 1);
      }
      case 'fun':
        return keyResult('<>', index, index + 1, index + 1);
      default:
        return keyResult('<', index, index + 1, index + 1);
    }
  });
}

function handleClosingDelimiter(text: string, tree: Tree, key: string, index: number): KeyResult {
  if (key === '>') {
    return handlePairedClosingDelimiterKey(text, tree, key, index, '<', handleSpecialNodeKey);
  }

  return handleStandardClosingDelimiterKey(
    text,
    tree,
    key,
    index,
    handleSpecialNodeKey,
    NON_ANGLE_DELIMITER_ANCESTORS,
  );
}

function handleBlockComment(node: Node, key: string, text: string, index: number): KeyResult {
  // tree-sitter-kotlin does not expose dedicated KDoc tokens, so detect KDoc
  // by its opening marker and keep the extra () / [] pairing there.
  if (!node.text.startsWith('/**')) {
    return keyResult(key, index, index + 1, index + 1);
  }

  switch (key) {
    case '(':
      return getOpeningDelimiterResult(text, index, '(', ')', OPENING_DELIMITER_OPTIONS);
    case '[':
      return getOpeningDelimiterResult(text, index, '[', ']', OPENING_DELIMITER_OPTIONS);
    default:
      return keyResult(key, index, index + 1, index + 1);
  }
}

function isTextNode(node: Node | null): boolean {
  return node !== null && TEXT_NODE_TYPES.has(node.type);
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

function getPreviousBlockCommentIndent(node: Node, text: string, index: number): string | null {
  const previousLine = getPreviousNonEmptyLine(text, index);
  if (previousLine === null || previousLine.text.trim() !== '*/') {
    return null;
  }

  const commentEnd = previousLine.start + previousLine.text.lastIndexOf('*/') + 2;
  const blockComment = findAncestor(
    node.tree.rootNode.descendantForIndex(commentEnd - 1),
    'multiline_comment',
  );
  if (blockComment === null || blockComment.endIndex !== commentEnd) {
    return null;
  }
  return getIndent(text, getLineStart(text, blockComment.startIndex));
}

function getClosedBlockCommentBodyIndent(
  text: string,
  commentContext: BlockCommentContext,
  index: number,
): string | null {
  if (commentContext.end === null) {
    return null;
  }

  const commentStartLine = getLineStart(text, commentContext.start);
  const nextLineStart = getNextLineStart(text, index + 1);
  if (nextLineStart !== null && nextLineStart < commentContext.end) {
    const nextLine = text.slice(nextLineStart, getLineEnd(text, nextLineStart)).trim();
    if (nextLine.length !== 0 && nextLine !== '*/') {
      return getIndent(text, nextLineStart);
    }
  }

  const previousLine = getPreviousNonEmptyLine(text, index);
  if (previousLine === null || previousLine.start < commentStartLine) {
    return null;
  }

  const previousLineText = previousLine.text.trim();
  if (previousLineText === '/*' || previousLineText === '/**' || previousLineText === '*/') {
    return null;
  }

  return getIndent(text, previousLine.start);
}

function getDocCommentBodyPrefix(
  text: string,
  commentContext: BlockCommentContext,
  index: number,
): string | null {
  if (!commentContext.isDoc || commentContext.end === null) {
    return null;
  }

  const commentStartLine = getLineStart(text, commentContext.start);
  const nextLineStart = getNextLineStart(text, index + 1);
  if (nextLineStart !== null && nextLineStart < commentContext.end) {
    const nextPrefix = getDocCommentBodyLinePrefix(text, nextLineStart);
    if (nextPrefix !== null) {
      return nextPrefix;
    }
  }

  const previousLine = getPreviousNonEmptyLine(text, index);
  if (previousLine === null || previousLine.start < commentStartLine) {
    return null;
  }

  return getDocCommentBodyLinePrefix(text, previousLine.start);
}

function getDocCommentBodyLinePrefix(text: string, lineStart: number): string | null {
  const lineEnd = getLineEnd(text, lineStart);
  const line = text.slice(lineStart, lineEnd);
  const trimmedLine = line.trim();
  if (trimmedLine.length === 0 || trimmedLine === '/**' || trimmedLine === '*/') {
    return null;
  }

  const indent = getIndent(text, lineStart);
  if (!line.startsWith(`${indent}*`)) {
    return null;
  }

  const spacesAfterStar = line.slice(indent.length + 1).match(/^[ \t]*/)?.[0] ?? '';
  return `${indent}*${spacesAfterStar}`;
}

function getMatchingClosingDelimiter(char: string | null): string | null {
  switch (char) {
    case '(':
      return ')';
    case '[':
      return ']';
    case '{':
      return '}';
    default:
      return null;
  }
}

function getIndentStepFromContext(
  text: string,
  index: number,
  currentIndent: string,
): string | null {
  const currentLineStart = getLineStart(text, index);
  let lineEnd = currentLineStart;
  while (lineEnd > 0) {
    const lineStart = getLineStart(text, lineEnd - 1);
    const line = text.slice(lineStart, lineEnd);
    if (line.trim().length !== 0) {
      const indent = getIndent(text, lineStart);
      if (indent.length < currentIndent.length && currentIndent.startsWith(indent)) {
        const indentStep = currentIndent.slice(indent.length);
        return indentStep.length === 0 ? null : indentStep;
      }
    }
    lineEnd = lineStart;
  }
  return null;
}

interface TextRange {
  start: number;
  end: number;
}

interface StringLiteralContext {
  startIndex: number;
  endIndex: number;
  wrapWithParens: boolean;
  spaceAfterConcatenationOperator: boolean;
}

function handleStringLiteralEnter(
  text: string,
  node: Node,
  index: number,
  indentUnit: string,
): KeyResult | null {
  // multiline_comment nodes can appear as siblings of ERROR nodes; findEnclosingErrorNode
  // may return an out-of-range ERROR sibling and trigger false string-split logic. Skip
  // string handling entirely when the node itself is a block comment.
  if (node.type === 'multiline_comment') {
    return null;
  }

  // In fwcd's grammar both single- and triple-quoted strings are `string_literal`.
  // Detect triple-quoted strings by the `"""` prefix on the enclosing literal.
  const multilineStringLiteral = findAncestorAtEnter(node, index, 'string_literal');
  if (multilineStringLiteral !== null && isMultilineStringLiteral(text, multilineStringLiteral)) {
    return handleMultilineStringLiteralEnter(text, multilineStringLiteral, index, indentUnit);
  }

  const stringLiteral = getStringLiteralContext(text, node, index);
  if (stringLiteral === null || index >= stringLiteral.endIndex - 1) {
    return null;
  }

  const continuationIndent = `${getIndent(text, getLineStart(text, stringLiteral.startIndex))}${indentUnit}${indentUnit}`;
  const wrapPrefix = stringLiteral.wrapWithParens ? '(' : '';
  const wrapSuffix = stringLiteral.wrapWithParens ? ')' : '';
  const lineBreak = getLineBreakAtIndex(text, index);
  const operatorSpacing = stringLiteral.spaceAfterConcatenationOperator ? ' ' : '';
  const before = text.slice(stringLiteral.startIndex + 1, lineBreak.startOffset);
  const after = text.slice(lineBreak.endOffset, stringLiteral.endIndex - 1);
  const replacement = `${wrapPrefix}"${before}" +${operatorSpacing}${lineBreak.text}${continuationIndent}"${after}"${wrapSuffix}`;
  const offset =
    `${wrapPrefix}"${before}" +${operatorSpacing}${lineBreak.text}${continuationIndent}`.length;
  return keyResult(
    replacement,
    stringLiteral.startIndex,
    stringLiteral.endIndex,
    stringLiteral.startIndex + offset,
  );
}

function getStringLiteralContext(
  text: string,
  node: Node,
  index: number,
): StringLiteralContext | null {
  const stringLiteral = findAncestorAtEnter(node, index, 'string_literal');
  if (stringLiteral !== null) {
    return {
      startIndex: stringLiteral.startIndex,
      endIndex: stringLiteral.endIndex,
      wrapWithParens: shouldWrapQualifiedStringReceiver(stringLiteral),
      spaceAfterConcatenationOperator: true,
    };
  }

  return findMalformedInterpolationStringLiteral(text, node, index);
}

function findMalformedInterpolationStringLiteral(
  text: string,
  node: Node,
  index: number,
): StringLiteralContext | null {
  const errorNode = findEnclosingErrorNode(node, index);
  if (errorNode === null) {
    return null;
  }

  const startIndex = findPreviousUnescapedQuote(text, errorNode.startIndex, index - 1);
  if (startIndex === null || text.startsWith(MULTILINE_QUOTE, startIndex)) {
    return null;
  }

  // Reject if the found quote is inside an already-parsed string_literal (i.e. it's a closing
  // quote, not an opening one). This prevents false positives when unrelated string literals
  // happen to precede the cursor inside a broad ERROR node.
  const nodeAtStart = node.tree.rootNode.descendantForIndex(startIndex);
  const existingStringAtStart = findAncestor(nodeAtStart, 'string_literal');
  if (existingStringAtStart !== null && existingStringAtStart.startIndex < startIndex) {
    return null;
  }

  const endIndex = findNextUnescapedQuote(text, index + 1);
  return endIndex === null
    ? null
    : {
        startIndex,
        endIndex: endIndex + 1,
        wrapWithParens: false,
        spaceAfterConcatenationOperator: false,
      };
}

function findPreviousUnescapedQuote(
  text: string,
  minIndex: number,
  startIndex: number,
): number | null {
  for (let current = startIndex; current >= minIndex; current--) {
    if (text[current] !== '"' || isEscapedQuote(text, current)) {
      continue;
    }
    if (current >= 2 && text.startsWith(MULTILINE_QUOTE, current - 2)) {
      current -= 2;
      continue;
    }
    return current;
  }
  return null;
}

function findNextUnescapedQuote(text: string, startIndex: number): number | null {
  for (let current = startIndex; current < text.length; current++) {
    if (text[current] !== '"' || isEscapedQuote(text, current)) {
      continue;
    }
    if (text.startsWith(MULTILINE_QUOTE, current)) {
      current += 2;
      continue;
    }
    return current;
  }
  return null;
}

function isEscapedQuote(text: string, index: number): boolean {
  let backslashCount = 0;
  for (let current = index - 1; current >= 0 && text[current] === '\\'; current--) {
    backslashCount++;
  }
  return backslashCount % 2 === 1;
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
      return keyResult(
        `${prefixLine}\n${commentIndent} */`,
        index,
        index + 1 + currentLineTail.length,
        index + prefixLine.length,
      );
    }
    return useLinePrefix
      ? keyResult(prefixLine, index, index + 1 + currentLineTail.length, index + prefixLine.length)
      : null;
  }

  if (!useLinePrefix) {
    if (commentContext.end === null) {
      return keyResult(
        `${prefixLine}\n${commentIndent} */\n${currentLineTail}`,
        index,
        index + 1 + currentLineTail.length,
        index + prefixLine.length,
      );
    }
    return null;
  }

  if (trimmedTail.startsWith('*/')) {
    return keyResult(
      `${prefixLine}\n${commentIndent} */`,
      index,
      index + 1 + currentLineTail.length,
      index + prefixLine.length,
    );
  }

  if (commentContext.end === null) {
    return keyResult(
      `${prefixLine}\n${commentIndent} */\n${currentLineTail}`,
      index,
      index + 1 + currentLineTail.length,
      index + prefixLine.length,
    );
  }

  const shiftedLine = getCommentBodyLine(currentLineTail, commentIndent);
  return keyResult(
    `${prefixLine}\n${shiftedLine}`,
    index,
    index + 1 + currentLineTail.length,
    index + prefixLine.length,
  );
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
  const marginChar =
    trimCallInfo.kind === 'trimIndent'
      ? null
      : (trimCallInfo.marginChar ?? getMarginCharFromLiteral(text, multilineStringLiteral));
  const tail = text.slice(index + 1, contentEnd);
  const whitespaceAfterCaret = tail.match(/^[ \t]*/)?.[0] ?? '';
  const tailAfterCaret = tail.slice(whitespaceAfterCaret.length);
  const originalLiteralText =
    text.slice(multilineStringLiteral.startIndex, index) +
    text.slice(index + 1, multilineStringLiteral.endIndex);
  if (!originalLiteralText.includes('\n')) {
    const shouldUseTrimIndent =
      trimCallInfo.kind === 'trimIndent' ||
      (marginChar === null && isStartOfMultilineString(text, multilineStringLiteral));
    const linePrefix = getMultilineStringLinePrefix(
      baseIndent,
      indentUnit,
      shouldUseTrimIndent ? null : (marginChar ?? DEFAULT_TRIM_MARGIN_CHAR),
    );
    const trimCall = getInsertedTrimCall(
      text,
      multilineStringLiteral,
      trimCallInfo.kind !== null,
      shouldUseTrimIndent ? null : (marginChar ?? DEFAULT_TRIM_MARGIN_CHAR),
    );
    const replacement =
      tail.length === 0
        ? `\n${linePrefix}\n${baseIndent}${MULTILINE_QUOTE}${trimCall}`
        : `\n${linePrefix}${tail}${MULTILINE_QUOTE}${trimCall}`;
    return keyResult(
      replacement,
      index,
      multilineStringLiteral.endIndex,
      index + `\n${linePrefix}`.length,
    );
  }

  const previousLineStart = getLineStart(text, index);
  const linePrefix = getExistingMultilineStringLinePrefix(
    text,
    multilineStringLiteral,
    previousLineStart,
    indentUnit,
    marginChar,
  );
  if (isBraceEnter(text[index - 1], tailAfterCaret[0])) {
    return keyResult(
      `\n${linePrefix}${whitespaceAfterCaret}\n${linePrefix}${tailAfterCaret}`,
      index,
      contentEnd,
      index + `\n${linePrefix}${whitespaceAfterCaret}`.length,
    );
  }

  return keyResult(`\n${linePrefix}`, index, index + 1, index + `\n${linePrefix}`.length);
}

function shouldWrapQualifiedStringReceiver(stringLiteral: Node): boolean {
  let expression: Node = stringLiteral;
  for (
    let current: Node | null = stringLiteral.parent;
    current !== null;
    current = current.parent
  ) {
    switch (current.type) {
      case 'parenthesized_expression':
        return false;
      case 'navigation_expression':
        return (
          current.namedChild(0)?.startIndex === expression.startIndex &&
          current.namedChild(0)?.endIndex === expression.endIndex
        );
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
):
  | { kind: 'trimIndent'; marginChar: null }
  | { kind: 'trimMargin'; marginChar: string }
  | { kind: null; marginChar: null } {
  for (const callExpression of getLiteralCallChain(multilineStringLiteral)) {
    switch (getCallName(text, callExpression)) {
      case 'trimIndent':
        return { kind: 'trimIndent', marginChar: null };
      case 'trimMargin':
        return {
          kind: 'trimMargin',
          marginChar: getTrimMarginMarginChar(text, callExpression),
        };
    }
  }
  return { kind: null, marginChar: null };
}

function getLiteralCallChain(multilineStringLiteral: Node): Node[] {
  const calls: Node[] = [];
  let current: Node = multilineStringLiteral;
  while (true) {
    const navigationExpression = current.parent;
    if (
      navigationExpression?.type !== 'navigation_expression' ||
      navigationExpression.namedChild(0)?.startIndex !== current.startIndex ||
      navigationExpression.namedChild(0)?.endIndex !== current.endIndex
    ) {
      return calls;
    }

    const callExpression = navigationExpression.parent;
    if (
      callExpression?.type !== 'call_expression' ||
      callExpression.namedChild(0)?.startIndex !== navigationExpression.startIndex ||
      callExpression.namedChild(0)?.endIndex !== navigationExpression.endIndex
    ) {
      return calls;
    }

    calls.push(callExpression);
    current = callExpression;
  }
}

function getCallName(text: string, callExpression: Node): string | null {
  const navigationExpression = callExpression.namedChild(0);
  const rawIdentifier =
    navigationExpression?.type === 'navigation_expression'
      ? navigationExpression.namedChild(1)
      : null;
  // fwcd wraps the method identifier in navigation_suffix (.foo); unwrap to get simple_identifier
  const identifier =
    rawIdentifier?.type === 'navigation_suffix' ? rawIdentifier.namedChild(0) : rawIdentifier;
  return identifier === null ? null : text.slice(identifier.startIndex, identifier.endIndex);
}

function getTrimMarginMarginChar(text: string, callExpression: Node): string {
  const callSuffixOrValueArgs = callExpression.namedChild(1);
  // fwcd wraps value_arguments in call_suffix; unwrap if needed
  const valueArguments =
    callSuffixOrValueArgs?.type === 'call_suffix'
      ? callSuffixOrValueArgs.namedChild(0)
      : callSuffixOrValueArgs;
  const valueArgument =
    valueArguments?.type === 'value_arguments' ? valueArguments.namedChild(0) : null;
  const argumentExpression = valueArgument?.namedChild(0);
  const stringContent =
    argumentExpression?.type === 'string_literal' ? argumentExpression.namedChild(0) : null;
  return stringContent?.type === 'string_content' &&
    stringContent.endIndex - stringContent.startIndex === 1
    ? text[stringContent.startIndex]
    : DEFAULT_TRIM_MARGIN_CHAR;
}

function getMarginCharFromLiteral(text: string, multilineStringLiteral: Node): string | null {
  const lines = text
    .slice(multilineStringLiteral.startIndex, multilineStringLiteral.endIndex)
    .split(/\r?\n/);
  if (lines.length <= 2) {
    return null;
  }

  const middleNonBlankLines = lines.slice(1, -1).filter((line) => line.trim().length !== 0);
  return middleNonBlankLines.length !== 0 &&
    middleNonBlankLines.every((line) => line.trimStart().startsWith(DEFAULT_TRIM_MARGIN_CHAR))
    ? DEFAULT_TRIM_MARGIN_CHAR
    : null;
}

function isStartOfMultilineString(text: string, multilineStringLiteral: Node): boolean {
  const firstLine = text.slice(
    multilineStringLiteral.startIndex,
    Math.min(getLineEnd(text, multilineStringLiteral.startIndex), multilineStringLiteral.endIndex),
  );
  return firstLine.trim().replace(/^\$+/, '') === MULTILINE_QUOTE;
}

function getInsertedTrimCall(
  text: string,
  multilineStringLiteral: Node,
  hasTrimCall: boolean,
  marginChar: string | null,
): string {
  if (hasTrimCall || isTrimCallSuppressed(text, multilineStringLiteral)) {
    return '';
  }
  if (marginChar === null) {
    return '.trimIndent()';
  }
  return marginChar === DEFAULT_TRIM_MARGIN_CHAR ? '.trimMargin()' : `.trimMargin("${marginChar}")`;
}

function isTrimCallSuppressed(text: string, multilineStringLiteral: Node): boolean {
  for (
    let current: Node | null = multilineStringLiteral.parent;
    current !== null;
    current = current.parent
  ) {
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
  return (
    modifiers !== null && text.slice(modifiers.startIndex, modifiers.endIndex).includes('const')
  );
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
    return getMultilineStringLinePrefix(
      baseIndent,
      indentUnit,
      getMarginCharToInsert(
        text,
        multilineStringLiteral,
        text.slice(previousLineStart, getLineEnd(text, previousLineStart)),
        marginChar,
      ),
    );
  }

  const previousLine = text.slice(previousLineStart, getLineEnd(text, previousLineStart));
  const indent = getIndent(text, previousLineStart);
  const insertedMarginChar = getMarginCharToInsert(
    text,
    multilineStringLiteral,
    previousLine,
    marginChar,
  );
  if (insertedMarginChar === null) {
    return indent;
  }

  const trimmedLine = previousLine.slice(indent.length);
  const whitespaceAfterMargin = trimmedLine.startsWith(insertedMarginChar)
    ? (trimmedLine.slice(insertedMarginChar.length).match(/^[ \t]*/)?.[0] ?? '')
    : '';
  return `${indent}${insertedMarginChar}${whitespaceAfterMargin}`;
}

function getMarginCharToInsert(
  text: string,
  multilineStringLiteral: Node,
  previousLine: string,
  marginChar: string | null,
): string | null {
  if (marginChar === null) {
    return null;
  }

  const prefixStripped = previousLine.trimStart();
  const lines = text
    .slice(multilineStringLiteral.startIndex, multilineStringLiteral.endIndex)
    .split(/\r?\n/);
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

function getMultilineStringLinePrefix(
  baseIndent: string,
  indentUnit: string,
  marginChar: string | null,
): string {
  return `${baseIndent}${indentUnit}${marginChar ?? ''}`;
}

function isBraceEnter(charBefore: string | undefined, charAfter: string | undefined): boolean {
  return (
    (charBefore === '{' && charAfter === '}') ||
    (charBefore === '(' && charAfter === ')') ||
    (charBefore === '>' && charAfter === '<')
  );
}

function findBlockCommentContext(
  node: Node,
  text: string,
  index: number,
): BlockCommentContext | null {
  const blockComment = findAncestor(node, 'multiline_comment');
  if (blockComment !== null) {
    // fwcd's master grammar represents unclosed `/*` as a multiline_comment node spanning
    // to EOF. Treat it as unclosed (end=null) when the comment text lacks the closing `*/`.
    const isClosed = blockComment.text.endsWith('*/');
    return {
      start: blockComment.startIndex,
      end: isClosed ? blockComment.endIndex : null,
      isDoc: blockComment.text.startsWith('/**'),
    };
  }

  const errorNode = findEnclosingErrorNode(node, index);
  if (errorNode !== null) {
    const errorComment = findUnterminatedBlockCommentContext(errorNode, text, index);
    if (errorComment !== null) {
      return errorComment;
    }
  }

  return findLexicalBlockCommentContext(node, text, index);
}

function findUnterminatedBlockCommentContext(
  errorNode: Node,
  text: string,
  index: number,
): BlockCommentContext | null {
  const errorStartIndex = errorNode.startIndex;
  if (index - errorStartIndex < 2) {
    return null;
  }

  // In incomplete surrounding constructs, tree-sitter-kotlin may report only the
  // token that first broke recovery (for example a class body's `{`) as ERROR and
  // drop the following unfinished comment from the tree entirely.
  return findUnterminatedBlockCommentInRange(text, errorStartIndex, index, []);
}

function findLexicalBlockCommentContext(
  node: Node,
  text: string,
  index: number,
): BlockCommentContext | null {
  for (const scanRoot of getBlockCommentScanRoots(node)) {
    const ignoredRanges = collectIgnoredRanges(scanRoot, index);
    const commentContext = findUnterminatedBlockCommentInRange(
      text,
      scanRoot.startIndex,
      index,
      ignoredRanges,
    );
    if (commentContext !== null) {
      return commentContext;
    }
  }
  return null;
}

function getBlockCommentScanRoots(node: Node): Node[] {
  const roots: Node[] = [];
  const seenStarts = new Set<number>();
  for (let current: Node | null = node; current !== null; current = current.parent) {
    if (!BLOCK_COMMENT_SCAN_ROOT_TYPES.has(current.type) || seenStarts.has(current.startIndex)) {
      continue;
    }
    roots.push(current);
    seenStarts.add(current.startIndex);
  }

  const sourceFile = node.tree.rootNode;
  if (!seenStarts.has(sourceFile.startIndex)) {
    roots.push(sourceFile);
  }
  return roots;
}

function collectIgnoredRanges(node: Node, endIndex: number): TextRange[] {
  const ranges: TextRange[] = [];
  collectIgnoredRangesRecursively(node, endIndex, ranges);
  return ranges;
}

function collectIgnoredRangesRecursively(node: Node, endIndex: number, ranges: TextRange[]): void {
  if (node.startIndex >= endIndex) {
    return;
  }

  if (shouldIgnoreRangeForBlockCommentScan(node)) {
    ranges.push({
      start: node.startIndex,
      end: Math.min(node.endIndex, endIndex),
    });
    return;
  }

  for (let i = 0; i < node.childCount; i++) {
    const child = node.child(i);
    if (child !== null) {
      collectIgnoredRangesRecursively(child, endIndex, ranges);
    }
  }
}

function shouldIgnoreRangeForBlockCommentScan(node: Node): boolean {
  return (
    BLOCK_COMMENT_IGNORED_NODE_TYPES.has(node.type) ||
    (node.type === 'identifier' && node.text.startsWith('`'))
  );
}

function findUnterminatedBlockCommentInRange(
  text: string,
  startIndex: number,
  endIndex: number,
  ignoredRanges: TextRange[],
): BlockCommentContext | null {
  const stack: Array<{ start: number; isDoc: boolean }> = [];
  let ignoredRangeIndex = 0;
  for (let current = startIndex; current < endIndex; ) {
    const ignoredRange = ignoredRanges[ignoredRangeIndex];
    if (ignoredRange !== undefined && current >= ignoredRange.start) {
      current = ignoredRange.end;
      ignoredRangeIndex++;
      continue;
    }

    if (text.startsWith('/*', current)) {
      stack.push({
        start: current,
        isDoc: text.startsWith('/**', current),
      });
      current += text.startsWith('/**', current) ? 3 : 2;
      continue;
    }

    if (stack.length > 0 && text.startsWith('*/', current)) {
      stack.pop();
      current += 2;
      continue;
    }

    current++;
  }

  const openComment = stack.at(-1);
  return openComment === undefined
    ? null
    : {
        start: openComment.start,
        end: null,
        isDoc: openComment.isDoc,
      };
}
