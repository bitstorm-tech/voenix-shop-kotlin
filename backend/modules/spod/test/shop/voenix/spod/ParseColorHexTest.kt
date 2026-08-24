package shop.voenix.spod

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The partner's colour format is undocumented, so this is the whole contract: whatever is
 * recognizable becomes one lowercase `#rrggbb`, and everything else becomes `null` — never a
 * guessed colour, because a swatch is what a customer orders by.
 */
internal class ParseColorHexTest {
    @Test
    fun `every accepted spelling becomes the same lowercase six-digit colour`() {
        assertEquals("#0a0b0c", parseColorHex("#0A0B0C"))
        assertEquals("#0a0b0c", parseColorHex("0a0b0c"))
        assertEquals("#aabbcc", parseColorHex("#ABC"))
        assertEquals("#aabbcc", parseColorHex("abc"))
        assertEquals("#ffffff", parseColorHex("  #FFFFFF  "))
    }

    /** A two-tone garment lists its colours; a shop swatch shows the first one. */
    @Test
    fun `a comma separated list is read as its first colour`() {
        assertEquals("#112233", parseColorHex("#112233,#445566"))
        assertEquals("#112233", parseColorHex(" #112233 , #445566 "))
        assertEquals("#aabbcc", parseColorHex("abc,def,123"))
    }

    @Test
    fun `anything else is no colour at all`() {
        assertNull(parseColorHex(null))
        assertNull(parseColorHex(""))
        assertNull(parseColorHex("   "))
        assertNull(parseColorHex(","))
        assertNull(parseColorHex("black"))
        assertNull(parseColorHex("#12345"))
        assertNull(parseColorHex("#1234567"))
        assertNull(parseColorHex("#0a0b0g"))
        assertNull(parseColorHex("rgb(1,2,3)"))
        assertNull(parseColorHex("##0a0b0c"))
    }
}
