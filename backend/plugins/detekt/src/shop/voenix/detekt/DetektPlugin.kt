package shop.voenix.detekt

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.amper.plugins.Classpath
import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.ModuleSources
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction

@TaskAction(executionAvoidance = ExecutionAvoidance.Disabled)
fun runDetekt(
    @Input mainSources: ModuleSources,
    @Input additionalSourceRoots: List<Path>,
    @Input projectClasspath: Classpath,
    @Input detektClasspath: Classpath,
    @Input(inferTaskDependency = false) configFile: Path,
    @Output reportDirectory: Path,
    projectRoot: String,
) {
    check(Files.isRegularFile(configFile)) {
        "Detekt configuration file does not exist: $configFile"
    }
    Files.createDirectories(reportDirectory)

    runDetektAnalysis(
        label = "main",
        sourceRoots = mainSources.sourceDirectories.existingDirectories(),
        analysisMode = "full",
        projectClasspath = projectClasspath.resolvedFiles,
        detektClasspath = detektClasspath.resolvedFiles,
        configFile = configFile,
        reportDirectory = reportDirectory,
        projectRoot = Path.of(projectRoot),
    )
    runDetektAnalysis(
        label = "tests and plugins",
        sourceRoots = additionalSourceRoots.existingDirectories(),
        analysisMode = "light",
        projectClasspath = emptyList(),
        detektClasspath = detektClasspath.resolvedFiles,
        configFile = configFile,
        reportDirectory = reportDirectory,
        projectRoot = Path.of(projectRoot),
        reportName = "additional",
    )
}

internal fun buildDetektArguments(
    sourceRoots: List<Path>,
    analysisMode: String,
    projectClasspath: List<Path>,
    configFile: Path,
    reportDirectory: Path,
    reportName: String,
    projectRoot: Path,
    javaHome: Path,
): List<String> = buildList {
    add("--input")
    add(sourceRoots.joinToString(File.pathSeparator))
    add("--analysis-mode")
    add(analysisMode)
    if (projectClasspath.isNotEmpty()) {
        add("--classpath")
        add(projectClasspath.joinToString(File.pathSeparator))
    }
    add("--language-version")
    add("2.4")
    add("--api-version")
    add("2.4")
    add("--jvm-target")
    add("17")
    add("--jdk-home")
    add(javaHome.toString())
    add("--config")
    add(configFile.toString())
    add("--build-upon-default-config")
    add("--fail-on-severity")
    add("Warning")
    add("--base-path")
    add(projectRoot.toString())
    add("--report")
    add("sarif:${reportDirectory.resolve("$reportName.sarif")}")
    add("--report")
    // Detekt 2.0.0-alpha.5 documents "md", but its registered report ID is "markdown".
    add("markdown:${reportDirectory.resolve("$reportName.md")}")
}

internal fun buildDetektCommand(
    javaExecutable: String,
    detektClasspath: List<Path>,
    arguments: List<String>,
): List<String> = buildList {
    add(javaExecutable)
    add("-cp")
    add(detektClasspath.joinToString(File.pathSeparator))
    add(DETEKT_MAIN_CLASS)
    addAll(arguments)
}

internal fun detektFailureMessage(
    exitCode: Int,
    analysisLabel: String,
): String? =
    when (exitCode) {
        0 -> null
        1 -> "Detekt $analysisLabel analysis stopped because of an unexpected tool error."
        2 -> "Detekt $analysisLabel analysis found at least one mandatory code finding."
        DETEKT_INVALID_CONFIG_EXIT_CODE ->
            "Detekt $analysisLabel analysis rejected the invalid configuration file."
        else -> "Detekt $analysisLabel analysis stopped with unknown exit code $exitCode."
    }

private fun runDetektAnalysis(
    label: String,
    sourceRoots: List<Path>,
    analysisMode: String,
    projectClasspath: List<Path>,
    detektClasspath: List<Path>,
    configFile: Path,
    reportDirectory: Path,
    projectRoot: Path,
    reportName: String = label,
) {
    if (sourceRoots.isEmpty()) {
        println("No Kotlin source roots found for Detekt $label analysis.")
        return
    }

    val javaExecutable = ProcessHandle.current().info().command().orElse("java")
    val arguments =
        buildDetektArguments(
            sourceRoots = sourceRoots,
            analysisMode = analysisMode,
            projectClasspath = projectClasspath,
            configFile = configFile,
            reportDirectory = reportDirectory,
            reportName = reportName,
            projectRoot = projectRoot,
            javaHome = Path.of(System.getProperty("java.home")),
        )
    val command = buildDetektCommand(javaExecutable, detektClasspath, arguments)
    val exitCode = ProcessBuilder(command).inheritIO().start().waitFor()

    detektFailureMessage(exitCode, label)?.let { failureMessage -> error(failureMessage) }
}

private fun List<Path>.existingDirectories(): List<Path> =
    distinct()
        .filter { sourceRoot -> Files.isDirectory(sourceRoot) }
        .sortedBy { sourceRoot -> sourceRoot.toString() }

private const val DETEKT_MAIN_CLASS = "dev.detekt.cli.Main"
private const val DETEKT_INVALID_CONFIG_EXIT_CODE = 3
