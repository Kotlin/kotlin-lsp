// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import { describe, test } from 'node:test';
import * as path from 'path';
import {
  createDoTest,
  createDoTestKey,
  createParser,
} from '@jetbrains/vscode-extension-core/testing/keyHandlerTestUtils';
import kotlinKeyHandler from './keyHandler';

const projectRoot = process.cwd();
const parser = createParser(path.join(projectRoot, 'grammars', 'tree-sitter-kotlin.wasm'));
const doTest = createDoTest(parser, kotlinKeyHandler);
const doTestKey = createDoTestKey(parser, kotlinKeyHandler);

describe('Handling key presses in Kotlin files', () => {
  describe('bracket auto-completion', () => {
    test('completes parentheses', doTest('val x = (<caret>', 'val x = (<caret>)'));

    test('completes square brackets', doTest('val x = [<caret>', 'val x = [<caret>]'));

    test('completes curly braces', doTest('val x = {<caret>', 'val x = {<caret>}'));

    test(
      'completes parentheses with CRLF line separator',
      doTest('fun f(<caret>\r\n\r\n', 'fun f(<caret>)\r\n\r\n'),
    );

    test(
      'completes curly braces with CRLF line separator',
      doTest('fun f() {<caret>\r\n\r\n', 'fun f() {<caret>}\r\n\r\n'),
    );

    test('completes single quotes', doTest("val x = '<caret>", "val x = '<caret>'"));

    test(
      'completes parenthesis after empty angular braces',
      doTest(`val x = ArrayList<>(<caret>`, `val x = ArrayList<>(<caret>)`),
    );
  });

  describe('angle bracket auto-completion', () => {
    test('completes after fun keyword', doTest('fun <<caret>', 'fun <<caret>>'));

    test('completes after uppercase identifier', doTest('class Foo<<caret>', 'class Foo<<caret>>'));

    test(
      'no completion after lowercase identifier',
      doTest('val less = x <<caret>', 'val less = x <<caret>'),
    );

    test(
      'no completion after non-identifier token',
      doTest('val x = 1 <<caret>', 'val x = 1 <<caret>'),
    );

    test(
      'no overtyping in an empty type parameter list',
      doTest('fun <><caret>>', 'fun <><caret>'),
    );

    test(
      'no overtyping in a non-empty type parameter list',
      doTest('fun <T><caret>>', 'fun <T><caret>'),
    );
  });

  describe('closing delimiter handling', () => {
    test('skips an existing closing parenthesis', doTest('foo()<caret>)', 'foo()<caret>'));

    test('skips an existing closing square bracket', doTest('arr[0]<caret>]', 'arr[0]<caret>'));

    test('skips an existing closing brace', doTest('run {}<caret>}', 'run {}<caret>'));
  });

  describe('single quote handling', () => {
    test(
      'wraps in single quotes when opening a char literal',
      doTest("val x = '<caret>", "val x = '<caret>'"),
    );

    test(
      'no auto-close when typing closing quote',
      doTest("val x = 'x'<caret>'", "val x = 'x'<caret>"),
    );
  });

  describe('double quote handling', () => {
    test(
      'wraps in double quotes when opening a string',
      doTestKey('val x = <caret>', '"', 'val x = "<caret>"'),
    );

    test(
      'no auto-close when typing closing quote of an existing string',
      doTestKey('val x = "hello<caret>', '"', 'val x = "hello"<caret>'),
    );

    test(
      'skips an existing closing quote',
      doTestKey('val x = "hello<caret>"', '"', 'val x = "hello"<caret>'),
    );

    test(
      'fails because of a bug inn the grammar - adds a matching quote when opening a string when another string is near',
      doTestKey('val a = <caret>\nval b = ""', '"', 'val a = "<caret>"\nval b = ""'),
    );
  });

  describe('triple-quoted string handling', () => {
    test(
      'completes multiline string delimiters on third quote',
      doTestKey('""<caret>', '"', '"""<caret>"""'),
    );

    test(
      'completes multiline string delimiters on third quote in an assignment',
      doTestKey('val str = ""<caret>', '"', 'val str = """<caret>"""'),
    );

    test(
      'inserts a quote at the beginning of the content',
      doTestKey('"""<caret>text"""', '"', '""""<caret>text"""'),
    );

    test(
      'inserts a quote at the beginning of the content in an assignment',
      doTestKey('val str = """<caret>text"""', '"', 'val str = """"<caret>text"""'),
    );

    test(
      'inserts a quote in the middle of the content',
      doTestKey('"""te<caret>xt"""', '"', '"""te"<caret>xt"""'),
    );

    test(
      'inserts a quote in the middle of the content in an assignment',
      doTestKey('val str = """te<caret>xt"""', '"', 'val str = """te"<caret>xt"""'),
    );

    test(
      'overtypes first quote of the closing delimiter',
      doTestKey('"""text<caret>"""', '"', '"""text"<caret>""'),
    );

    test(
      'overtypes first quote of the closing delimiter in an assignment',
      doTestKey('val str = """text<caret>"""', '"', 'val str = """text"<caret>""'),
    );

    test(
      'overtypes second quote of the closing delimiter',
      doTestKey('"""text"<caret>""', '"', '"""text""<caret>"'),
    );

    test(
      'overtypes second quote of the closing delimiter in an assignment',
      doTestKey('val str = """text"<caret>""', '"', 'val str = """text""<caret>"'),
    );

    test(
      'overtypes third quote of the closing delimiter',
      doTestKey('"""text""<caret>"', '"', '"""text"""<caret>'),
    );

    test(
      'overtypes third quote of the closing delimiter in an assignment',
      doTestKey('val str = """text""<caret>"', '"', 'val str = """text"""<caret>'),
    );

    test(
      'opens a new string after the closing delimiter',
      doTestKey('"""text"""<caret>', '"', '"""text""""<caret>'),
    );

    test(
      'opens a new string after the closing delimiter in an assignment',
      doTestKey('val str = """text"""<caret>', '"', 'val str = """text""""<caret>'),
    );
  });

  describe('no completion inside comments', () => {
    test('no completion inside block comment', doTest('/* (<caret> */', '/* (<caret> */'));

    test('no completion inside line comment', doTest('// (<caret>', '// (<caret>'));
  });

  describe('KDoc handling', () => {
    test('completes parentheses in KDoc comments', doTest('/** (<caret> */', '/** (<caret>) */'));

    test(
      'completes square brackets in KDoc comments',
      doTest('/** [<caret> */', '/** [<caret>] */'),
    );
  });

  describe('Enter handling', () => {
    test('completes multi-lne comments', doTest('  /*\n<caret>', '  /*\n  <caret>\n   */'));

    test(
      'completes multi-lne comments within function body',
      doTest(
        `
fun foo() {
  println("hello")
  /*
<caret>
  println("world")
}`,
        `
fun foo() {
  println("hello")
  /*
  <caret>
   */
  println("world")
}`,
        '  ',
      ),
    );

    test(
      'starts KDoc comments on enter',
      doTest('/**\n<caret>\nfun foo() {}', '/**\n * <caret>\n */\nfun foo() {}'),
    );

    test(
      'starts KDoc comments in a class on enter',
      doTest(
        'class C {\n    /**\n<caret>\n    fun foo() {}\n}',
        'class C {\n    /**\n     * <caret>\n     */\n    fun foo() {}\n}',
      ),
    );

    test(
      'moves following code below a generated KDoc closing delimiter',
      doTest('/**\n<caret>fun foo() {}', '/**\n * <caret>\n */\nfun foo() {}'),
    );

    test(
      'moves following class members below a generated KDoc closing delimiter',
      doTest(
        'class C {\n    /**\n<caret>    fun foo() {}\n}',
        'class C {\n    /**\n     * <caret>\n     */\n    fun foo() {}\n}',
      ),
    );

    test(
      'aligns KDoc closing delimiters when entering before an indented closing line',
      doTest('/**\n<caret>    */', '/**\n * <caret>\n */'),
    );

    test(
      'moves KDoc body text below the caret when entering before an unprefixed body line',
      doTest('/**\n<caret>    foo\n */', '/**\n * <caret>\n * foo\n */'),
    );

    test(
      'moves KDoc body text below the caret when entering before a prefixed body line',
      doTest('/**\n<caret>    * foo\n */', '/**\n * <caret>\n * foo\n */'),
    );

    test(
      'aligns KDoc closing delimiters inside a class when entering before an indented closing line',
      doTest(
        'class C {\n    /**\n<caret>        */\n}',
        'class C {\n    /**\n     * <caret>\n     */\n}',
      ),
    );

    test(
      'continues KDoc before the closing delimiter',
      doTest('/**\n<caret> */', '/**\n * <caret>\n */'),
    );

    test(
      'continues KDoc body lines',
      doTest('/**\n * foo\n<caret> */', '/**\n * foo\n * <caret>\n */'),
    );

    test('starts block comments with leading stars', doTest('/*\n<caret>', '/*\n * <caret>\n */'));

    test(
      'does not continue line comments on enter',
      doTest('// hello\n<caret>', '// hello\n<caret>'),
    );

    test(
      'does not continue line comments when enter splits them in the middle',
      doTest('// hel\n<caret>lo', '// hel\n// <caret>lo'),
    );

    test(
      'does not rewrite enter in the middle of a single-line string literal',
      doTest('val s = "hel\n<caret>lo"', 'val s = "hel" + \n        <caret>"lo"'),
    );

    test(
      'does not rewrite enter in the middle of a single-line string literal with CRLF',
      doTest('val s = "hel\r\n<caret>lo"', 'val s = "hel" + \r\n        <caret>"lo"'),
    );

    test(
      'wraps dot-qualified string receivers in parentheses when splitting on enter',
      doTest(
        'val l = "foo\n<caret>bar".length()',
        'val l = ("foo" + \n        <caret>"bar").length()',
      ),
    );

    test(
      'does not duplicate existing parentheses around a split string receiver',
      doTest(
        'val l = ("asdf" + "foo\n<caret>bar").length()',
        'val l = ("asdf" + "foo" + \n        <caret>"bar").length()',
      ),
    );

    test(
      'turns a one-line multiline string into a trimIndent call on enter',
      doTest(
        `
class A {
    val a = """
<caret>"""
}`,
        `
class A {
    val a = """
        <caret>
    """.trimIndent()
}`,
      ),
    );

    test(
      'preserves custom trimMargin markers when turning a one-line multiline string into two lines',
      doTest(
        `
class A {
    val a = """blah blah
<caret>""".trimMargin("#")
}`,
        `
class A {
    val a = """blah blah
        #<caret>
    """.trimMargin("#")
}`,
      ),
    );

    test(
      'uses the default trimMargin marker when the trimMargin argument is dynamic',
      doTest(
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
      ),
    );

    test(
      'does not append trimIndent in const multiline strings',
      doTest(
        `
object O {
    const val s = """
<caret>"""
}`,
        `
object O {
    const val s = """
        <caret>
    """
}`,
      ),
    );

    test(
      'does not append trimIndent in annotation multiline strings with interpolation prefixes',
      doTest(
        `
@Annotation($$"""
<caret>""")
fun some() {}`,
        `
@Annotation($$"""
    <caret>
""")
fun some() {}`,
      ),
    );

    test(
      'keeps existing multiline string closing quotes aligned on enter',
      doTest(
        `
fun some() {
    val b = """
<caret>
    """
}`,
        `
fun some() {
    val b = """
        <caret>
    """
}`,
      ),
    );

    test(
      'moves a template entry onto the new multiline string line on enter',
      doTest(
        `
val className = 1
val test = """
<caret>$className"""`,
        `
val className = 1
val test = """
    <caret>$className""".trimIndent()`,
      ),
    );

    test(
      'keeps trimMargin indentation when entering inside matching braces',
      doTest(
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
      ),
    );

    test(
      'keeps whitespace after the caret on the current trimMargin line when entering before a closing brace',
      doTest(
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
      ),
    );

    test(
      'reuses the previous trimIndent line indentation when entering before a closing brace',
      doTest(
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
      ),
    );

    test(
      'keeps whitespace after the caret on the current trimIndent line when entering before a closing brace',
      doTest(
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
      ),
    );

    test(
      'Properly indents class body',
      doTest(
        `
class C {
<caret>}`,
        `
class C {
    <caret>
}`,
      ),
    );

    test(
      'Properly indents method body',
      doTest(
        `
class C {
  fun foo() {
<caret>}
}`,
        `
class C {
  fun foo() {
    <caret>
  }
}`,
      ),
    );

    test(
      'properly indents after a single-line comment',
      doTest(
        `
fun foo() {
  // comment
<caret>
}`,
        `
fun foo() {
  // comment
  <caret>
}`,
        '  ',
      ),
    );

    test(
      'properly indents after a multi-line comment',
      doTest(
        `
fun foo() {
  /*
  comment
  */
<caret>
}`,
        `
fun foo() {
  /*
  comment
  */
  <caret>
}`,
        '  ',
      ),
    );

    test(
      'properly indents after a multiline comment',
      doTest(
        `
fun foo() {
    /* comment */
<caret>
}`,
        `
fun foo() {
    /* comment */
    <caret>
}`,
        '    ',
      ),
    );

    test(
      'properly indents inside a multi-line comment',
      doTest(
        `
/*
      blah
<caret>
      blah
 */`,
        `
/*
      blah
      <caret>
      blah
 */`,
      ),
    );

    test(
      'properly indents inside a multi-line comment with CRLF',
      doTest(
        '         val c = """\r\n             line 1\r\n<caret>\r\n         """.trimIndent()',
        '         val c = """\r\n             line 1\r\n             <caret>\r\n         """.trimIndent()',
      ),
    );

    test(
      'properly indents inside a multi-line doc comment',
      doTest(
        `
/**
  *    blah
<caret>
  *    blah
  */`,
        `
/**
  *    blah
  *    <caret>
  *    blah
  */`,
      ),
    );

    test(
      'properly indents inside a multi-line string',
      doTest(
        `
         val c = """
             line 1
<caret>
         """.trimIndent()`,
        `
         val c = """
             line 1
             <caret>
         """.trimIndent()`,
      ),
    );
  });

  describe('no completion inside string content', () => {
    test('no completion for ( inside a string', doTest('"(<caret>"', '"(<caret>"'));
  });

  describe('string interpolation', () => {
    test(
      'completes braces for ${...} interpolation',
      doTest('val s = "${<caret>"', 'val s = "${<caret>}"'),
    );

    test(
      'does not duplicate an interpolation closing brace',
      doTest('val s = "${<caret>}"', 'val s = "${<caret>}"'),
    );

    test(
      'completes parentheses inside interpolation expression',
      doTest('val s = "${foo(<caret>}"', 'val s = "${foo(<caret>)}"'),
    );

    test(
      'no completion in string content before interpolation',
      doTest('val s = "a(<caret> ${x}"', 'val s = "a(<caret> ${x}"'),
    );

    test(
      'completes braces in nested interpolation',
      doTest('val s = "${outer("${<caret>")}"', 'val s = "${outer("${<caret>}")}"'),
    );

    test(
      'completes parentheses in deeply nested interpolation expression',
      doTest('val s = "${outer("${inner(<caret>}")}"', 'val s = "${outer("${inner(<caret>)}")}"'),
    );

    test(
      'no completion inside deepest nested string content',
      doTest('val s = "${outer("${"a(<caret>"}")}"', 'val s = "${outer("${"a(<caret>"}")}"'),
    );
  });

  describe('multi-line snippets', () => {
    test(
      'completes parentheses in a multi-line call chain',
      doTest(
        `
val result = foo(
    bar(<caret>
)`,
        `
val result = foo(
    bar(<caret>)
)`,
      ),
    );

    test(
      'completes square brackets in multi-line KDoc content',
      doTest(
        `
/**
 * See [<caret>
 */`,
        `
/**
 * See [<caret>]
 */`,
      ),
    );

    test(
      'completes parentheses in a multi-line interpolation expression',
      doTest(
        `
val s = """
    \${foo(
        bar(<caret>
    )}
""".trimIndent()`,
        `
val s = """
    \${foo(
        bar(<caret>)
    )}
""".trimIndent()`,
      ),
    );

    test(
      'does not rewrite enter inside a lambda body',
      doTest(
        `
listOf(1, 2, 3).map { x ->
<caret>}`,
        `
listOf(1, 2, 3).map { x ->
    <caret>
}`,
      ),
    );

    test(
      'keeps enclosing indentation when expanding an empty lambda body',
      doTest(
        `
fun test() {
    listOf(1, 2, 3).map { x ->
<caret>}
}`,
        `
fun test() {
    listOf(1, 2, 3).map { x ->
        <caret>
    }
}`,
      ),
    );

    test(
      'respects configured two-space indentation when expanding an empty lambda body',
      doTest(
        `
fun test() {
  listOf(1, 2, 3).map { x ->
<caret>}
}`,
        `
fun test() {
  listOf(1, 2, 3).map { x ->
    <caret>
  }
}`,
        '  ',
      ),
    );

    test(
      'properly handles malformed interpolation',
      doTest('val s = "a${}b"\n<caret>', 'val s = "a${}b"\n<caret>'),
    );

    test(
      'properly wraps after malformed interpolation',
      doTest('val s = "a${}\n<caret>b"', 'val s = "a${}" +\n        <caret>"b"'),
    );

    test(
      'properly indents next line',
      doTest(
        `
class A {
  val x = 1
<caret>
}
                `,
        `
class A {
  val x = 1
  <caret>
}
                `,
      ),
    );

    test(
      'properly indents next line when block uses tabs',
      doTest(
        `
class A {
\tval x = 1
<caret>
}
                `,
        `
class A {
\tval x = 1
\t<caret>
}
                `,
      ),
    );

    test(
      'properly indents next line before a closing parenthesis',
      doTest(
        `
fun test() {
  call(
    value,
<caret>
  )
}
                `,
        `
fun test() {
  call(
    value,
        <caret>
  )
}
                `,
      ),
    );

    test(
      'properly indents next line before a closing bracket',
      doTest(
        `
fun test() {
  val y = xs[
<caret>
  ]
}
                `,
        `
fun test() {
  val y = xs[
      <caret>
  ]
}
                `,
      ),
    );

    test(
      'inserts block indent on blank line before next statement',
      doTest(
        `
class A {
  val x = 1
<caret>
  val y = 2
}`,
        `
class A {
  val x = 1
  <caret>
  val y = 2
}`,
      ),
    );

    test(
      'indents after opening parenthesis when next line closes call',
      doTest(
        `
fun test() {
  val result = foo(
<caret>
  )
}
                `,
        `
fun test() {
  val result = foo(
      <caret>
  )
}
                `,
      ),
    );

    test(
      'indents after comma in function call when next line closes call',
      doTest(
        `
fun test() {
  val result = foo(
    first,
<caret>
  )
}
                `,
        `
fun test() {
  val result = foo(
    first,
        <caret>
  )
}
                `,
      ),
    );

    test(
      'indents after arithmetic operator when next line closes parenthesized expression',
      doTest(
        `
fun test() {
  val result = (1 +
<caret>
  )
}
                `,
        `
fun test() {
  val result = (1 +
      <caret>
  )
}
                `,
      ),
    );

    test(
      'inserts block indent on blank line before leading operator on next line',
      doTest(
        `
fun test() {
  val result = 1
<caret>
  + 2
}
                `,
        `
fun test() {
  val result = 1
  <caret>
  + 2
}
                `,
      ),
    );

    test(
      'indents after arithmetic operator when next line starts with an operand',
      doTest(
        `
fun test() {
  val result = 1 +
<caret>
2
}
                `,
        `
fun test() {
  val result = 1 +
      <caret>
2
}
                `,
      ),
    );

    test(
      'inserts block indent on blank line before leading boolean operator on next line',
      doTest(
        `
fun test() {
  val ok = conditionA
<caret>
  && conditionB
}
                `,
        `
fun test() {
  val ok = conditionA
  <caret>
  && conditionB
}
                `,
      ),
    );

    test(
      'indents after boolean operator when next line starts with an operand',
      doTest(
        `
fun test() {
  val ok = conditionA &&
<caret>
conditionB
}
                `,
        `
fun test() {
  val ok = conditionA &&
      <caret>
conditionB
}
                `,
      ),
    );

    test(
      'indents with tabs before a closing brace',
      doTest(
        `
fun test() {
\tif (true) {
\t\tprintln(1)
<caret>
\t}
}
                `,
        `
fun test() {
\tif (true) {
\t\tprintln(1)
\t\t<caret>
\t}
}
                `,
      ),
    );

    test(
      'continues indent after equals for single-expression function body',
      doTest(
        `
fun answer() =
<caret>
42
                `,
        `
fun answer() =
    <caret>
42
                `,
      ),
    );

    test(
      'inserts block indent after statement-ending semicolon, not continuation indent',
      doTest(
        `
fun test() {
  val a = 1;
<caret>
  val b = 2
}
                `,
        `
fun test() {
  val a = 1;
  <caret>
  val b = 2
}
                `,
      ),
    );

    test(
      'continues indent after comma in destructuring before closing parenthesis',
      doTest(
        `
fun test() {
  val (a,
<caret>
  ) = p
}
                `,
        `
fun test() {
  val (a,
      <caret>
  ) = p
}
                `,
      ),
    );

    test(
      'continues indent when splitting if condition before closing parenthesis',
      doTest(
        `
fun test() {
  if (true &&
<caret>
  ) {}
}
                `,
        `
fun test() {
  if (true &&
      <caret>
  ) {}
}
                `,
      ),
    );

    test(
      'continues indent after elvis operator before expression on next line',
      doTest(
        `
fun test() {
  val x = a ?:
<caret>
  b
}
                `,
        `
fun test() {
  val x = a ?:
      <caret>
  b
}
                `,
      ),
    );

    test(
      'properly indents after a multi-line comment',
      doTest(
        `
fun foo() {
  /*
   * comment
   */
<caret>
}`,
        `
fun foo() {
  /*
   * comment
   */
  <caret>
}`,
        '  ',
      ),
    );

    test(
      'properly indents after a multi-line comment',
      doTest(
        `
class C {
  /**
   * comment
   */
<caret>
fun foo() {
}`,
        `
class C {
  /**
   * comment
   */
  <caret>
fun foo() {
}`,
        '  ',
      ),
    );

    test(
      'indents after an argument',
      doTest(
        `
fun foo(a: Int,
<caret>) {}`,
        `
fun foo(a: Int,
        <caret>) {}`,
      ),
    );

    test(
      'indents in a function invocation',
      doTest(
        `
val x = foo(1, 2,
<caret>)`,
        `
val x = foo(1, 2,
    <caret>)`,
      ),
    );

    test(
      'indents in type parameters',
      doTest(
        `
fun foo<T,
<caret>U>() {}`,
        `
fun foo<T,
        <caret>U>() {}`,
      ),
    );

    test(
      'indents in class type parameters',
      doTest(
        `
class Box<
<caret>T: Any, R>`,
        `
class Box<
        <caret>T: Any, R>`,
      ),
    );

    test(
      'indents in type arguments',
      doTest(
        `
val x = foo<T,
<caret>U>()`,
        `
val x = foo<T,
        <caret>U>()`,
      ),
    );

    test(
      'indents in class type arguments',
      doTest(
        `
val box = Box<
<caret>Stirng, String>()`,
        `
val box = Box<
        <caret>Stirng, String>()`,
      ),
    );

    test(
      'indents in class type arguments in type annotation',
      doTest(
        `
val box: Box<
<caret>Stirng, String>()`,
        `
val box: Box<
        <caret>Stirng, String>()`,
      ),
    );

    test(
      'indents in function parameters',
      doTest(
        `
fun foo(
    a: Int,
<caret>)`,
        `
fun foo(
    a: Int,
    <caret>)`,
      ),
    );

    test(
      'indents when Enter is pressed before dot',
      doTest(
        `
val x = a
<caret>.b`,
        `
val x = a
    <caret>.b`,
      ),
    );

    test(
      'indents when Enter is pressed before dot in chained calls',
      doTest(
        `
val x = a
    .b
<caret>.c`,
        `
val x = a
    .b
    <caret>.c`,
      ),
    );

    test(
      'indents in a primary constructor of a data class',
      doTest(
        `
data class Foo(
    val a: Int,
<caret>)`,
        `
data class Foo(
    val a: Int,
    <caret>)`,
      ),
    );

    test(
      'indents when Enter is pressed in function arguments',
      { todo: 'LSP-1104 (no longer properly works without trailing whitespaces)' },
      doTest(
        `
    val items = foo(
        x,
<caret>
    )`,
        `
    val items = foo(
        x,
        <caret>
    )`,
      ),
    );

    test(
      'indents when Enter is pressed after a enum constant',
      doTest(
        `
enum class E {
    A,
<caret>
}`,
        `
enum class E {
    A,
    <caret>
}`,
      ),
    );

    test(
      'handles CRLF when continuing before closing parenthesis',
      doTest(
        `fun test() {\r\n  foo(\r\n    1,\r\n<caret>\r\n  )\r\n}`,
        `fun test() {\r\n  foo(\r\n    1,\r\n        <caret>\r\n  )\r\n}`,
      ),
    );

    test(
      'keeps escaped quote in string literals',
      doTest(`val s = "\\"<caret>"`, `val s = "\\"<caret>"`),
    );

    test(
      'keeps escaped quote in char literals',
      doTest(`val c = '\\'<caret>'`, `val c = '\\'<caret>'`),
    );
  });

  describe('suppresses extra closing symbols', () => {
    test('no extra parenthesis', doTest('val x = (2 + 3)<caret>)', 'val x = (2 + 3)<caret>'));

    test('no extra bracket', doTest('val x = a[0]<caret>]', 'val x = a[0]<caret>'));

    test('no extra curly bracket', doTest('fun f() {}<caret>}', 'fun f() {}<caret>'));

    test('no extra angular bracket', doTest('fun <T><caret>>', 'fun <T><caret>'));
  });
});
