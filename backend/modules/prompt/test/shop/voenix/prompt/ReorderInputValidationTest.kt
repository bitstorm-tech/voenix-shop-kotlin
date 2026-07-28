package shop.voenix.prompt

import kotlin.test.Test
import kotlin.test.assertEquals

internal class ReorderInputValidationTest {
    @Test
    fun `two different positive ids are valid`() {
        assertEquals(emptyMap(), ReorderInput(sourceId = 3, targetId = 1).validate())
    }

    @Test
    fun `both ids are required`() {
        assertEquals(
            mapOf(
                "sourceId" to listOf("SourceId is required"),
                "targetId" to listOf("TargetId is required"),
            ),
            ReorderInput().validate(),
        )
    }

    @Test
    fun `ids must be positive`() {
        assertEquals(
            mapOf(
                "sourceId" to listOf("SourceId must be positive"),
                "targetId" to listOf("TargetId must be positive"),
            ),
            ReorderInput(sourceId = 0, targetId = -1).validate(),
        )
    }

    @Test
    fun `moving a row onto itself is rejected`() {
        assertEquals(
            mapOf("targetId" to listOf("TargetId must be different from SourceId")),
            ReorderInput(sourceId = 7, targetId = 7).validate(),
        )
    }
}
