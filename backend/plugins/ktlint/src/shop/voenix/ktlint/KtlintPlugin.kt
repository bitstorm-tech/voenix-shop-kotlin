package shop.voenix.ktlint

import com.pinterest.ktlint.rule.engine.api.Code
import com.pinterest.ktlint.rule.engine.api.KtLintRuleEngine
import com.pinterest.ktlint.rule.engine.api.LintError
import com.pinterest.ktlint.ruleset.standard.StandardRuleSetProvider
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.ModuleSources
import org.jetbrains.amper.plugins.TaskAction

@TaskAction(executionAvoidance = ExecutionAvoidance.Disabled)
fun runKtlint(
    @Input sources: ModuleSources,
    @Input additionalSourceRoots: List<Path>,
    @Input pluginSourceRoot: Path,
    includePluginSources: Boolean,
) {
    val sourceRoots =
        sources.sourceDirectories +
            additionalSourceRoots +
            listOfNotNull(pluginSourceRoot.takeIf { includePluginSources })
    val sourceFiles = kotlinSourceFiles(sourceRoots)

    if (sourceFiles.isEmpty()) {
        println("No Kotlin source files found for ktlint.")
        return
    }

    val engine =
        KtLintRuleEngine(
            ruleProviders = StandardRuleSetProvider().getRuleProviders(),
            isInvokedFromCli = true,
        )
    val violations = sourceFiles.flatMap { sourceFile -> lintSourceFile(sourceFile, engine) }

    if (violations.isNotEmpty()) {
        error(ktlintFailureMessage(violations))
    }
}

internal fun kotlinSourceFiles(sourceRoots: List<Path>): List<Path> =
    sourceRoots
        .distinct()
        .flatMap { sourceRoot -> sourceRoot.kotlinFiles() }
        .distinct()
        .sortedBy { sourceFile -> sourceFile.toString() }

internal fun ktlintFailureMessage(violations: List<KtlintViolation>): String =
    violations.joinToString(
        separator = "\n",
        prefix = "ktlint found ${violations.size} violation(s):\n",
    )

internal fun lintSourceFile(
    sourceFile: Path,
    engine: KtLintRuleEngine,
): List<KtlintViolation> {
    val errors = mutableListOf<LintError>()
    engine.lint(Code.fromFile(sourceFile.toFile())) { error -> errors += error }

    return errors.map { error -> KtlintViolation(sourceFile, error) }
}

private fun Path.kotlinFiles(): List<Path> {
    if (!Files.exists(this)) {
        return emptyList()
    }
    if (isRegularFile()) {
        return if (extension == "kt" || extension == "kts") listOf(this) else emptyList()
    }

    return Files.walk(this).use { paths ->
        paths
            .filter { path -> path.isRegularFile() }
            .filter { path -> path.extension == "kt" || path.extension == "kts" }
            .toList()
    }
}
