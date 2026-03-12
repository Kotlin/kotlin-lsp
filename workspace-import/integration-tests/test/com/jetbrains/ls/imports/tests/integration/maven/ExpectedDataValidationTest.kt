// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.tests.integration.maven

import com.intellij.ide.starter.extended.allure.DoNotReportToAllure
import com.intellij.platform.testFramework.core.FileComparisonFailedError
import com.intellij.workspaceModel.performanceTesting.helpers.JsonSerializer
import com.jetbrains.ls.imports.tests.integration.LspTestData
import com.jetbrains.ls.imports.tests.integration.normalize
import com.jetbrains.ls.imports.tests.integration.withIgnoringNonClassesRoots
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.io.path.readText


@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ParameterizedTest(name = "{0}")
@EnumSource(LspTestData::class)
private annotation class LspExpectedWorkspaceDataTest

@DoNotReportToAllure
class ExpectedDataValidationTest {

    @LspExpectedWorkspaceDataTest
    fun `expected json file should be pretty`(testData: LspTestData) {
        val expected = testData.getFile()!!.readText()
        val actual = JsonSerializer.serializePretty(normalize(testData.getStructure().modules))
        if (expected != actual) {
            throw FileComparisonFailedError(
                "Json Should be pretty printed", expected, actual,
                testData.getFile()!!.toString(),
                null
            )
        }
    }


    @LspExpectedWorkspaceDataTest
    fun `expected json file should not contain JAVADOC and SOURCES libraries`(testData: LspTestData) {
        val expected = testData.getFile()!!.readText()
        val modules = withIgnoringNonClassesRoots(normalize(testData.getStructure().modules))
        val actual = JsonSerializer.serializePretty(modules)
        if (expected != actual) {
            throw FileComparisonFailedError(
                "Json Should not contain JAVADOC and SOURCES", expected, actual,
                testData.getFile()!!.toString(),
                null
            )
        }
    }
}
