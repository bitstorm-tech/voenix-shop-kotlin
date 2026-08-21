package shop.voenix.article

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json

/**
 * The pin between the two lists of print aspect ratios that must stay identical: the constants of
 * [PrintAspectRatio] and the values the database CHECK of `article_mugs` allows.
 *
 * They cannot be derived from one another — one lives in Kotlin, the other in a migration Flyway
 * has already run everywhere — so a third ratio added on one side alone is exactly the mistake this
 * test exists to catch. It reads the migration that introduced the constraint instead of a live
 * database, because the question is about the two *sources*, and answering it needs no PostgreSQL.
 */
internal class PrintAspectRatioTest {
    @Test
    fun `the enum carries exactly the ratios the database CHECK allows`() {
        val migration =
            checkNotNull(javaClass.classLoader.getResourceAsStream(MIGRATION_RESOURCE)) {
                    "The migration $MIGRATION_RESOURCE is not on the test classpath"
                }
                .use { stream -> stream.readBytes().decodeToString() }

        val checkValues =
            checkNotNull(Regex("""print_aspect_ratio IN \(([^)]*)\)""").find(migration)) {
                    "The migration declares no CHECK on print_aspect_ratio"
                }
                .groupValues[1]
                .split(",")
                .map { value -> value.trim().trim('\'') }

        assertEquals(
            listOf("16:9", "1:1"),
            checkValues,
            "The CHECK of article_mugs is the wire contract this enum spells out",
        )
        assertEquals(checkValues, PrintAspectRatio.entries.map(PrintAspectRatio::wireValue))
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
        const val MIGRATION_RESOURCE = "db/migration/V19__article_print_aspect_ratio.sql"
    }
}
