package shop.voenix.production.spod

import kotlinx.serialization.Serializable

/**
 * The SPOD installation a destination talks to.
 *
 * The base URL is a property of this enum and never a column or an admin input
 * (`docs/adr/0002-production-fulfillment-channels.md`, decision 3): a destination chooses between
 * two known installations, so no configuration mistake can point fulfillment at an arbitrary host.
 *
 * On the wire and in the database the value is the entry name, which is also what the
 * `ck_production_destination_spod_environment` check constraint allows.
 */
@Serializable
internal enum class SpodEnvironment(val baseUrl: String) {
    PRODUCTION("https://rest.spreadconnect.app"),
    STAGING("https://rest.spreadconnect-staging.app");

    internal companion object {
        /** The stored value read back, or `null` when the column holds something unknown. */
        internal fun ofStoredValue(value: String): SpodEnvironment? =
            entries.firstOrNull { environment ->
                environment.name == value
            }
    }
}
