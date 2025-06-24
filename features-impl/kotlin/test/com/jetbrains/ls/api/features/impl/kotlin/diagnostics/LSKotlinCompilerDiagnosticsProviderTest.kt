// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.diagnostics

import com.intellij.mock.MockMultiLineImmutableDocument
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.impl.common.kotlin.diagnostics.compiler.toLsp
import com.jetbrains.lsp.protocol.DiagnosticSeverity
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.diagnostics.KaDiagnosticWithPsi
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class LSKotlinCompilerDiagnosticsProviderTest {

    @Test
    fun `should enhance diagnostic message with factory name`() {
        // Given
        val document = MockMultiLineImmutableDocument("val x: Int = \"test\"")
        val file: VirtualFile = LightVirtualFile("test.kt", "val x: Int = \"test\"")
        
        val mockDiagnostic = mock(KaDiagnosticWithPsi::class.java)
        `when`(mockDiagnostic.defaultMessage).thenReturn("Type mismatch")
        `when`(mockDiagnostic.factoryName).thenReturn("TYPE_MISMATCH")
        `when`(mockDiagnostic.severity).thenReturn(KaSeverity.ERROR)
        `when`(mockDiagnostic.textRanges).thenReturn(listOf(com.intellij.openapi.util.TextRange(13, 19)))
        
        val mockKaSession = mock(KaSession::class.java)
        val mockAnalysisContext = mock(LSAnalysisContext::class.java)
        val mockServer = mock(LSServer::class.java)
        
        // When
        val diagnostics = with(mockKaSession) {
            with(mockAnalysisContext) {
                with(mockServer) {
                    mockDiagnostic.toLsp(document, file)
                }
            }
        }
        
        // Then
        assertEquals(1, diagnostics.size)
        val diagnostic = diagnostics[0]
        assertEquals("Type mismatch (TYPE_MISMATCH)", diagnostic.message)
        assertEquals(DiagnosticSeverity.Error, diagnostic.severity)
        assertEquals("TYPE_MISMATCH", diagnostic.code?.stringValue)
        assertEquals("Kotlin", diagnostic.source)
    }

    @Test
    fun `should not duplicate factory name if already in message`() {
        // Given
        val document = MockMultiLineImmutableDocument("val x: Int = \"test\"")
        val file: VirtualFile = LightVirtualFile("test.kt", "val x: Int = \"test\"")
        
        val mockDiagnostic = mock(KaDiagnosticWithPsi::class.java)
        `when`(mockDiagnostic.defaultMessage).thenReturn("Type mismatch: TYPE_MISMATCH detected")
        `when`(mockDiagnostic.factoryName).thenReturn("TYPE_MISMATCH")
        `when`(mockDiagnostic.severity).thenReturn(KaSeverity.WARNING)
        `when`(mockDiagnostic.textRanges).thenReturn(listOf(com.intellij.openapi.util.TextRange(13, 19)))
        
        val mockKaSession = mock(KaSession::class.java)
        val mockAnalysisContext = mock(LSAnalysisContext::class.java)
        val mockServer = mock(LSServer::class.java)
        
        // When
        val diagnostics = with(mockKaSession) {
            with(mockAnalysisContext) {
                with(mockServer) {
                    mockDiagnostic.toLsp(document, file)
                }
            }
        }
        
        // Then
        assertEquals(1, diagnostics.size)
        val diagnostic = diagnostics[0]
        assertEquals("Type mismatch: TYPE_MISMATCH detected", diagnostic.message)
        // Should not append factory name again if it's already in the message
        assertTrue(!diagnostic.message.endsWith("(TYPE_MISMATCH)"))
    }

    @Test
    fun `should handle empty factory name gracefully`() {
        // Given
        val document = MockMultiLineImmutableDocument("val x: Int = \"test\"")
        val file: VirtualFile = LightVirtualFile("test.kt", "val x: Int = \"test\"")
        
        val mockDiagnostic = mock(KaDiagnosticWithPsi::class.java)
        `when`(mockDiagnostic.defaultMessage).thenReturn("Generic error")
        `when`(mockDiagnostic.factoryName).thenReturn("")
        `when`(mockDiagnostic.severity).thenReturn(KaSeverity.INFO)
        `when`(mockDiagnostic.textRanges).thenReturn(listOf(com.intellij.openapi.util.TextRange(0, 5)))
        
        val mockKaSession = mock(KaSession::class.java)
        val mockAnalysisContext = mock(LSAnalysisContext::class.java)
        val mockServer = mock(LSServer::class.java)
        
        // When
        val diagnostics = with(mockKaSession) {
            with(mockAnalysisContext) {
                with(mockServer) {
                    mockDiagnostic.toLsp(document, file)
                }
            }
        }
        
        // Then
        assertEquals(1, diagnostics.size)
        val diagnostic = diagnostics[0]
        assertEquals("Generic error", diagnostic.message)
        assertEquals(DiagnosticSeverity.Information, diagnostic.severity)
    }
} 