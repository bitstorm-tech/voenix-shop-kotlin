package shop.voenix.account

import java.time.Instant
import kotlinx.serialization.Serializable
import shop.voenix.validation.ValidationErrors

/** The stored user row including credential and lockout state. Never serialized. */
internal data class UserAccount(
    val id: Long,
    val email: String,
    val emailConfirmed: Boolean,
    val passwordHash: String,
    val createdAt: Instant,
    val failedLoginCount: Int,
    val lockedUntil: Instant?,
    val roles: Set<String>,
    val shippingAddress: Address?,
    val billingAddress: Address?,
    val hasSeparateBillingAddress: Boolean,
)

internal fun UserAccount.toView(): AccountProfileView =
    AccountProfileView(
        id = id,
        email = email,
        roles = roles.sorted(),
        shippingAddress = shippingAddress,
        billingAddress = billingAddress,
        hasSeparateBillingAddress = hasSeparateBillingAddress,
        createdAt = createdAt.toString(),
    )

/** The one profile representation returned by both `GET me` and `PUT profile`. */
@Serializable
internal data class AccountProfileView(
    val id: Long,
    val email: String,
    val roles: List<String>,
    val shippingAddress: Address?,
    val billingAddress: Address?,
    val hasSeparateBillingAddress: Boolean,
    val createdAt: String,
)

/**
 * The one shipping/billing address value used in profile input and output. All fields are optional;
 * the profile stores whatever subset the customer filled in.
 *
 * This single type is deliberate: it is the one shared serializable value of both [ProfileInput]
 * and [AccountProfileView]. `PUT profile` is a full replace, so what a client sends back is exactly
 * what `GET me` answered — splitting it into an `AddressInput` and an `AddressView` would create
 * two identical types that must never drift apart.
 */
@Serializable
internal data class Address(
    val firstName: String? = null,
    val lastName: String? = null,
    val street: String? = null,
    val houseNumber: String? = null,
    val postalCode: String? = null,
    val city: String? = null,
    val country: String? = null,
    val phone: String? = null,
) {
    fun validate(fieldPrefix: String): ValidationErrors = buildMap {
        maxLength(fieldPrefix, "firstName", firstName, MAX_NAME_LENGTH)
        maxLength(fieldPrefix, "lastName", lastName, MAX_NAME_LENGTH)
        maxLength(fieldPrefix, "street", street, MAX_STREET_LENGTH)
        maxLength(fieldPrefix, "houseNumber", houseNumber, MAX_HOUSE_NUMBER_LENGTH)
        maxLength(fieldPrefix, "postalCode", postalCode, MAX_POSTAL_CODE_LENGTH)
        maxLength(fieldPrefix, "city", city, MAX_CITY_LENGTH)
        if (!country.isNullOrBlank() && !isTwoLetterCountry(country.trim())) {
            put("$fieldPrefix.country", listOf("Country must be a two-letter code"))
        }
        if (!phone.isNullOrEmpty() && !PHONE_PATTERN.matches(phone)) {
            put("$fieldPrefix.phone", listOf("Phone is not a valid phone number"))
        }
    }

    /** Normalization after validation: trim every field and drop blank values. */
    fun normalized(): Address? =
        Address(
                firstName = firstName.cleaned(),
                lastName = lastName.cleaned(),
                street = street.cleaned(),
                houseNumber = houseNumber.cleaned(),
                postalCode = postalCode.cleaned(),
                city = city.cleaned(),
                country = country.cleaned(),
                phone = phone.cleaned(),
            )
            .takeIf { normalized -> normalized != EMPTY }

    private fun String?.cleaned(): String? = this?.trim()?.ifEmpty { null }

    private fun MutableMap<String, List<String>>.maxLength(
        prefix: String,
        field: String,
        value: String?,
        maximum: Int,
    ) {
        if (value != null && value.length > maximum) {
            put("$prefix.$field", listOf("Must be at most $maximum characters"))
        }
    }

    private fun isTwoLetterCountry(value: String): Boolean =
        value.length == COUNTRY_CODE_LENGTH && value.all(Char::isLetter)

    companion object {
        private val EMPTY = Address()
        private val PHONE_PATTERN = Regex("""^\+?[\d\s\-().\/]+$""")
        private const val MAX_NAME_LENGTH = 100
        private const val MAX_STREET_LENGTH = 200
        private const val MAX_HOUSE_NUMBER_LENGTH = 20
        private const val MAX_POSTAL_CODE_LENGTH = 10
        private const val MAX_CITY_LENGTH = 100
        private const val COUNTRY_CODE_LENGTH = 2
    }
}
