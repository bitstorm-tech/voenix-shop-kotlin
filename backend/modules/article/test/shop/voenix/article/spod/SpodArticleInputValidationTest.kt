package shop.voenix.article.spod

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.descriptors.elementNames

/**
 * The field-rule matrix of the one SPOD article write there is: the shop's half of a synced
 * article.
 *
 * The product belongs to the Spreadconnect backoffice, so the rules about a name, a description, a
 * colour, a size, and the three printer ids are gone with the fields they judged — and since the
 * print frame is measured by a sync run, so is every rule about it. What is left is what the shop
 * decides: where the article is filed, which variant is the default one, and whether it may be
 * shown at all.
 *
 * Three activation rules are deliberately *not* here. Whether a price exists, whether the partner
 * still lists the article, and whether its frame was measured are facts about the stored row, so
 * the write path owns them; `SpodArticleAdminIntegrationTest` proves them.
 */
internal class SpodArticleInputValidationTest {
    @Test
    fun `a draft that says nothing is accepted`() {
        assertEquals(emptyMap(), SpodArticleInput().validate())
    }

    @Test
    fun `a subcategory without a category is rejected`() {
        assertEquals(
            mapOf("subcategoryId" to listOf("SubcategoryId requires CategoryId")),
            SpodArticleInput(subcategoryId = 5).validate(),
        )
    }

    @Test
    fun `ids that reference another row must be positive`() {
        assertEquals(
            mapOf(
                "categoryId" to listOf("CategoryId must be positive"),
                "subcategoryId" to listOf("SubcategoryId must be positive"),
                "defaultVariantId" to listOf("DefaultVariantId must be positive"),
            ),
            SpodArticleInput(categoryId = 0, subcategoryId = -1, defaultVariantId = 0).validate(),
        )
    }

    @Test
    fun `an active article needs a default variant and a category`() {
        assertEquals(
            listOf("An active article requires an active default variant"),
            SpodArticleInput(active = true, categoryId = 1).validate()["active"],
        )
        assertEquals(
            listOf("An active article requires a category"),
            SpodArticleInput(active = true, defaultVariantId = 7).validate()["active"],
        )
        assertEquals(
            emptyMap(),
            SpodArticleInput(active = true, categoryId = 1, defaultVariantId = 7).validate(),
        )
    }

    /**
     * The write contract is the shop's half of the article and nothing else. Everything the sync
     * owns is absent — the measured frame and the print ratio included — and so are the two fields
     * that were never anybody's to send: the display position is decided by the module, and a price
     * id is never accepted.
     */
    @Test
    fun `the write contract carries no field the sync owns`() {
        assertEquals(
            setOf("active", "categoryId", "subcategoryId", "defaultVariantId", "price"),
            SpodArticleInput.serializer().descriptor.elementNames.toSet(),
        )
    }
}
