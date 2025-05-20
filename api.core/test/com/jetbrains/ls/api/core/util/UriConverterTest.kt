package com.jetbrains.ls.api.core.util

import com.intellij.openapi.util.SystemInfoRt
import com.jetbrains.lsp.protocol.URI
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

class UriConverterTest {
    @Test
    fun `should convert LSP URI to IntelliJ URI for Unix paths`() {
        onlyOnUnix {
            testLspToIntellij(
                "file:///home/user/test.txt",
                "file:///home/user/test.txt"
            )

            testLspToIntellij(
                "file:///usr/local/bin/app",
                "file:///usr/local/bin/app"
            )
        }
    }

    @Test
    fun `should convert LSP URI to IntelliJ URI for Windows paths`() {
        onlyOnWindows {
            testLspToIntellij(
                "file://c:/Users/test/file.txt",
                "file:///C:/Users/test/file.txt"
            )

            testLspToIntellij(
                "file://d:/Program Files/App/config.xml",
                "file:///D:/Program%20Files/App/config.xml"
            )
        }
    }

    @Test
    fun `should convert LSP URI to IntelliJ URI for JAR files`() {
        onlyOnWindows {
            testLspToIntellij(
                "jar://c:/libs/app.jar!/",
                "file:///C:/libs/app.jar"
            )
        }

        onlyOnUnix {
            testLspToIntellij(
                "jar:///path/to/lib.jar!/",
                "file:///path/to/lib.jar"
            )
        }

        onlyOnWindows {
            // Platform-independent JAR tests
            testLspToIntellij(
                "jar://d:/path/to/lib.jar!/com/example/Class.class",
                "jar:///D:/path/to/lib.jar!/com/example/Class.class"
            )
        }
        onlyOnUnix {
            // Platform-independent JAR tests
            testLspToIntellij(
                "jar:///path/to/lib.jar!/com/example/Class.class",
                "jar:///path/to/lib.jar!/com/example/Class.class"
            )
        }
    }

    @Test
    fun `should convert LSP URI to IntelliJ URI with special characters`() {
        onlyOnUnix {
            testLspToIntellij(
                "file:///path/with spaces/file.txt",
                "file:///path/with%20spaces/file.txt"
            )

            testLspToIntellij(
                "file:///path/with#hash/file.txt",
                "file:///path/with%23hash/file.txt"
            )
        }
    }

    @Test
    fun `should convert IntelliJ URI to LSP URI for Unix paths`() {
        onlyOnUnix {
            testIntellijToLsp(
                "file:///home/user/test.txt",
                "file:///home/user/test.txt"
            )

            testIntellijToLsp(
                "file:///usr/local/bin/app",
                "file:///usr/local/bin/app"
            )
        }
    }

    @Test
    fun `should convert IntelliJ URI to LSP URI for Windows paths`() {
        onlyOnWindows {
            testIntellijToLsp(
                "file:///c%3A/Users/test/file.txt",
                "file://c:/Users/test/file.txt"
            )

            testIntellijToLsp(
                "file:///d%3A/Program%20Files/App/config.xml",
                "file://d:/Program Files/App/config.xml"
            )
        }
    }

    @Test
    fun `should convert IntelliJ URI to LSP URI for JAR files`() {
        onlyOnWindows {
            testIntellijToLsp(
                "jar:///c%3A/libs/app.jar!/com/example/Main.class",
                "jar://C:/libs/app.jar!/com/example/Main.class"
            )
        }
        onlyOnUnix {
            testIntellijToLsp(
                "jar:///path/to/lib.jar!/com/example/Class.class",
                "jar:///path/to/lib.jar!/com/example/Class.class"
            )
        }
    }

    @Test
    fun `should convert IntelliJ URI to LSP URI with special characters`() {
        onlyOnUnix {
            testIntellijToLsp(
                "file:///path/with%20spaces/file.txt",
                "file:///path/with spaces/file.txt"
            )

            testIntellijToLsp(
                "file:///path/with%23hash/file.txt",
                "file:///path/with#hash/file.txt"
            )
        }

        onlyOnWindows {
            testIntellijToLsp(
                "file:///c%3A/path/with%20spaces/file.txt",
                "file://c:/path/with spaces/file.txt"
            )

            testIntellijToLsp(
                "file:///c%3A/path/with%23hash/file.txt",
                "file://c:/path/with#hash/file.txt"
            )
        }
    }

    @Test
    fun `should convert Path to LSP URI correctly`() {
        onlyOnWindows {
            val windowsPath = Path.of("c:\\test\\path.txt")
            testLspToIntellij(
                "file://c:/test/path.txt",
                windowsPath.toLspUri().uri
            )
        }

        onlyOnUnix {
            val unixPath = Path.of("/test/path.txt")
            testLspToIntellij(
                "file:///test/path.txt",
                unixPath.toLspUri().uri,
            )
        }
    }

    @Test
    fun `should handle empty paths correctly`() {
        onlyOnUnix {
            testLspToIntellij(
                "file:///",
                "file:///"
            )
            testIntellijToLsp(
                "file:///",
                "file:///"
            )
        }

        onlyOnWindows {
            testLspToIntellij(
                "file:///",
                "file:///"
            )
            testIntellijToLsp(
                "file:///",
                "file:///"
            )
        }
    }

    @Test
    fun `should handle special characters in paths`() {
        onlyOnUnix {
            testLspToIntellij(
                "file:///path/with@symbol/file.txt",
                "file:///path/with%40symbol/file.txt"
            )

            testLspToIntellij(
                "file:///path/with+plus/file.txt",
                "file:///path/with%2Bplus/file.txt"
            )

            testLspToIntellij(
                "file:///path/with,comma/file.txt",
                "file:///path/with%2Ccomma/file.txt"
            )
        }

        onlyOnWindows {
            testLspToIntellij(
                "file://c:/path/with@symbol/file.txt",
                "file:///c:/path/with%40symbol/file.txt"
            )

            testLspToIntellij(
                "file://c:/path/with+plus/file.txt",
                "file:///c:/path/with%2Bplus/file.txt"
            )

            testLspToIntellij(
                "file://c:/path/with,comma/file.txt",
                "file:///c:/path/with%2Ccomma/file.txt"
            )
        }
    }


    @Test
    fun `should handle extremely long paths`() {
        onlyOnUnix {
            val longPath = "a".repeat(255) // Max filename length in many filesystems
            testLspToIntellij(
                "file:///path/$longPath.txt",
                "file:///path/$longPath.txt"
            )
        }

        onlyOnWindows {
            val longPath = "a".repeat(255) // Max filename length in many filesystems
            testLspToIntellij(
                "file://c:/path/$longPath.txt",
                "file:///c:/path/$longPath.txt"
            )
        }
    }

    @Test
    fun `should convert Windows drive letter to uppercase`() {
        onlyOnWindows {
            testLspToIntellij(
                "file://c:/Users/test.txt",
                "file:///c:/Users/test.txt"
            )

            testLspToIntellij(
                "file://d:/Program Files/test.txt",
                "file:///d:/Program%20Files/test.txt"
            )

            testLspToIntellij(
                "jar://e:/libs/app.jar!/",
                "file:///e:/libs/app.jar"
            )
        }
    }


    @Test
    fun `should handle non-ASCII characters in paths`() {
        onlyOnUnix {
            testLspToIntellij(
                "file:///path/привет/файл.txt",
                "file:///path/%D0%BF%D1%80%D0%B8%D0%B2%D0%B5%D1%82/%D1%84%D0%B0%D0%B9%D0%BB.txt"
            )

            testLspToIntellij(
                "file:///path/测试/文件.txt",
                "file:///path/%E6%B5%8B%E8%AF%95/%E6%96%87%E4%BB%B6.txt"
            )
        }

        onlyOnWindows {
            testLspToIntellij(
                "file://c:/path/привет/файл.txt",
                "file:///C:/path/%D0%BF%D1%80%D0%B8%D0%B2%D0%B5%D1%82/%D1%84%D0%B0%D0%B9%D0%BB.txt"
            )

            testLspToIntellij(
                "file://c:/path/测试/文件.txt",
                "file:///C:/path/%E6%B5%8B%E8%AF%95/%E6%96%87%E4%BB%B6.txt"
            )
        }
    }

    private fun testLspToIntellij(
       expectedIntellijUri: String,
       lspUri: String
    ) {
        val actual = UriConverter.lspUriToIntellijUri(lspUri)
        assertEquals(expectedIntellijUri, actual)

        testContractForLspUri(lspUri)
        testContractForIntelliJUri(expectedIntellijUri)
    }
    
    private fun testIntellijToLsp(
        expectedLspUri: String,
        intellijUri: String
    ) {
        val actual = UriConverter.intellijUriToLspUri(intellijUri)
        assertEquals(expectedLspUri, actual)

        testContractForLspUri(expectedLspUri)
        testContractForIntelliJUri(intellijUri)
    }

    private fun testContractForLspUri(
        lspUri: String
    ) {
        val intellij1 = UriConverter.lspUriToIntellijUri(lspUri)

        val lsp2: String = UriConverter.intellijUriToLspUri(intellij1)
        val intellij2 = UriConverter.lspUriToIntellijUri(lsp2)


        val lsp3 = UriConverter.intellijUriToLspUri(intellij2)

        assertEquals(lsp2, lsp3)
        assertEquals(intellij1, intellij2)
    }

    private fun testContractForIntelliJUri(
        intellijUri: String
    ) {
        val lsp1: String = UriConverter.intellijUriToLspUri(intellijUri)

        val intellij2 = UriConverter.lspUriToIntellijUri(lsp1)
        val lsp2 = UriConverter.intellijUriToLspUri(intellij2)

        val intellij3 = UriConverter.lspUriToIntellijUri(lsp2)

        assertEquals(lsp1, lsp2)
        assertEquals(intellij2, intellij3)
    }

    private fun onlyOnWindows(test: () -> Unit) {
        if (SystemInfoRt.isWindows) {
            test()
        }
    }

    private fun onlyOnUnix(test: () -> Unit) {
        if (!SystemInfoRt.isWindows) {
            test()
        }
    }
}
