package shop.voenix.promotion

import javax.sql.DataSource

/**
 * Seeds one cart and one order per id in [orderIds] — the rows a redemption needs since the Order
 * migration made `promotion_redemptions.order_id` a `NOT NULL` foreign key
 * (`docs/migration/order-migration.md`). Each order gets its own cart, because at most one live
 * order may exist per cart.
 *
 * The seeded orders carry no promotion of their own: what a promotion test asserts is the
 * redemption, never the order that paid for it. Re-seeding an id that already exists is a no-op, so
 * a test may call this as often as it reseeds its promotions.
 */
internal fun insertOrders(
    dataSource: DataSource,
    vararg orderIds: Long,
) {
    dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            orderIds.forEach { orderId ->
                statement.executeUpdate(
                    "INSERT INTO voenix.carts (id, guest_session_token, status) " +
                        "VALUES ($orderId, 'guest-$orderId', 'CHECKED_OUT') ON CONFLICT DO NOTHING"
                )
                statement.executeUpdate(
                    "INSERT INTO voenix.orders " +
                        "(id, cart_id, guest_session_token, status, $ORDER_ADDRESS_COLUMNS, " +
                        "subtotal_cents, shipping_cost_cents, discount_cents, total_cents) " +
                        "VALUES ($orderId, $orderId, 'guest-$orderId', 'PAID', " +
                        "$ORDER_ADDRESS_VALUES, 1000, 490, 0, 1490) ON CONFLICT DO NOTHING"
                )
            }
        }
    }
}

/**
 * Seeds one active cart per id in [cartIds] — the rows a reservation points at. Each cart gets its
 * own guest token, because only one active cart may exist per token. Re-seeding an existing id is a
 * no-op.
 */
internal fun insertCarts(
    dataSource: DataSource,
    vararg cartIds: Long,
) {
    dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            cartIds.forEach { cartId ->
                statement.executeUpdate(
                    "INSERT INTO voenix.carts (id, guest_session_token, status) " +
                        "VALUES ($cartId, 'cart-$cartId', 'ACTIVE') ON CONFLICT DO NOTHING"
                )
            }
        }
    }
}

/**
 * Seeds one confirmed customer per id in [userIds] — the rows a per-user reservation points at.
 * Re-seeding an existing id is a no-op.
 */
internal fun insertUsers(
    dataSource: DataSource,
    vararg userIds: Long,
) {
    dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            userIds.forEach { userId ->
                statement.executeUpdate(
                    "INSERT INTO voenix.users (id, email, password_hash) " +
                        "VALUES ($userId, 'customer-$userId@example.com', 'hash') " +
                        "ON CONFLICT DO NOTHING"
                )
            }
        }
    }
}

/** The address snapshot every seeded order carries; no promotion test varies it. */
private const val ORDER_ADDRESS_COLUMNS =
    "shipping_first_name, shipping_last_name, shipping_street, shipping_house_number, " +
        "shipping_postal_code, shipping_city, shipping_country, " +
        "billing_first_name, billing_last_name, billing_street, billing_house_number, " +
        "billing_postal_code, billing_city, billing_country, email"

private const val ORDER_ADDRESS_VALUES =
    "'Ada', 'Lovelace', 'Hauptstrasse', '1', '10115', 'Berlin', 'DE', " +
        "'Ada', 'Lovelace', 'Hauptstrasse', '1', '10115', 'Berlin', 'DE', " +
        "'customer@example.com'"
