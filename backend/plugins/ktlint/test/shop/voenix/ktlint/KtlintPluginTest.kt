package shop.voenix.ktlint

import com.pinterest.ktlint.rule.engine.api.KtLintRuleEngine
import com.pinterest.ktlint.rule.engine.api.LintError
import com.pinterest.ktlint.rule.engine.core.api.RuleId
import com.pinterest.ktlint.ruleset.standard.StandardRuleSetProvider
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class KtlintPluginTest {
    @Test
    fun `source discovery includes Kotlin production test and plugin files`() {
        val moduleRoot = createTempDirectory()
        val productionRoot = moduleRoot.resolve("src").createDirectory()
        val testRoot = moduleRoot.resolve("test").createDirectory()
        val pluginRoot = moduleRoot.resolve("plugins").createDirectory()
        val productionFile = productionRoot.resolve("Production.kt")
        val testFile = testRoot.resolve("ProductionTest.kt")
        val pluginFile = pluginRoot.resolve("quality.kts")
        productionFile.writeText("class Production\n")
        testFile.writeText("class ProductionTest\n")
        pluginFile.writeText("val quality = true\n")
        moduleRoot.resolve("README.md").writeText("not Kotlin")

        assertEquals(
            listOf(pluginFile, productionFile, testFile),
            kotlinSourceFiles(listOf(productionRoot, testRoot, pluginRoot)),
        )
    }

    @Test
    fun `lint source file preserves trailing newline`() {
        val sourceFile = createTempDirectory().resolve("Example.kt")
        sourceFile.writeText("class Example\n")

        val violations =
            lintSourceFile(
                sourceFile,
                KtLintRuleEngine(
                    ruleProviders = StandardRuleSetProvider().getRuleProviders(),
                    isInvokedFromCli = true,
                ),
            )

        assertEquals(emptyList(), violations)
    }

    @Test
    fun `failure message includes violation details`() {
        val violations =
            listOf(
                KtlintViolation(
                    sourceFile = Path.of("src/Example.kt"),
                    error =
                        LintError(
                            3,
                            9,
                            RuleId("standard:final-newline"),
                            "File must end with a newline (\\n)",
                            false,
                        ),
                )
            )

        assertEquals(
            "ktlint found 1 violation(s):\nsrc/Example.kt:3:9: File must end with a newline (\\n) (standard:final-newline)",
            ktlintFailureMessage(violations),
        )
    }
}
