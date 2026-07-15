package shop.voenix.supplier

import kotlinx.serialization.Serializable
import shop.voenix.country.Country

@Serializable
public data class Supplier(
    public val id: Long,
    public val name: String,
    public val title: String?,
    public val firstName: String?,
    public val lastName: String?,
    public val street: String?,
    public val houseNumber: String?,
    public val city: String?,
    public val postalCode: String?,
    public val countryId: Long?,
    public val country: Country?,
    public val phoneNumber1: String?,
    public val phoneNumber2: String?,
    public val phoneNumber3: String?,
    public val email: String?,
    public val website: String?,
)
