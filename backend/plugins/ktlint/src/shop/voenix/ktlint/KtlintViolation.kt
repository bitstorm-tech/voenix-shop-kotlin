package shop.voenix.ktlint

import com.pinterest.ktlint.rule.engine.api.LintError
import java.nio.file.Path

internal data class KtlintViolation(
    val sourceFile: Path,
    val error: LintError,
) {
    override fun toString(): String =
        "$sourceFile:${error.line}:${error.col}: ${error.detail} (${error.ruleId.value})"
}
