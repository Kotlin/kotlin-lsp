import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { Language, Parser } from 'web-tree-sitter';
import { type KeyHandler, type KeyResult } from './keyHandlerUtils';

const CARET_MARKER = '<caret>';
const WEB_TREE_SITTER_WASM_PATH = fileURLToPath(
  new URL('../node_modules/web-tree-sitter/web-tree-sitter.wasm', import.meta.url),
);

let treeSitterInitPromise: Promise<void> | null = null;

export async function createParser(grammarWasmPath: string): Promise<Parser> {
  treeSitterInitPromise ??= Parser.init({
    locateFile: () => WEB_TREE_SITTER_WASM_PATH,
  });
  await treeSitterInitPromise;

  const language = await Language.load(grammarWasmPath);
  const parser = new Parser();
  parser.setLanguage(language);
  return parser;
}

export function createDoTest(
  parserPromise: Promise<Parser>,
  handler: KeyHandler,
): (input: string, expected: string, indentUnit?: string) => () => Promise<void> {
  return (input: string, expected: string, indentUnit?: string) => async () => {
    const caretIndex = input.indexOf(CARET_MARKER);
    assert.notEqual(caretIndex, -1, `Input must contain ${CARET_MARKER} as caret marker`);

    const source = input.slice(0, caretIndex) + input.slice(caretIndex + CARET_MARKER.length);
    const key = source[caretIndex - 1];
    const keyOffset = caretIndex - key.length;
    const effectiveIndentUnit = indentUnit ?? '    ';
    const parser = await parserPromise;

    const tree = parser.parse(source)!;
    const result = handler(source, tree, key, keyOffset, effectiveIndentUnit);
    const resultSource = applyEdit(source, result);
    const caretOffset = result.caretOffset;
    const resultWithCaret =
      resultSource.slice(0, caretOffset) + CARET_MARKER + resultSource.slice(caretOffset);

    assert.strictEqual(resultWithCaret, expected);
  };
}

function applyEdit(source: string, result: KeyResult): string {
  return source.slice(0, result.startOffset) + result.text + source.slice(result.endOffset);
}
