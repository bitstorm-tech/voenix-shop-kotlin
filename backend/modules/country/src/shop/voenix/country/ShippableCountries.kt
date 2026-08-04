package shop.voenix.country

/**
 * The one question a checkout asks this module: may a parcel go to this country code?
 *
 * The answer is the administrable `countries` table itself. A country is shippable exactly when
 * that table has a row with this code — there is no separate `active` flag on it, so *adding the
 * row* is what opens a destination and *deleting it* through the country admin is what closes one.
 * The capability is named for the question it answers rather than for the column it reads, so the
 * day the table grows a real activation flag only this implementation changes.
 *
 * What it deliberately does not do is reach into an *order*. An order stores the country it was
 * placed with as plain text and no foreign key points from it to this table, so removing a country
 * here stops future checkouts and leaves every order that already exists exactly as it was.
 *
 * Unexpected database failures are not mapped to a result and surface as exceptions, exactly like
 * `CheckoutCarts` and `PromotionCodes` do it: the consuming module answers them with its own error
 * policy.
 */
public interface ShippableCountries {
    /**
     * Whether the shop ships to [countryCode], an ISO 3166-1 alpha-2 code.
     *
     * The code is compared case-insensitively — the admin stores it upper-case, and a client that
     * sends `de` means the same country as one that sends `DE`. Anything that is not a stored code
     * answers `false`, including a blank string.
     */
    public suspend fun isShippable(countryCode: String): Boolean
}
