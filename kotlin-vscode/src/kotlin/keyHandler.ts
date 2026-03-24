import {Tree, Node} from 'web-tree-sitter';
import {type KeyResult, keyResult} from '../types';

export default (tree: Tree, key: string, index: number): KeyResult => {
    const root = tree.rootNode;
    const node = root.descendantForIndex(index);
    if (node === null) return keyResult(key, 1);
    const text = root.text;

    // Kotlin overtypes an existing closing quote instead of inserting a duplicate one.
    if (key === '"' && node.type !== '"""' && text[index + 1 - root.startIndex] === '"') {
        return keyResult('', 1);
    }

    switch (node.type) {
        case 'block_comment':
            return handleBlockComment(node, key, text, root.startIndex, index);
        case 'line_comment':
        case 'string_content':
            return keyResult(key, 1);
        case '"':
            return handleDoubleQuote(node);
        case '"""': {
            const offset = index - node.startIndex;
            switch (offset) {
                case 0:
                    return keyResult('"', 1);
                case 1:
                    return keyResult('', 1);
                default:
                    return keyResult('""""', 1);
            }
        }
        default:
            switch (key) {
                case '(':
                    return getOpenBraceResult(text, index - root.startIndex, '(', ')');
                case '[':
                    return getOpenBraceResult(text, index - root.startIndex, '[', ']');
                case '{': {
                    // `${...}` templates are the one place where '{' inside a string should
                    // still behave like a paired delimiter.
                    if (index > root.startIndex && text[index - 1 - root.startIndex] === '$' && text[index + 1 - root.startIndex] === '}') {
                        return keyResult('{', 1);
                    }
                    if (index > root.startIndex && text[index - 1 - root.startIndex] === '$') {
                        return keyResult('{}', 1);
                    }
                    return getOpenBraceResult(text, index - root.startIndex, '{', '}');
                }
                case `'`:
                    return (node.parent?.type === 'character_literal')
                            ? keyResult('', 1)
                            : keyResult(`''`, 1);
                case '<': {
                    const prev = prevNode(tree.rootNode, node);
                    if (prev === null) return keyResult(key, 1);
                    switch (prev.type) {
                        case 'identifier': {
                            const looksLikeAClass = prev.endIndex === node.startIndex && prev.text[0] === prev.text[0].toUpperCase();
                            return looksLikeAClass ? keyResult('<>', 1) : keyResult(key, 1)
                        }
                        case 'fun':
                            return keyResult('<>', 1);
                        default:
                            return keyResult('<', 1);
                    }
                }
                case ')':
                case ']':
                case '>':
                case '}': {
                    if (index < root.endIndex - 1 && text[index + 1 - root.startIndex] === key) {
                        // Only skip a closer while we are still inside the same broken
                        // parser subtree; otherwise treat the key as a literal character.
                        for (let i = index + 1; i < root.endIndex; i++) {
                            const n = root.descendantForIndex(i);
                            if (n?.isError) {
                                return keyResult('', 1);
                            }
                            switch (n?.type) {
                                case node.type:
                                    if (n.parent?.isError) {
                                        return keyResult('', 1);
                                    }
                                    break
                                case 'source_file':
                                    if (text[i - root.startIndex] === key) {
                                        return keyResult('', 1);
                                    }
                                    break;
                                default:
                                    return keyResult(key, 1);
                            }
                        }
                    }
                    return keyResult(key, 1);
                }
                default:
                    return keyResult(key, 1);
            }
    }
}

function handleDoubleQuote(node: Node): KeyResult {
    const parent = node.parent;
    if (parent === null) return keyResult('"', 1);

    switch (parent.type) {
        case 'string_literal':
            // The Kotlin grammar may eagerly attach this quote to a later closing quote,
            // even though the user just started a new string. If this quote is the
            // opening delimiter of that literal, keep pairing it as an opening quote.
            return node.startIndex === parent.startIndex ? keyResult('""', 1) : keyResult('"', 1);
        case 'string_content':
            return keyResult('"', 1);
        default:
            return keyResult('""', 1);
    }
}

function handleBlockComment(node: Node, key: string, text: string, rootStartIndex: number, index: number): KeyResult {
    // tree-sitter-kotlin does not expose dedicated KDoc tokens, so detect KDoc
    // by its opening marker and keep the extra () / [] pairing there.
    if (!node.text.startsWith('/**')) {
        return keyResult(key, 1);
    }

    switch (key) {
        case '(':
            return getOpenBraceResult(text, index - rootStartIndex, '(', ')');
        case '[':
            return getOpenBraceResult(text, index - rootStartIndex, '[', ']');
        default:
            return keyResult(key, 1);
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
            return keyResult(opening + closing, 1);
        case '/':
            switch (text[textIndex + 2]) {
                case '/':
                case '*':
                    return keyResult(opening + closing, 1);
            }
    }
    return keyResult(opening, 1);
}

function prevNode(root: Node, node: Node): Node | null {
    for (let i = node.startIndex - 1; i >= root.startIndex; i--) {
        const n = root.descendantForIndex(i);
        if (n && n.childCount === 0) return n;
    }
    return null;
}
