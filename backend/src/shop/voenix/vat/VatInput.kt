package shop.voenix.vat

import kotlinx.serialization.Serializable
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors

@Serializable
data class VatInput(
    val name: String? = null,
    val percent: Int? = null,
    val description: String? = null,
    val isDefault: Boolean = false,
) : Validatable {
    override fun validate(): ValidationErrors = buildMap {
        if (name.isNullOrBlank()) {
            put("name", listOf("Name is required"))
        } else if (name.trim().length > MAXIMUM_NAME_LENGTH) {
            put("name", listOf("Name must be at most 255 characters"))
        }

        val inputPercent = percent
        if (inputPercent == null) {
            put("percent", listOf("Percent is required"))
        } else if (inputPercent !in MINIMUM_PERCENT..MAXIMUM_PERCENT) {
            put("percent", listOf("Percent must be between 0 and 100"))
        }
    }

    companion object {
        private const val MAXIMUM_NAME_LENGTH = 255
        private const val MINIMUM_PERCENT = 0
        private const val MAXIMUM_PERCENT = 100
    }
}
