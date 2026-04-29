// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import { describe, test, before } from 'node:test';
import assert from 'node:assert/strict';
import * as path from 'path';
import { Parser, Language } from 'web-tree-sitter';
import kotlinKeyHandler from './keyHandler';
import {type KeyResult} from '../types';


describe('Handling key presses in Kotlin files', () => {

    describe('bracket auto-completion', () => {
        test('completes parentheses', doTest('val x = (|', 'val x = (|)'));

        test('completes square brackets', doTest('val x = [|', 'val x = [|]'));

        test('completes curly braces', doTest('val x = {|', 'val x = {|}'));

        test('completes single quotes', doTest("val x = '|", "val x = '|'"));

        test('completes parenthesis after empty angular braces', doTest(`val x = ArrayList<>(|`, `val x = ArrayList<>(|)`));

    });

    describe('angle bracket auto-completion', () => {
        test('completes after fun keyword', doTest('fun <|', 'fun <|>'));

        test('completes after uppercase identifier', doTest('class Foo<|', 'class Foo<|>'));

        test('no completion after lowercase identifier', doTest('val less = x <|', 'val less = x <|'));

        test('no completion after non-identifier token', doTest('val x = 1 <|', 'val x = 1 <|'));

        test('no overtyping in an empty type parameter list', doTest('fun <>|>', 'fun <>|'));

        test('no overtyping in a non-empty type parameter list', doTest('fun <T>|>', 'fun <T>|'));


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

    describe('Enter handling', () => {
        test('starts KDoc comments on enter', doTest('/**\n|\nfun foo() {}', '/**\n * |\n */\nfun foo() {}'));

        test('starts KDoc comments in a class on enter', doTest('class C {\n    /**\n|\n    fun foo() {}\n}', 'class C {\n    /**\n     * |\n     */\n    fun foo() {}\n}'));

        test('moves following code below a generated KDoc closing delimiter', doTest(
                '/**\n|fun foo() {}',
                '/**\n * |\n */\nfun foo() {}',
        ));

        test('moves following class members below a generated KDoc closing delimiter', doTest(
                'class C {\n    /**\n|    fun foo() {}\n}',
                'class C {\n    /**\n     * |\n     */\n    fun foo() {}\n}',
        ));

        test('aligns KDoc closing delimiters when entering before an indented closing line', doTest(
                '/**\n|    */',
                '/**\n * |\n */',
        ));

        test('moves KDoc body text below the caret when entering before an unprefixed body line', doTest(
                '/**\n|    foo\n */',
                '/**\n * |\n * foo\n */',
        ));

        test('moves KDoc body text below the caret when entering before a prefixed body line', doTest(
                '/**\n|    * foo\n */',
                '/**\n * |\n * foo\n */',
        ));

        test('aligns KDoc closing delimiters inside a class when entering before an indented closing line', doTest(
                'class C {\n    /**\n|        */\n}',
                'class C {\n    /**\n     * |\n     */\n}',
        ));

        test('continues KDoc before the closing delimiter', doTest('/**\n| */', '/**\n * |\n */'));

        test('continues KDoc body lines', doTest('/**\n * foo\n| */', '/**\n * foo\n * |\n */'));

        test('starts block comments with leading stars', doTest('/*\n|', '/*\n * |\n */'));

        test('does not continue line comments on enter', doTest('// hello\n|', '// hello\n|'));

        test('does not continue line comments when enter splits them in the middle', doTest('// hel\n|lo', '// hel\n// |lo'));

        test('does not rewrite enter in the middle of a single-line string literal', doTest(
                'val s = "hel\n|lo"',
                'val s = "hel" + \n        |"lo"',
        ));

        test('wraps dot-qualified string receivers in parentheses when splitting on enter', doTest(
                'val l = "foo\n|bar".length()',
                'val l = ("foo" + \n        |"bar").length()',
        ));

        test('does not duplicate existing parentheses around a split string receiver', doTest(
                'val l = ("asdf" + "foo\n|bar").length()',
                'val l = ("asdf" + "foo" + \n        |"bar").length()',
        ));

        test('turns a one-line multiline string into a trimIndent call on enter', doTest(
                `
class A {
    val a = """
|"""
}`,
                `
class A {
    val a = """
        |
    """.trimIndent()
}`,
        ));

        test('preserves custom trimMargin markers when turning a one-line multiline string into two lines', doTest(
                `
class A {
    val a = """blah blah
|""".trimMargin("#")
}`,
                `
class A {
    val a = """blah blah
        #|
    """.trimMargin("#")
}`,
        ));

        test('uses the default trimMargin marker when the trimMargin argument is dynamic', doTest(
                `
class A {
    val m = '#'
    val a = """blah blah
<caret>""".trimMargin(m)
}`,
                `
class A {
    val m = '#'
    val a = """blah blah
        |<caret>
    """.trimMargin(m)
}`,
        ));

        test('does not append trimIndent in const multiline strings', doTest(
                `
object O {
    const val s = """
|"""
}`,
                `
object O {
    const val s = """
        |
    """
}`,
        ));

        test('does not append trimIndent in annotation multiline strings with interpolation prefixes', doTest(
                `
@Annotation($$"""
|""")
fun some() {}`,
                `
@Annotation($$"""
    |
""")
fun some() {}`,
        ));

        test('keeps existing multiline string closing quotes aligned on enter', doTest(
                `
fun some() {
    val b = """
|
    """
}`,
                `
fun some() {
    val b = """
        |
    """
}`,
        ));

        test('moves a template entry onto the new multiline string line on enter', doTest(
                `
val className = 1
val test = """
|$className"""`,
                `
val className = 1
val test = """
    |$className""".trimIndent()`,
        ));

        test('keeps trimMargin indentation when entering inside matching braces', doTest(
                `
val a =
  """
     |  blah blah blah {
<caret>}
  """.trimMargin()`,
                `
val a =
  """
     |  blah blah blah {
     |  <caret>
     |  }
  """.trimMargin()`,
        ));

        test('keeps whitespace after the caret on the current trimMargin line when entering before a closing brace', doTest(
                `
val a =
  """
     |  blah blah blah {
<caret>    }
  """.trimMargin()`,
                `
val a =
  """
     |  blah blah blah {
     |      <caret>
     |  }
  """.trimMargin()`,
        ));

        test('reuses the previous trimIndent line indentation when entering before a closing brace', doTest(
                `
fun test = """
    {
        abc
        abc {
<caret>
    }
""".trimIndent()`,
                `
fun test = """
    {
        abc
        abc {
        <caret>
    }
""".trimIndent()`,
        ));

        test('keeps whitespace after the caret on the current trimIndent line when entering before a closing brace', doTest(
                `
fun test = """
    {
        abc
        abc {
<caret>    }
""".trimIndent()`,
                `
fun test = """
    {
        abc
        abc {
            <caret>
        }
""".trimIndent()`,
        ));
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
                `
val result = foo(
    bar(|
)`,
                `
val result = foo(
    bar(|)
)`,
        ));

        test('completes square brackets in multi-line KDoc content', doTest(
                `
/**
 * See [|
 */`,
                `
/**
 * See [|]
 */`,
        ));

        test('completes parentheses in a multi-line interpolation expression', doTest(
                `
val s = """
    \${foo(
        bar(|
    )}
""".trimIndent()`,
                `
val s = """
    \${foo(
        bar(|)
    )}
""".trimIndent()`,
        ));

        test('does not rewrite enter inside a lambda body', doTest(
                `
listOf(1, 2, 3).map { x -> 
|}`,
                `
listOf(1, 2, 3).map { x ->
    |
}`,
        ));

        test('keeps enclosing indentation when expanding an empty lambda body', doTest(
                `
fun test() {
    listOf(1, 2, 3).map { x -> 
|}
}`,
                `
fun test() {
    listOf(1, 2, 3).map { x ->
        |
    }
}`,
        ));

        test('respects configured two-space indentation when expanding an empty lambda body', doTest(
                `
fun test() {
  listOf(1, 2, 3).map { x -> 
|}
}`,
                `
fun test() {
  listOf(1, 2, 3).map { x ->
    |
  }
}`,
                '  ',
        ));

        test('properly handles malformed interpolation', doTest(
                'val s = "a${}b"\n|',
                'val s = "a${}b"\n|',
        ));

        test('properly indents next line', doTest(
                `
class A {
  val x = 1
|
}                
                `,
                `
class A {
  val x = 1
  |
}                
                `,
        ));

        test('properly indents next line when block uses tabs', doTest(
                `
class A {
\tval x = 1
|
}
                `,
                `
class A {
\tval x = 1
\t|
}
                `,
        ));

        test('properly indents next line before a closing parenthesis', doTest(
                `
fun test() {
  call(
    value,
|
  )
}
                `,
                `
fun test() {
  call(
    value,
        |
  )
}
                `,
        ));

        test('properly indents next line before a closing bracket', doTest(
                `
fun test() {
  val y = xs[
|
  ]
}
                `,
                `
fun test() {
  val y = xs[
      |
  ]
}
                `,
        ));

        test('inserts block indent on blank line before next statement', doTest(
                `
class A {
  val x = 1
|
  val y = 2
}`,
                `
class A {
  val x = 1
  |
  val y = 2
}`,
        ));

        test('indents after opening parenthesis when next line closes call', doTest(
                `
fun test() {
  val result = foo(
|
  )
}
                `,
                `
fun test() {
  val result = foo(
      |
  )
}
                `,
        ));

        test('indents after comma in function call when next line closes call', doTest(
                `
fun test() {
  val result = foo(
    first,
|
  )
}
                `,
                `
fun test() {
  val result = foo(
    first,
        |
  )
}
                `,
        ));

        test('indents after arithmetic operator when next line closes parenthesized expression', doTest(
                `
fun test() {
  val result = (1 +
|
  )
}
                `,
                `
fun test() {
  val result = (1 +
      |
  )
}
                `,
        ));

        test('inserts block indent on blank line before leading operator on next line', doTest(
                `
fun test() {
  val result = 1
|
  + 2
}
                `,
                `
fun test() {
  val result = 1
  |
  + 2
}
                `,
        ));

        test('indents after arithmetic operator when next line starts with an operand', doTest(
                `
fun test() {
  val result = 1 +
|
2
}
                `,
                `
fun test() {
  val result = 1 +
      |
2
}
                `,
        ));

        test('inserts block indent on blank line before leading boolean operator on next line', doTest(
                `
fun test() {
  val ok = conditionA
|
  && conditionB
}
                `,
                `
fun test() {
  val ok = conditionA
  |
  && conditionB
}
                `,
        ));

        test('indents after boolean operator when next line starts with an operand', doTest(
                `
fun test() {
  val ok = conditionA &&
|
conditionB
}
                `,
                `
fun test() {
  val ok = conditionA &&
      |
conditionB
}
                `,
        ));

        test('indents with tabs before a closing brace', doTest(
                `
fun test() {
\tif (true) {
\t\tprintln(1)
|
\t}
}
                `,
                `
fun test() {
\tif (true) {
\t\tprintln(1)
\t\t|
\t}
}
                `,
        ));

        test('continues indent after equals for single-expression function body', doTest(
                `
fun answer() =
|
42
                `,
                `
fun answer() =
    |
42
                `,
        ));

        test('inserts block indent after statement-ending semicolon, not continuation indent', doTest(
                `
fun test() {
  val a = 1;
|
  val b = 2
}
                `,
                `
fun test() {
  val a = 1;
  |
  val b = 2
}
                `,
        ));

        test('continues indent after comma in destructuring before closing parenthesis', doTest(
                `
fun test() {
  val (a,
|
  ) = p
}
                `,
                `
fun test() {
  val (a,
      |
  ) = p
}
                `,
        ));

        test('continues indent when splitting if condition before closing parenthesis', doTest(
                `
fun test() {
  if (true &&
|
  ) {}
}
                `,
                `
fun test() {
  if (true &&
      |
  ) {}
}
                `,
        ));

        test('continues indent after elvis operator before expression on next line', doTest(
                `
fun test() {
  val x = a ?:
|
  b
}
                `,
                `
fun test() {
  val x = a ?:
      |
  b
}
                `,
        ));

        test('handles CRLF when continuing before closing parenthesis', doTest(
                `fun test() {\r\n  foo(\r\n    1,\r\n|\r\n  )\r\n}`,
                `fun test() {\r\n  foo(\r\n    1,\r\n        |\r\n  )\r\n}`,
        ));

        test('keeps escaped quote in string literals', doTest(
                `val s = "\\"|"`,
                `val s = "\\"|"`,
        ));

        test('keeps escaped quote in char literals', doTest(
                `val c = '\\'|'`,
                `val c = '\\'|'`,
        ));
    });

    describe('suppresses extra closing symbols', () => {
        test('no extra parenthesis', doTest('val x = (2 + 3)|)', 'val x = (2 + 3)|'));

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
 *                  or `<caret>` marking the caret position (the typed
 *                  character is the one just left of the marker).
 * @param expected  Source code after the handler's result has been applied,
 *                  with the same caret marker as the input.
 * @param indentUnit
 */
function doTest(input: string, expected: string, indentUnit?: string): () => void {
    return () => {
        const caretMarker = input.includes('<caret>') ? '<caret>' : '|';
        const caretIndex = input.indexOf(caretMarker);
        const source = input.slice(0, caretIndex) + input.slice(caretIndex + caretMarker.length);
        const key = source[caretIndex - 1];
        const keyOffset = caretIndex - key.length;
        const effectiveIndentUnit = indentUnit ?? '    ';
        assert.ok(parser, `No parser initialized`);
        const tree = parser.parse(source)!;
        const result = kotlinKeyHandler(source, tree, key, keyOffset, effectiveIndentUnit);
        const resultSource = applyEdit(source, result);
        const caretOffset = result.caretOffset;
        const resultWithCaret = resultSource.slice(0, caretOffset) + caretMarker + resultSource.slice(caretOffset);

        assert.strictEqual(resultWithCaret, expected);
    };
}

function applyEdit(source: string, result: KeyResult): string {
    return source.slice(0, result.startOffset) + result.text + source.slice(result.endOffset);
}
