package shop.voenix.validation

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the shared builder guarantees to every `validate()` implementation: messages accumulate per
 * field instead of overwriting each other, order is the order the rules ran in, and a built map is
 * a snapshot.
 */
internal class ValidationErrorsBuilderTest {
    @Test
    fun `a builder that collected nothing reports no errors`() {
        assertEquals(emptyMap(), buildValidationErrors {})
    }

    @Test
    fun `two rules on one field keep both messages in order`() {
        val errors = buildValidationErrors {
            add("targetId", "TargetId must be positive")
            add("targetId", "TargetId must be different from SourceId")
        }

        assertEquals(
            mapOf(
                "targetId" to
                    listOf("TargetId must be positive", "TargetId must be different from SourceId")
            ),
            errors,
        )
    }

    @Test
    fun `an empty message list adds no field`() {
        val errors = buildValidationErrors { addAll("name", emptyList()) }

        assertEquals(emptyMap(), errors)
    }

    @Test
    fun `a nested result is merged into the messages already collected`() {
        val nested =
            mapOf("name" to listOf("Name is required"), "price" to listOf("Price is required"))

        val errors = buildValidationErrors {
            add("name", "Name must be at most 200 characters")
            addAll(nested)
        }

        assertEquals(
            mapOf(
                "name" to listOf("Name must be at most 200 characters", "Name is required"),
                "price" to listOf("Price is required"),
            ),
            errors,
        )
    }

    @Test
    fun `fields keep the order in which they were first reported`() {
        val errors = buildValidationErrors {
            add("second", "Second is required")
            add("first", "First is required")
            add("second", "Second must be positive")
        }

        assertEquals(listOf("second", "first"), errors.keys.toList())
    }

    @Test
    fun `a built map is a snapshot that later adds do not change`() {
        val builder = ValidationErrorsBuilder()
        builder.add("name", "Name is required")

        val errors = builder.build()
        builder.add("name", "Name must be at most 200 characters")

        assertEquals(mapOf("name" to listOf("Name is required")), errors)
    }

    @Test
    fun `a built map does not change when the caller keeps mutating the list it passed in`() {
        val messages = mutableListOf("Name is required")
        val builder = ValidationErrorsBuilder()
        builder.addAll("name", messages)

        val errors = builder.build()
        messages += "Name must be at most 200 characters"

        assertEquals(mapOf("name" to listOf("Name is required")), errors)
    }
}
