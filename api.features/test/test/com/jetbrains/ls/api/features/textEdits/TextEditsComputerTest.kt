// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.textEdits

import com.jetbrains.ls.test.api.utils.injector.TextEditsApplier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TextEditsComputerTest {
    @Test
    fun singleLineAddModifier() {
        doTest("class Foo {}", "public class Foo {}")
    }

    @Test
    fun replaceFunctionName() {
        doTest("fun foo() {}", "fun bar() {}")
    }

    @Test
    fun multilineAddModifier() {
        doTest(
            """
            |class Foo {
            |  fun foo() {}
            |}""".trimMargin(),
            """
            |public class Foo {
            |  private fun foo() {}
            |}""".trimMargin(),
        )
    }

    @Test
    fun swapFunctions() {
        doTest(
            """
            |fun foo1() {}
            |fun foo2() {}
            |fun foo3() {}
            |""".trimMargin(),
            """
            |fun fo3() {}
            |fun fo2() {}
            |fun fo1() {}
            |""".trimMargin(),
        )
    }


    @Test
    fun addNewLine() {
        doTest(
           "",
           "\n",
        )
    }

    @Test
    fun multipleNewLines() {
        doTest(
            "\n\n\n",
            "\n",
        )
    }

    @Test
    fun emptyTexts() {
        doTest(
            "",
            "",
        )
    }


    @Test
    fun noChanges() {
        val text =  """
            |The sun dipped below the horizon, painting the sky with orange and pink hues.
            |She stood there, barefoot, feeling alive, ready to embrace whatever mysteries the night would bring.
            |""".trimMargin()
        doTest(
            text,
            text,
        )
    }

    @Test
    fun multipleNewLinesWithTextBetween() {
        doTest(
            "\naaa\nbbb\nccc",
            "bbb\ncccc",
        )
    }



    @Test
    fun textsGeneratedByAi() {
        doTest(
            """
            |The sun dipped below the horizon, painting the sky with orange and pink hues.
            |Waves crashed against the shore, whispering secrets to the sand.
            |A cool breeze carried the scent of salt and adventure.
            |She stood there, barefoot, feeling alive, ready to embrace whatever mysteries the night would bring.
            |""".trimMargin(),
            """
            |The old clock ticked steadily, echoing through the silent room.
            |Dust danced in the golden sunlight streaming through the window.
            |A forgotten book lay open on the wooden table, its pages yellowed with time.
            |Outside, birds sang softly, welcoming the morning.
            |Today held promise, yet its secrets remained unknown.
            |""".trimMargin(),
        )
    }




    private fun doTest(text1: String, text2: String) {
        doTestSingleWay(text1, text2)
        doTestSingleWay(text2, text1)
    }

    private fun doTestSingleWay(oldText: String, newText: String) {
        val edits = TextEditsComputer.computeTextEdits(oldText, newText)
        val textAfterApplyingEdits = TextEditsApplier.applyTextEdits(oldText, edits)
        assertEquals(
            newText,
            textAfterApplyingEdits,
            "Text edits: $edits",
        )
    }
}