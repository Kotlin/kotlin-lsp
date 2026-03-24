// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import { describe, test, before } from 'node:test';
import assert from 'node:assert/strict';
import * as path from 'path';
import { Parser, Language } from 'web-tree-sitter';
import kotlinKeyHandler from './keyHandler';


describe('Handling key presses in Kotlin files', () => {

    describe('bracket auto-completion', () => {
        test('completes parentheses', doTest('val x = (|', 'val x = (|)'));

        test('completes square brackets', doTest('val x = [|', 'val x = [|]'));

        test('completes curly braces', doTest('val x = {|', 'val x = {|}'));

        test('completes single quotes', doTest("val x = '|", "val x = '|'"));
    });

    describe('angle bracket auto-completion', () => {
        test('completes after fun keyword', doTest('fun <|', 'fun <|>'));

        test('completes after uppercase identifier', doTest('class Foo<|', 'class Foo<|>'));

        test('no completion after lowercase identifier', doTest('val less = x <|', 'val less = x <|'));

        test('no completion after non-identifier token', doTest('val x = 1 <|', 'val x = 1 <|'));
    });

    describe('single quote handling', () => {
        test('wraps in single quotes when opening a char literal', doTest('val x = \'|', 'val x = \'|\''));

        test('no auto-close when typing closing quote', doTest('val x = \'x\'|\'', 'val x = \'x\'|'));
    });

    describe('double quote handling', () => {
        test('wraps in double quotes when opening a string', doTest('val x = "|', 'val x = "|"'));

        test('no auto-close when typing closing quote of an existing string', doTest('val x = "hello"|', 'val x = "hello"|'));

        test('skips an existing closing quote', doTest('val x = "hello"|"', 'val x = "hello"|'));

        test('fails because of a bug inn the grammar - adds a matching quote when opening a string when another string is near', doTest('val a = "|\nval b = ""', 'val a = "|"\nval b = ""'));
    });

    describe('triple-quoted string handling', () => {
        test('completes multiline string delimiters on third quote', doTest('"""|', '"""|"""'));
    });

    describe('no completion inside comments', () => {
        test('no completion inside block comment', doTest('/* (| */', '/* (| */'));

        test('no completion inside line comment', doTest('// (|', '// (|'));
    });

    describe('KDoc handling', () => {
        test('completes parentheses in KDoc comments', doTest('/** (| */', '/** (|) */'));

        test('completes square brackets in KDoc comments', doTest('/** [| */', '/** [|] */'));
    });

    describe('no completion inside string content', () => {
        test('no completion for ( inside a string', doTest('"(|"', '"(|"'));
    });

    describe('string interpolation', () => {
        test('completes braces for ${...} interpolation', doTest('val s = "${|"', 'val s = "${|}"'));

        test('does not duplicate an interpolation closing brace', doTest('val s = "${|}"', 'val s = "${|}"'));

        test('completes parentheses inside interpolation expression', doTest('val s = "${foo(|}"', 'val s = "${foo(|)}"'));

        test('no completion in string content before interpolation', doTest('val s = "a(| ${x}"', 'val s = "a(| ${x}"'));

        test('completes braces in nested interpolation', doTest('val s = "${outer("${|")}"', 'val s = "${outer("${|}")}"'));

        test('completes parentheses in deeply nested interpolation expression', doTest('val s = "${outer("${inner(|}")}"', 'val s = "${outer("${inner(|)}")}"'));

        test('no completion inside deepest nested string content', doTest('val s = "${outer("${"a(|"}")}"', 'val s = "${outer("${"a(|"}")}"'));
    });

    describe('multi-line snippets', () => {
        test('completes parentheses in a multi-line call chain', doTest(
                `val result = foo(
    bar(|
)`,
                `val result = foo(
    bar(|)
)`,
        ));

        test('completes square brackets in multi-line KDoc content', doTest(
                `/**
 * See [|
 */`,
                `/**
 * See [|]
 */`,
        ));

        test('completes parentheses in a multi-line interpolation expression', doTest(
                `val s = """
    \${foo(
        bar(|
    )}
""".trimIndent()`,
                `val s = """
    \${foo(
        bar(|)
    )}
""".trimIndent()`,
        ));
    });

    describe('suppresses extra closing symbols', () => {
        test('no extra parenthesis', doTest('\nval x = (2 + 3)|)', '\nval x = (2 + 3)|'));

        test('no extra bracket', doTest('val x = a[0]|]', 'val x = a[0]|'));

        test('no extra curly bracket', doTest('fun f() {}|}', 'fun f() {}|'));

        test('no extra angular bracket', doTest('fun <T>|>', 'fun <T>|'));
    })
});


let parser: Parser;

before(async () => {
    const projectRoot = path.resolve(__dirname, '..', '..');
    await Parser.init({
        locateFile: () => path.join(projectRoot, 'node_modules', 'web-tree-sitter', 'web-tree-sitter.wasm'),
    });
    const kotlinLanguage = await Language.load(
            path.join(projectRoot, 'node_modules', '@tree-sitter-grammars', 'tree-sitter-kotlin', 'tree-sitter-kotlin.wasm'),
    );
    parser = new Parser();
    parser.setLanguage(kotlinLanguage);
});

/**
 * Simulates a single key press and the handler's response.
 *
 * @param input     Source code immediately after a key was typed, with `|`
 *                  marking the caret position (the typed character is the one
 *                  just left of `|`).
 * @param expected  Source code after the handler's result has been applied,
 *                  with `|` marking the expected caret position.
 */
function doTest(input: string, expected: string): () => void {
    return () => {
        const caretIndex = input.indexOf('|');
        assert.notEqual(caretIndex, -1, 'Input must contain | as caret marker');
        const source = input.slice(0, caretIndex) + input.slice(caretIndex + 1);
        const keyOffset = caretIndex - 1;
        const key = source[keyOffset];
        assert.ok(parser, `No parser initialized`);
        const tree = parser.parse(source)!;
        const result = kotlinKeyHandler(tree, key, keyOffset);
        const resultSource = source.slice(0, keyOffset) + result.text + source.slice(keyOffset + 1);
        const resultWithCaret = resultSource.slice(0, keyOffset + result.offset) + '|' + resultSource.slice(keyOffset + result.offset);

        assert.strictEqual(resultWithCaret, expected);
    };
}
