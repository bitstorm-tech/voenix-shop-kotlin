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
