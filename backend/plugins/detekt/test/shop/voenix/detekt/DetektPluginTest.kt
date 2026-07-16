package shop.voenix.detekt

import java.io.File
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DetektPluginTest {
    @Test
    fun `full analysis arguments include project classpath language versions and reports`() {
        val sourceRoot = createTempDirectory()
        val projectClasspath = listOf(Path.of("project-classes"), Path.of("dependency.jar"))
        val configFile = Path.of("config", "detekt", "detekt.yml")
        val reportDirectory = Path.of("build", "detekt")
        val projectRoot = Path.of("backend")

        val arguments =
            buildDetektArguments(
                sourceRoots = listOf(sourceRoot),
                analysisMode = "full",
                projectClasspath = projectClasspath,
                configFile = configFile,
                reportDirectory = reportDirectory,
                reportName = "main",
                projectRoot = projectRoot,
                javaHome = Path.of("provisioned-jdk"),
            )

        assertEquals("full", optionValue(arguments, "--analysis-mode"))
        assertEquals(sourceRoot.toString(), optionValue(arguments, "--input"))
        assertEquals(
            projectClasspath.joinToString(File.pathSeparator),
            optionValue(arguments, "--classpath"),
        )
        assertEquals("2.4", optionValue(arguments, "--language-version"))
        assertEquals("2.4", optionValue(arguments, "--api-version"))
        assertEquals("25", optionValue(arguments, "--jvm-target"))
        assertEquals(configFile.toString(), optionValue(arguments, "--config"))
        assertEquals(projectRoot.toString(), optionValue(arguments, "--base-path"))
        assertEquals("provisioned-jdk", optionValue(arguments, "--jdk-home"))
        assertTrue("--build-upon-default-config" in arguments)
        assertEquals("Warning", optionValue(arguments, "--fail-on-severity"))
        assertTrue("sarif:${reportDirectory.resolve("main.sarif")}" in arguments)
        assertTrue("markdown:${reportDirectory.resolve("main.md")}" in arguments)
        assertFalse("--all-rules" in arguments)
    }

    @Test
    fun `light analysis covers test and plugin roots without project classpath`() {
        val testRoot = Path.of("test")
        val pluginRoot = Path.of("plugins")

        val arguments =
            buildDetektArguments(
                sourceRoots = listOf(testRoot, pluginRoot),
                analysisMode = "light",
                projectClasspath = emptyList(),
                configFile = Path.of("detekt.yml"),
                reportDirectory = Path.of("reports"),
                reportName = "additional",
                projectRoot = Path.of("backend"),
                javaHome = Path.of("jdk"),
            )

        assertEquals("light", optionValue(arguments, "--analysis-mode"))
        assertEquals(
            listOf(testRoot, pluginRoot).joinToString(File.pathSeparator),
            optionValue(arguments, "--input"),
        )
        assertFalse("--classpath" in arguments)
        assertTrue("sarif:${Path.of("reports", "additional.sarif")}" in arguments)
        assertTrue("markdown:${Path.of("reports", "additional.md")}" in arguments)
    }

    @Test
    fun `tool classpath is isolated from analyzed project classpath`() {
        val toolClasspath = listOf(Path.of("detekt-cli.jar"), Path.of("kotlin-compiler.jar"))
        val projectClasspath = Path.of("project.jar")
        val arguments = listOf("--classpath", projectClasspath.toString())

        val command =
            buildDetektCommand(
                javaExecutable = "java",
                detektClasspath = toolClasspath,
                arguments = arguments,
                javaFeatureVersion = 25,
            )

        assertEquals("java", command[0])
        assertEquals("--sun-misc-unsafe-memory-access=allow", command[1])
        assertEquals("-cp", command[2])
        assertEquals(toolClasspath.joinToString(File.pathSeparator), command[3])
        assertEquals("dev.detekt.cli.Main", command[4])
        assertFalse(command[3].contains(projectClasspath.toString()))
        assertEquals(arguments, command.drop(5))
    }

    @Test
    fun `unsafe compatibility option is limited to JDKs that support warning suppression`() {
        val commandOnJdk21 = buildDetektCommand("java", emptyList(), emptyList(), 21)
        val commandOnJdk24 = buildDetektCommand("java", emptyList(), emptyList(), 24)
        val commandOnJdk26 = buildDetektCommand("java", emptyList(), emptyList(), 26)

        assertFalse("--sun-misc-unsafe-memory-access=allow" in commandOnJdk21)
        assertTrue("--sun-misc-unsafe-memory-access=allow" in commandOnJdk24)
        assertFalse("--sun-misc-unsafe-memory-access=allow" in commandOnJdk26)
    }

    @Test
    fun `documented exit codes have distinct messages`() {
        assertNull(detektFailureMessage(0, "main"))
        assertTrue(detektFailureMessage(1, "main").orEmpty().contains("unexpected tool error"))
        assertTrue(detektFailureMessage(2, "main").orEmpty().contains("code finding"))
        assertTrue(detektFailureMessage(3, "main").orEmpty().contains("invalid configuration"))
        assertTrue(detektFailureMessage(99, "main").orEmpty().contains("unknown exit code 99"))
    }

    private fun optionValue(
        arguments: List<String>,
        option: String,
    ): String {
        val optionIndex = arguments.indexOf(option)
        assertTrue(optionIndex >= 0, "Missing option $option in $arguments")
        return arguments[optionIndex + 1]
    }
}
