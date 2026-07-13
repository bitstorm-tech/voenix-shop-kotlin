package shop.voenix.http

interface RequestValidationInput {
    fun validationErrors(): Map<String, List<String>>
}
