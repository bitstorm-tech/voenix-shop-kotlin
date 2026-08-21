package shop.voenix.article

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json

/**
 * The pin between the two lists of print aspect ratios that must stay identical: the constants of
 * [PrintAspectRatio] and the values the database CHECKs of `article_mugs` and `article_tshirts`
 * allow.
 *
 * They cannot be derived from one another — one lives in Kotlin, the other in a migration Flyway
 * has already run everywhere — so a third ratio added on one side alone is exactly the mistake this
 * test exists to catch. It reads the migration that introduced the constraint instead of a live
 * database, because the question is about the two *sources*, and answering it needs no PostgreSQL.
 */
internal class PrintAspectRatioTest {
    @Test
    fun `the enum carries exactly the ratios the database CHECK allows`() {
        // Every article type declares the same closed pair on its own column, so every one of them
        // is pinned here.
        MIGRATION_RESOURCES.forEach { resource ->
            val checkValues = checkValuesOf(resource)

            assertEquals(
                listOf("16:9", "1:1"),
                checkValues,
                "The CHECK in $resource is the wire contract this enum spells out",
            )
            assertEquals(checkValues, PrintAspectRatio.entries.map(PrintAspectRatio::wireValue))
        }
    }

    /** The values the `print_aspect_ratio` CHECK of [resource] allows, in the order it lists. */
    private fun checkValuesOf(resource: String): List<String> {
        val migration =
            checkNotNull(javaClass.classLoader.getResourceAsStream(resource)) {
                    "The migration $resource is not on the test classpath"
                }
                .use { stream -> stream.readBytes().decodeToString() }

        return checkNotNull(Regex("""print_aspect_ratio IN \(([^)]*)\)""").find(migration)) {
                "The migration $resource declares no CHECK on print_aspect_ratio"
            }
            .groupValues[1]
            .split(",")
            .map { value -> value.trim().trim('\'') }
    }

    @Test
    fun `a ratio is read and written as its wire value`() {
        PrintAspectRatio.entries.forEach { ratio ->
            assertEquals(ratio, PrintAspectRatio.ofWireValue(ratio.wireValue))
            assertEquals("\"${ratio.wireValue}\"", Json.encodeToString(ratio))
            assertEquals(
                ratio,
                Json.decodeFromString<PrintAspectRatio>("\"${ratio.wireValue}\""),
                "The JSON contract carries the wire value, never the constant name",
            )
        }

        // The constant names are not the contract, and nothing that is not a supported ratio is.
        assertNull(PrintAspectRatio.ofWireValue("WIDE_16_9"))
        assertNull(PrintAspectRatio.ofWireValue("4:3"))
        assertNull(PrintAspectRatio.ofWireValue(""))
    }

    private companion object {
        val MIGRATION_RESOURCES =
            listOf(
                "db/migration/V19__article_print_aspect_ratio.sql",
                "db/migration/V20__create_article_tshirts.sql",
            )
    }
}
