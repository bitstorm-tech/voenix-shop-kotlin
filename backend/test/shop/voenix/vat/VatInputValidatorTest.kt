package shop.voenix.vat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VatInputValidatorTest {
    @Test
    fun `valid input has no errors`() {
        assertTrue(
            VatInputValidator.validate(
                    VatInput(
                        name = " Standard ",
                        percent = 19,
                        description = " German standard rate ",
                        isDefault = true,
                    )
                )
                .isEmpty()
        )
    }

    @Test
    fun `invalid input returns every applicable field error`() {
        val cases =
            listOf(
                VatInput() to
                    linkedMapOf(
                        "name" to listOf("Name is required"),
                        "percent" to listOf("Percent is required"),
                    ),
                VatInput(name = "   ", percent = -1) to
                    linkedMapOf(
                        "name" to listOf("Name is required"),
                        "percent" to listOf("Percent must be between 0 and 100"),
                    ),
                VatInput(name = "x".repeat(256), percent = 101) to
                    linkedMapOf(
                        "name" to listOf("Name must be at most 255 characters"),
                        "percent" to listOf("Percent must be between 0 and 100"),
                    ),
            )

        cases.forEach { (input, expected) ->
            assertEquals(expected, VatInputValidator.validate(input))
        }
    }

    @Test
    fun `percent boundaries are valid`() {
        assertTrue(VatInputValidator.validate(VatInput("Zero", 0)).isEmpty())
        assertTrue(VatInputValidator.validate(VatInput("Full", 100)).isEmpty())
    }
}
