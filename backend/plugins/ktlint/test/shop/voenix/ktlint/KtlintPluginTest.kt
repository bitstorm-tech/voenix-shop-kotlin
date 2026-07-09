package shop.voenix.ktlint

import com.pinterest.ktlint.rule.engine.api.LintError
import com.pinterest.ktlint.rule.engine.core.api.RuleId
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class KtlintPluginTest {
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
                ),
            )

        assertEquals(
            "ktlint found 1 violation(s):\nsrc/Example.kt:3:9: File must end with a newline (\\n) (standard:final-newline)",
            ktlintFailureMessage(violations),
        )
    }
}
