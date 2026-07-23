package shop.voenix.production.delivery

/**
 * A destination as the delivery stage reads it — the only read model that carries the password,
 * because an adapter must authenticate. It exists solely on the worker path: it is never
 * serialized, never returned by any API, and [toString] redacts the password so an accidental log
 * statement cannot leak it.
 */
internal data class ProductionDeliveryDestination(
    val id: Long,
    val channel: String,
    val enabled: Boolean,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val hostKeyFingerprint: String,
    val remotePath: String,
    val timeoutSeconds: Int,
) {
    override fun toString(): String =
        "ProductionDeliveryDestination(id=$id, channel=$channel, enabled=$enabled, host=$host, " +
            "port=$port, username=$username, password=[redacted], " +
            "hostKeyFingerprint=$hostKeyFingerprint, remotePath=$remotePath, " +
            "timeoutSeconds=$timeoutSeconds)"
}
