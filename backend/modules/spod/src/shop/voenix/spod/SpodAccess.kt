package shop.voenix.spod

import kotlinx.serialization.Serializable

/**
 * Where a call goes and how it authenticates: one destination row's SPOD installation, token, and
 * timeout, as [SpodClient] needs them.
 *
 * It exists solely on the calling path: it is never serialized, never returned by any API, and its
 * [toString] redacts the token so an accidental log statement cannot leak it. [destinationId] is
 * this shop's own id of the row it was read from — this adapter's own context, and the only part of
 * it that may be logged.
 */
public data class SpodAccess(
    public val destinationId: Long,
    public val environment: SpodEnvironment,
    public val accessToken: String,
    public val timeoutSeconds: Int,
) {
    override fun toString(): String =
        "SpodAccess(destinationId=$destinationId, environment=$environment, " +
            "accessToken=[redacted], timeoutSeconds=$timeoutSeconds)"
}

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
public enum class SpodEnvironment(internal val baseUrl: String) {
    PRODUCTION("https://rest.spreadconnect.app"),
    STAGING("https://rest.spreadconnect-staging.app");

    public companion object {
        /**
         * The stored value read back. The `ck_production_destination_spod_environment` check
         * constraint allows nothing else, so an unknown value is a broken database and throws
         * rather than turning into a missing destination somewhere far from the cause.
         */
        public fun ofStoredValue(value: String): SpodEnvironment =
            checkNotNull(entries.firstOrNull { environment -> environment.name == value }) {
                "Unknown SPOD environment $value stored on a production destination"
            }
    }
}
