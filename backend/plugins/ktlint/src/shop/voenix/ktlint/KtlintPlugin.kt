package shop.voenix.ktlint

import com.pinterest.ktlint.rule.engine.api.Code
import com.pinterest.ktlint.rule.engine.api.KtLintRuleEngine
import com.pinterest.ktlint.rule.engine.api.LintError
import com.pinterest.ktlint.ruleset.standard.StandardRuleSetProvider
import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.ModuleSources
import org.jetbrains.amper.plugins.TaskAction
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

@TaskAction(executionAvoidance = ExecutionAvoidance.Disabled)
fun runKtlint(
    @Input sources: ModuleSources,
) {
    val sourceFiles =
        sources.sourceDirectories
            .flatMap { sourceDirectory -> sourceDirectory.kotlinFiles() }
            .sortedBy { it.toString() }

    if (sourceFiles.isEmpty()) {
        println("No Kotlin source files found for ktlint.")
        return
    }

    val engine =
        KtLintRuleEngine(
            ruleProviders = StandardRuleSetProvider().getRuleProviders(),
            isInvokedFromCli = true,
        )
    val violations =
        sourceFiles.flatMap { sourceFile ->
            val errors = mutableListOf<LintError>()
            engine.lint(Code.fromPath(sourceFile)) { error ->
                errors += error
            }

            errors.map { error -> KtlintViolation(sourceFile, error) }
        }

    if (violations.isNotEmpty()) {
        error(ktlintFailureMessage(violations))
    }
}

internal fun ktlintFailureMessage(violations: List<KtlintViolation>): String =
    violations.joinToString(
        separator = "\n",
        prefix = "ktlint found ${violations.size} violation(s):\n",
    )

private fun Path.kotlinFiles(): List<Path> {
    if (!Files.exists(this)) {
        return emptyList()
    }

    return Files.walk(this).use { paths ->
        paths
            .filter { path -> path.isRegularFile() }
            .filter { path -> path.extension == "kt" || path.extension == "kts" }
            .toList()
    }
}
