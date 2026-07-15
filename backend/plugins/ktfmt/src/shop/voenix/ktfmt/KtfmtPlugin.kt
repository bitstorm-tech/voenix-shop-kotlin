package shop.voenix.ktfmt

import com.facebook.ktfmt.format.Formatter
import com.facebook.ktfmt.format.ParseError
import com.google.googlejavaformat.java.FormatterException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.ModuleSources
import org.jetbrains.amper.plugins.TaskAction

@TaskAction(executionAvoidance = ExecutionAvoidance.Disabled)
fun checkKtfmt(
    @Input sources: ModuleSources,
    @Input additionalSourceRoots: List<Path>,
    @Input pluginSourceRoot: Path,
    includePluginSources: Boolean,
) {
    val sourceRoots =
        sourceRoots(sources, additionalSourceRoots, pluginSourceRoot, includePluginSources)
    val nonCanonicalFiles = findNonCanonicalKotlinFiles(sourceRoots)

    if (nonCanonicalFiles.isNotEmpty()) {
        error(ktfmtFailureMessage(nonCanonicalFiles))
    }
}

@TaskAction(executionAvoidance = ExecutionAvoidance.Disabled)
fun formatKtfmt(
    @Input sources: ModuleSources,
    @Input additionalSourceRoots: List<Path>,
    @Input pluginSourceRoot: Path,
    includePluginSources: Boolean,
) {
    val sourceRoots =
        sourceRoots(sources, additionalSourceRoots, pluginSourceRoot, includePluginSources)
    val formattedFiles = formatKotlinFiles(sourceRoots)

    if (formattedFiles.isEmpty()) {
        println("All Kotlin source files already use canonical ktfmt formatting.")
    } else {
        println("ktfmt formatted ${formattedFiles.size} Kotlin source file(s).")
    }
}

private fun sourceRoots(
    sources: ModuleSources,
    additionalSourceRoots: List<Path>,
    pluginSourceRoot: Path,
    includePluginSources: Boolean,
): List<Path> =
    sources.sourceDirectories +
        additionalSourceRoots +
        listOfNotNull(pluginSourceRoot.takeIf { includePluginSources })

internal fun findNonCanonicalKotlinFiles(sourceRoots: List<Path>): List<Path> =
    canonicalSources(sourceRoots)
        .filter { (sourceFile, canonicalSource) -> sourceFile.readText() != canonicalSource }
        .keys
        .toList()

internal fun formatKotlinFiles(sourceRoots: List<Path>): List<Path> {
    val canonicalSources = canonicalSources(sourceRoots)
    val changedSources = canonicalSources.filter { (sourceFile, canonicalSource) ->
        sourceFile.readText() != canonicalSource
    }

    changedSources.forEach { (sourceFile, canonicalSource) ->
        sourceFile.writeText(canonicalSource)
    }

    return changedSources.keys.toList()
}

internal fun kotlinSourceFiles(sourceRoots: List<Path>): List<Path> =
    sourceRoots
        .distinct()
        .flatMap { sourceRoot -> sourceRoot.kotlinFiles() }
        .distinct()
        .sortedBy { sourceFile -> sourceFile.toString() }

internal fun ktfmtFailureMessage(nonCanonicalFiles: List<Path>): String =
    nonCanonicalFiles.joinToString(
        separator = "\n",
        prefix = "ktfmt found ${nonCanonicalFiles.size} non-canonical Kotlin source file(s):\n",
        postfix = "\nRun './kotlin do ktfmt' from backend/ to repair them.",
    ) { sourceFile ->
        "- $sourceFile"
    }

private fun canonicalSources(sourceRoots: List<Path>): Map<Path, String> =
    kotlinSourceFiles(sourceRoots).associateWith { sourceFile -> stableKtfmtFormat(sourceFile) }

/*
 * ktfmt 0.64 can require a second pass for wrapped comments. Computing the fixed point in memory
 * keeps the public formatting command deterministic while preserving all-or-nothing parse safety.
 */
private fun stableKtfmtFormat(sourceFile: Path): String {
    var currentSource = sourceFile.readText()
    val seenSources = mutableSetOf(currentSource)

    repeat(MAX_FORMAT_PASSES) {
        val formattedSource =
            try {
                Formatter.format(KTFMT_OPTIONS, currentSource)
            } catch (exception: ParseError) {
                throw ktfmtParseFailure(sourceFile, exception)
            } catch (exception: FormatterException) {
                throw ktfmtParseFailure(sourceFile, exception)
            }
        if (formattedSource == currentSource) {
            return formattedSource
        }
        check(seenSources.add(formattedSource)) {
            "ktfmt formatting entered a cycle for $sourceFile; no files were changed."
        }
        currentSource = formattedSource
    }

    error(
        "ktfmt formatting did not stabilize after $MAX_FORMAT_PASSES passes for $sourceFile; " +
            "no files were changed."
    )
}

private fun ktfmtParseFailure(
    sourceFile: Path,
    cause: Exception,
): IllegalStateException =
    IllegalStateException(
        "ktfmt could not parse $sourceFile; no files were changed.",
        cause,
    )

private const val MAX_FORMAT_PASSES = 10

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

private val KTFMT_OPTIONS =
    Formatter.KOTLINLANG_FORMAT.toBuilder()
        .maxWidth(100)
        .blockIndent(4)
        .continuationIndent(4)
        .removeUnusedImports(false)
        .preserveLambdaBreaks(false)
        .build()
