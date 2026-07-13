package shop.voenix.vat

object VatInputValidator {
    fun validate(input: VatInput): Map<String, List<String>> = buildMap {
        if (input.name.isNullOrBlank()) {
            put("name", listOf("Name is required"))
        } else if (input.name.trim().length > MAXIMUM_NAME_LENGTH) {
            put("name", listOf("Name must be at most 255 characters"))
        }

        val percent = input.percent
        if (percent == null) {
            put("percent", listOf("Percent is required"))
        } else if (percent !in MINIMUM_PERCENT..MAXIMUM_PERCENT) {
            put("percent", listOf("Percent must be between 0 and 100"))
        }
    }

    private const val MAXIMUM_NAME_LENGTH = 255
    private const val MINIMUM_PERCENT = 0
    private const val MAXIMUM_PERCENT = 100
}
