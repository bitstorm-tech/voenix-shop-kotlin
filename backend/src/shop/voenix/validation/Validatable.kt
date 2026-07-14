package shop.voenix.validation

interface Validatable {
    fun validate(): ValidationErrors
}
