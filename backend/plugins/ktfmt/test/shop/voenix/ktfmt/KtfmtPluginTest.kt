package shop.voenix.ktfmt

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.io.path.createDirectory
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KtfmtPluginTest {
    @Test
    fun `unformatted Kotlin is found and formatted canonically`() {
        val sourceFile = createTempDirectory().resolve("Example.kt")
        sourceFile.writeText("class Example{fun answer()=42}\n")

        assertEquals(listOf(sourceFile), findNonCanonicalKotlinFiles(listOf(sourceFile.parent)))

        formatKotlinFiles(listOf(sourceFile.parent))

        assertEquals(
            """
            class Example {
                fun answer() = 42
            }
            """
                .trimIndent() + "\n",
            sourceFile.readText(),
        )
        assertEquals(emptyList(), findNonCanonicalKotlinFiles(listOf(sourceFile.parent)))
    }

    @Test
    fun `second formatting run does not rewrite a canonical file`() {
        val sourceFile = createTempDirectory().resolve("Example.kt")
        sourceFile.writeText("class Example{fun answer()=42}\n")
        formatKotlinFiles(listOf(sourceFile.parent))
        val preservedTimestamp = FileTime.fromMillis(1_234_000)
        Files.setLastModifiedTime(sourceFile, preservedTimestamp)

        formatKotlinFiles(listOf(sourceFile.parent))

        assertEquals(preservedTimestamp, Files.getLastModifiedTime(sourceFile))
    }

    @Test
    fun `one formatting command reaches the ktfmt fixed point`() {
        val sourceFile = createTempDirectory().resolve("SomeClass.kt")
        sourceFile.writeText(
            """
            class SomeClass {
                private fun someFunction() {
                    ListAssert(blah)
                        .blahBlahBlah(
                            "abcdefghijklmnopqurstuvw",
                            "alsdkf jlasjf lkasjdlsadfjl" // A long comment that gets wrapped and then reformatted again after running ktfmt twice, what!!!!
                        ).isEqualTo(someOtherThing)
                }
            }
            """
                .trimIndent() + "\n"
        )

        formatKotlinFiles(listOf(sourceFile.parent))

        assertEquals(emptyList(), findNonCanonicalKotlinFiles(listOf(sourceFile.parent)))
    }

    @Test
    fun `parse error leaves every source file unchanged`() {
        val sourceRoot = createTempDirectory()
        val validFile = sourceRoot.resolve("Valid.kt")
        val invalidFile = sourceRoot.resolve("Invalid.kt")
        val originalValidSource = "class Valid{fun answer()=42}\n"
        validFile.writeText(originalValidSource)
        invalidFile.writeText("fun broken(\n")

        val failure =
            assertFailsWith<IllegalStateException> { formatKotlinFiles(listOf(sourceRoot)) }

        assertTrue(failure.message.orEmpty().contains(invalidFile.toString()))
        assertTrue(failure.message.orEmpty().contains("no files were changed"))
        assertEquals(originalValidSource, validFile.readText())
    }

    @Test
    fun `unused imports are preserved`() {
        val sourceFile = createTempDirectory().resolve("Example.kt")
        sourceFile.writeText(
            """
            package example
            import kotlin.time.Duration
            class Example
            """
                .trimIndent() + "\n"
        )

        formatKotlinFiles(listOf(sourceFile.parent))

        assertTrue(sourceFile.readText().contains("import kotlin.time.Duration"))
    }

    @Test
    fun `Kotlin scripts are formatted`() {
        val sourceFile = createTempDirectory().resolve("example.kts")
        sourceFile.writeText("val message=\"hello\"\n")

        formatKotlinFiles(listOf(sourceFile.parent))

        assertEquals("val message = \"hello\"\n", sourceFile.readText())
    }

    @Test
    fun `Kotlin 2_4 context parameters can be formatted`() {
        val sourceFile = createTempDirectory().resolve("ContextParameter.kt")
        sourceFile.writeText(
            """
            class Logger
            context(logger: Logger)
            fun logMessage(){ println(logger) }
            """
                .trimIndent() + "\n"
        )

        formatKotlinFiles(listOf(sourceFile.parent))

        assertTrue(sourceFile.readText().contains("context(logger: Logger)"))
        assertEquals(emptyList(), findNonCanonicalKotlinFiles(listOf(sourceFile.parent)))
    }

    @Test
    fun `source discovery ignores non Kotlin files`() {
        val sourceRoot = createTempDirectory()
        val nestedRoot = sourceRoot.resolve("nested").createDirectory()
        val kotlinFile = nestedRoot.resolve("Example.kt")
        val scriptFile = sourceRoot.resolve("build.kts")
        kotlinFile.writeText("class Example\n")
        scriptFile.writeText("val example = 1\n")
        sourceRoot.resolve("README.md").writeText("not Kotlin")

        assertEquals(listOf(scriptFile, kotlinFile), kotlinSourceFiles(listOf(sourceRoot)))
    }
}
