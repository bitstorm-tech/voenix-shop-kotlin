package shop.voenix.supplier

internal data class StoredSupplier(
    val id: Long,
    val name: String,
    val title: String?,
    val firstName: String?,
    val lastName: String?,
    val street: String?,
    val houseNumber: String?,
    val city: String?,
    val postalCode: String?,
    val countryId: Long?,
    val phoneNumber1: String?,
    val phoneNumber2: String?,
    val phoneNumber3: String?,
    val email: String?,
    val website: String?,
)
