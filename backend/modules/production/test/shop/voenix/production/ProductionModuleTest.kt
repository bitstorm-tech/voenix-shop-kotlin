package shop.voenix.production

import kotlin.test.Test
import kotlin.test.assertNotNull

internal class ProductionModuleTest {
    @Test
    fun `module handle can be constructed`() {
        assertNotNull(ProductionModule())
    }
}
