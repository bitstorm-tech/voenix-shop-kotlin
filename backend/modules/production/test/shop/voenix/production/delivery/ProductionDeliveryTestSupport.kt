package shop.voenix.production.delivery

import java.nio.file.Path
import java.time.LocalDate
import javax.sql.DataSource
import shop.voenix.production.ProductionData
import shop.voenix.production.ProductionItem

/** The order date every sample order of the delivery integration tests ships with. */
internal val SAMPLE_ORDER_DATE: LocalDate = LocalDate.of(2026, 7, 16)

internal fun execute(dataSource: DataSource, sql: String) {
    dataSource.connection.use { connection ->
        connection.createStatement().use { statement -> statement.executeUpdate(sql) }
    }
}

/** Empties every production table so a test starts from a blank schema with fresh identities. */
internal fun resetProductionTables(dataSource: DataSource) {
    execute(
        dataSource,
        "TRUNCATE voenix.production_deliveries, voenix.production_jobs, " +
            "voenix.production_requests, voenix.production_destinations, voenix.suppliers " +
            "RESTART IDENTITY CASCADE",
    )
}

/**
 * Seeds one cart and one order per id in [orderIds] — the rows a production request needs since the
 * Order migration turned `production_requests.order_id` into a real foreign key
 * (`docs/migration/order-migration.md`). Each order gets its own cart, because at most one live
 * order may exist per cart.
 *
 * Production reads nothing from these rows: what a test produces from comes from its
 * `ProductionSource`. Re-seeding an id that already exists is a no-op, so a test may seed the same
 * order twice without caring who seeded it first.
 */
internal fun insertOrders(dataSource: DataSource, vararg orderIds: Long) {
    orderIds.forEach { orderId ->
        execute(
            dataSource,
            "INSERT INTO voenix.carts (id, guest_session_token, status) " +
                "VALUES ($orderId, 'guest-$orderId', 'CHECKED_OUT') ON CONFLICT DO NOTHING",
        )
        execute(
            dataSource,
            "INSERT INTO voenix.orders " +
                "(id, cart_id, guest_session_token, status, $ORDER_ADDRESS_COLUMNS, " +
                "subtotal_cents, shipping_cost_cents, discount_cents, total_cents) " +
                "VALUES ($orderId, $orderId, 'guest-$orderId', 'PAID', $ORDER_ADDRESS_VALUES, " +
                "1000, 490, 0, 1490) ON CONFLICT DO NOTHING",
        )
    }
}

/** The address snapshot every seeded order carries; no production test varies it. */
private const val ORDER_ADDRESS_COLUMNS =
    "shipping_first_name, shipping_last_name, shipping_street, shipping_house_number, " +
        "shipping_postal_code, shipping_city, shipping_country, " +
        "billing_first_name, billing_last_name, billing_street, billing_house_number, " +
        "billing_postal_code, billing_city, billing_country, email"

private const val ORDER_ADDRESS_VALUES =
    "'Erika', 'Musterfrau', 'Musterstraße', '1', '12345', 'Berlin', 'DE', " +
        "'Erika', 'Musterfrau', 'Musterstraße', '1', '12345', 'Berlin', 'DE', " +
        "'kundin@example.com'"

internal fun insertSupplier(dataSource: DataSource, id: Long = 1, name: String = "Supplier $id") {
    execute(dataSource, "INSERT INTO voenix.suppliers (id, name) VALUES ($id, '$name')")
}

/** Inserts an SFTP destination; null parameters are omitted so the column defaults apply. */
internal fun insertDestination(
    dataSource: DataSource,
    id: Long,
    supplierId: Long = 1,
    label: String = "Destination $id",
    enabled: Boolean? = null,
    host: String = "sftp.example.com",
    port: Int? = null,
    username: String = "user",
    password: String = "secret",
    hostKeyFingerprint: String = "SHA256:fingerprint",
    remotePath: String? = null,
    timeoutSeconds: Int = 30,
    notificationEmail: String? = null,
    notificationName: String? = null,
) {
    val columns =
        linkedMapOf(
            "id" to "$id",
            "supplier_id" to "$supplierId",
            "channel" to "'SFTP'",
            "label" to "'$label'",
            "host" to "'$host'",
            "username" to "'$username'",
            "password" to "'$password'",
            "host_key_fingerprint" to "'$hostKeyFingerprint'",
            "timeout_seconds" to "$timeoutSeconds",
        )
    enabled?.let { columns["enabled"] = "$it" }
    port?.let { columns["port"] = "$it" }
    remotePath?.let { columns["remote_path"] = "'$it'" }
    notificationEmail?.let { columns["notification_email"] = "'$it'" }
    notificationName?.let { columns["notification_name"] = "'$it'" }
    execute(
        dataSource,
        "INSERT INTO voenix.production_destinations (${columns.keys.joinToString(", ")}) " +
            "VALUES (${columns.values.joinToString(", ")})",
    )
}

/** An order for the shared sample recipient; without items it carries one supplier-1 item. */
internal fun order(orderId: Long, vararg items: ProductionItem): ProductionData =
    ProductionData(
        orderId = orderId,
        orderDate = SAMPLE_ORDER_DATE,
        shippingFirstName = "Erika",
        shippingLastName = "Musterfrau",
        shippingStreet = "Musterstraße",
        shippingHouseNumber = "1",
        shippingPostalCode = "12345",
        shippingCity = "Berlin",
        shippingCountry = "Deutschland",
        items = if (items.isEmpty()) listOf(item()) else items.toList(),
    )

internal fun item(
    supplierId: Long? = 1,
    articleName: String = "Zaubertasse",
    supplierArticleNumber: String? = null,
    variantName: String = "Blau",
    quantity: Int = 1,
    imagePath: Path? = null,
): ProductionItem =
    ProductionItem(
        supplierId = supplierId,
        articleName = articleName,
        supplierArticleNumber = supplierArticleNumber,
        variantName = variantName,
        quantity = quantity,
        imagePath = imagePath,
    )
