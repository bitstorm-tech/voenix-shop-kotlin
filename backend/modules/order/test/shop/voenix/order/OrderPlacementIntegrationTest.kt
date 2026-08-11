package shop.voenix.order

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * What a placement writes, and what it refuses to write.
 *
 * An order is a snapshot: the names, the supplier number, the print measurements, and every address
 * and amount are copied at checkout, and no later catalog change may rewrite them. A placement that
 * cannot be honoured — an unknown article, a missing print image, an invalid address — must leave
 * the database exactly as it found it, and a cart that already has a live order must not get a
 * second one.
 */
internal class OrderPlacementIntegrationTest : OrderServiceTestBase() {
    @Test
    fun `a placement snapshots names, supplier number, and the print measurements`() =
        withFixture("snapshot") { fixture ->
            val placed = fixture.service.place(OrderTestSupport.placeOrderInput()).expectPlaced()

            assertEquals(1, placed.orderId)
            // Never a passed-in number: subtotal + shipping - discount.
            assertEquals(4_470, placed.totalCents)

            // What the customer reads is the order module's own view, and the placement result no
            // longer carries it — a payment has no use for lines, so it is read back here.
            val stored =
                fixture.service
                    .order(placed.orderId, null, OrderTestSupport.GUEST_TOKEN)
                    .expectSuccess()
            assertEquals(OrderStatus.PENDING, stored.status)
            assertEquals(3_980, stored.subtotal)
            assertEquals(490, stored.shippingCost)
            assertEquals(0, stored.discountAmount)
            assertEquals(4_470, stored.total)

            val line = stored.items.single()
            assertEquals(OrderTestSupport.ARTICLE_ID, line.articleId)
            assertEquals("Classic mug", line.articleName)
            assertEquals("White", line.variantName)
            assertEquals(2, line.quantity)
            assertEquals(1_490, line.price)
            assertEquals(500, line.promptPrice)
            assertEquals(OrderTestSupport.PRINT_IMAGE_ID, line.imageId)

            // The production data never reaches the customer's answer, so it is asserted where it
            // is stored: the PDF is laid out from these five numbers and the supplier's number.
            assertEquals(
                "SUP-1",
                OrderTestSupport.singleString(
                    fixture.dataSource,
                    "SELECT supplier_article_number FROM voenix.order_items",
                ),
            )
            assertEquals(
                listOf(239L, 99L, 250L, 110L, 5L),
                listOf(
                        "print_template_width_mm",
                        "print_template_height_mm",
                        "document_format_width_mm",
                        "document_format_height_mm",
                        "document_format_margin_bottom_mm",
                    )
                    .map { column ->
                        OrderTestSupport.singleLong(
                            fixture.dataSource,
                            "SELECT $column FROM voenix.order_items",
                        )
                    },
            )
            assertEquals(
                OrderTestSupport.PROMPT_ID,
                OrderTestSupport.singleLong(
                    fixture.dataSource,
                    "SELECT prompt_id FROM voenix.order_items",
                ),
            )
        }

    @Test
    fun `changing the catalog afterwards does not move what was ordered`() =
        withFixture("catalog-change") { fixture ->
            val stored = fixture.service.place(OrderTestSupport.placeOrderInput()).expectPlaced()

            fixture.articles.variants =
                mapOf(
                    OrderTestSupport.REFERENCE to
                        OrderTestSupport.variant(
                            articleName = "Renamed mug",
                            supplierArticleNumber = "SUP-2",
                            printTemplateWidthMm = 100,
                        )
                )

            val reread = fixture.service.order(stored.orderId, null, OrderTestSupport.GUEST_TOKEN)
            assertEquals("Classic mug", reread.expectSuccess().items.single().articleName)
            assertEquals(
                "SUP-1",
                OrderTestSupport.singleString(
                    fixture.dataSource,
                    "SELECT supplier_article_number FROM voenix.order_items",
                ),
            )
            assertEquals(
                239L,
                OrderTestSupport.singleLong(
                    fixture.dataSource,
                    "SELECT print_template_width_mm FROM voenix.order_items",
                ),
            )
        }

    @Test
    fun `a placement stores every address, contact, and amount value it was given`() =
        withFixture("stored-snapshot") { fixture ->
            OrderTestSupport.seedPromotion(fixture.dataSource)
            // The two addresses share not a single value, so a column that took its value from the
            // wrong one — or from the wrong field of the right one — cannot pass unnoticed.
            val order =
                fixture.service
                    .place(
                        OrderTestSupport.placeOrderInput(
                            userId = OrderTestSupport.USER_ID,
                            promotionId = OrderTestSupport.PROMOTION_ID,
                            shippingAddress = OrderTestSupport.address(),
                            billingAddress =
                                OrderTestSupport.address(
                                    firstName = "Grace",
                                    lastName = "Hopper",
                                    street = "Nebenweg",
                                    houseNumber = "27b",
                                    postalCode = "20095",
                                    city = "Hamburg",
                                    country = "AT",
                                ),
                            phone = "+49 40 987654",
                            discountCents = 398,
                        )
                    )
                    .expectPlaced()

            assertEquals(
                mapOf(
                    "cart_id" to "1",
                    "user_id" to "${OrderTestSupport.USER_ID}",
                    "guest_session_token" to OrderTestSupport.GUEST_TOKEN,
                    "promotion_id" to "${OrderTestSupport.PROMOTION_ID}",
                    "status" to "PENDING",
                    "shipping_first_name" to "Ada",
                    "shipping_last_name" to "Lovelace",
                    "shipping_street" to "Hauptstrasse",
                    "shipping_house_number" to "1",
                    "shipping_postal_code" to "10115",
                    "shipping_city" to "Berlin",
                    "shipping_country" to "DE",
                    "billing_first_name" to "Grace",
                    "billing_last_name" to "Hopper",
                    "billing_street" to "Nebenweg",
                    "billing_house_number" to "27b",
                    "billing_postal_code" to "20095",
                    "billing_city" to "Hamburg",
                    "billing_country" to "AT",
                    "email" to OrderTestSupport.EMAIL,
                    "phone" to "+49 40 987654",
                    "subtotal_cents" to "3980",
                    "shipping_cost_cents" to "490",
                    "discount_cents" to "398",
                    // Never a passed-in number: subtotal + shipping - discount.
                    "total_cents" to "4072",
                ),
                OrderTestSupport.singleRow(
                    fixture.dataSource,
                    "SELECT cart_id, user_id, guest_session_token, promotion_id, status, " +
                        "shipping_first_name, shipping_last_name, shipping_street, " +
                        "shipping_house_number, shipping_postal_code, shipping_city, " +
                        "shipping_country, billing_first_name, billing_last_name, " +
                        "billing_street, billing_house_number, billing_postal_code, " +
                        "billing_city, billing_country, email, phone, subtotal_cents, " +
                        "shipping_cost_cents, discount_cents, total_cents " +
                        "FROM voenix.orders WHERE id = ${order.orderId}",
                ),
            )
        }

    @Test
    fun `the billing address falls back to the shipping address`() =
        withFixture("billing-fallback") { fixture ->
            fixture.service.place(OrderTestSupport.placeOrderInput()).expectPlaced()

            // "Same address" means every billing column, not just the city.
            assertEquals(
                mapOf(
                    "billing_first_name" to "Ada",
                    "billing_last_name" to "Lovelace",
                    "billing_street" to "Hauptstrasse",
                    "billing_house_number" to "1",
                    "billing_postal_code" to "10115",
                    "billing_city" to "Berlin",
                    "billing_country" to "DE",
                ),
                fixture.billingAddressOf(cartId = 1),
            )

            fixture.service
                .place(
                    OrderTestSupport.placeOrderInput(
                        cartId = 2,
                        billingAddress = OrderTestSupport.address(city = "Hamburg"),
                    )
                )
                .expectPlaced()
            assertEquals("Hamburg", fixture.billingAddressOf(cartId = 2)["billing_city"])
        }

    @Test
    fun `the lines keep the order the customer put them in`() =
        withFixture("positions") { fixture ->
            val placed =
                fixture.service
                    .place(
                        OrderTestSupport.placeOrderInput(
                            subtotalCents = 3_980 + 1_490,
                            lines =
                                listOf(
                                    OrderTestSupport.line(),
                                    OrderTestSupport.line(
                                        articleId = OrderTestSupport.OTHER_ARTICLE_ID,
                                        variantId = OrderTestSupport.OTHER_VARIANT_ID,
                                        quantity = 1,
                                        promptPriceCents = 0,
                                        promptId = null,
                                        printImageId = OrderTestSupport.OTHER_PRINT_IMAGE_ID,
                                    ),
                                ),
                        )
                    )
                    .expectPlaced()

            val stored =
                fixture.service
                    .order(placed.orderId, null, OrderTestSupport.GUEST_TOKEN)
                    .expectSuccess()
            assertEquals(
                listOf(OrderTestSupport.ARTICLE_ID, OrderTestSupport.OTHER_ARTICLE_ID),
                stored.items.map(OrderLineView::articleId),
            )
            assertEquals(
                listOf("Classic mug", "Travel mug"),
                stored.items.map(OrderLineView::articleName),
            )
        }

    @Test
    fun `an article the catalog does not know rejects the placement`() =
        withFixture("unknown-article") { fixture ->
            val input =
                OrderTestSupport.placeOrderInput(
                    subtotalCents = 3_980 + 1_990,
                    lines =
                        listOf(
                            OrderTestSupport.line(),
                            OrderTestSupport.line(articleId = 999, variantId = 998, quantity = 1),
                        ),
                )

            assertEquals(OrderPlacementResult.UnknownArticleReference, fixture.service.place(input))
            assertEquals(0, fixture.orderCount(), "A rejected placement must write nothing")
        }

    @Test
    fun `a print image that does not exist rejects the placement`() =
        withFixture("unknown-image") { fixture ->
            val input =
                OrderTestSupport.placeOrderInput(
                    lines = listOf(OrderTestSupport.line(printImageId = 999))
                )

            assertEquals(OrderPlacementResult.UnknownPrintImage, fixture.service.place(input))
            assertEquals(0, fixture.orderCount(), "A rejected placement must write nothing")
        }

    @Test
    fun `an invalid placement is refused before anything is written`() =
        withFixture("invalid") { fixture ->
            val result = fixture.service.place(OrderTestSupport.placeOrderInput(email = "nope"))

            assertTrue(result is OrderPlacementResult.Invalid, "$result")
            assertEquals(listOf("Email is not a valid address"), result.errors["email"])
            assertEquals(0, fixture.orderCount())
        }

    @Test
    fun `ordering the same cart twice answers with the order that already exists`() =
        withFixture("already-placed") { fixture ->
            val first = fixture.service.place(OrderTestSupport.placeOrderInput()).expectPlaced()

            val second = fixture.service.place(OrderTestSupport.placeOrderInput())

            assertEquals(OrderPlacementResult.AlreadyPlaced(first), second)
            assertEquals(1, fixture.orderCount())
        }

    @Test
    fun `a cancelled order leaves the cart free to be ordered again`() =
        withFixture("cancelled-cart") { fixture ->
            val first = fixture.service.place(OrderTestSupport.placeOrderInput()).expectPlaced()
            OrderTestSupport.execute(
                fixture.dataSource,
                "UPDATE voenix.orders SET status = 'CANCELLED' WHERE id = ${first.orderId}",
            )

            val second = fixture.service.place(OrderTestSupport.placeOrderInput()).expectPlaced()

            assertNotEquals(first.orderId, second.orderId)
            assertEquals(2, fixture.orderCount())
        }

    /**
     * The confirmation mail belongs to the placement, not to the payment (issue #110, Joe decision
     * 3): the customer gets the link to their order the moment the order exists, whatever the
     * payment then does with it.
     */
    @Test
    fun `a placement enqueues exactly one confirmation mail for its own order`() =
        withFixture("placement-mail") { fixture ->
            val first = fixture.service.place(OrderTestSupport.placeOrderInput()).expectPlaced()
            val second =
                fixture.service.place(OrderTestSupport.placeOrderInput(cartId = 2)).expectPlaced()

            assertEquals(
                listOf(first.orderId, second.orderId),
                OrderTestSupport.longs(
                    fixture.dataSource,
                    "SELECT source_id FROM voenix.email_jobs " +
                        "WHERE email_kind = 'ORDER_CONFIRMATION' ORDER BY source_id",
                ),
                "one mail per placed order, and nothing else",
            )
        }

    @Test
    fun `a placement that is refused enqueues no mail`() =
        withFixture("placement-mail-refused") { fixture ->
            fixture.service.place(OrderTestSupport.placeOrderInput()).expectPlaced()

            // The same cart again: the unique index refuses the insert and the caller is told which
            // order already exists. Nothing new was written, so nothing new may be mailed.
            fixture.service.place(OrderTestSupport.placeOrderInput())
            fixture.service.place(
                OrderTestSupport.placeOrderInput(
                    cartId = 2,
                    lines = listOf(OrderTestSupport.line(printImageId = 999)),
                )
            )

            assertEquals(1, fixture.orderCount())
            assertEquals(1, fixture.count("voenix.email_jobs"))
        }

    /**
     * The mail joins the placing transaction, and this is what proves it: the enqueue fails after
     * the order and its lines are written, and the order is gone with it. An enqueue *after* the
     * commit would leave the order behind.
     */
    @Test
    fun `an order whose mail cannot be enqueued is not placed at all`() =
        withFixture("placement-mail-rollback") { fixture ->
            fixture.email.failure = IllegalStateException("the mail outbox is down")

            assertFailsWith<IllegalStateException> {
                fixture.service.place(OrderTestSupport.placeOrderInput())
            }

            assertEquals(0, fixture.orderCount())
            assertEquals(0, fixture.count("voenix.order_items"))
            assertEquals(0, fixture.count("voenix.email_jobs"))
        }

    @Test
    fun `every placement stores an access token of its own`() =
        withFixture("access-token") { fixture ->
            val first = fixture.service.place(OrderTestSupport.placeOrderInput()).expectPlaced()
            val second =
                fixture.service.place(OrderTestSupport.placeOrderInput(cartId = 2)).expectPlaced()

            val tokens = listOf(first, second).map { order -> fixture.accessTokenOf(order.orderId) }
            tokens.forEach { token ->
                assertEquals(43, token?.length, "A stored token is a generated one")
            }
            assertNotEquals(
                tokens[0],
                tokens[1],
                "Two orders must never be reachable through the same link",
            )
        }

    /**
     * The collision path, forced: the generator hands out a token that already exists.
     *
     * It is unreachable in production at 2^-256 per placement, and it is still the path that
     * decides whether the customer gets their order or an `AlreadyPlaced` for a cart that never had
     * one. The insert fails with the same `23505` a duplicate cart raises, the live-order read
     * finds nothing — this cart *has* no order — and the bounded retry runs the insert again with a
     * fresh token.
     */
    @Test
    fun `a colliding access token is retried instead of reported as already placed`() =
        withFixture("access-token-collision") { fixture ->
            val first = fixture.service.place(OrderTestSupport.placeOrderInput()).expectPlaced()
            val taken =
                checkNotNull(OrderAccessToken(checkNotNull(fixture.accessTokenOf(first.orderId))))
            // Exactly one collision, then the real generator again — which is what the second
            // attempt has to use for the retry to be able to succeed at all.
            val queued = ArrayDeque(listOf(taken))
            val service =
                OrderService(
                    repository =
                        OrderRepository(fixture.database) {
                            queued.removeFirstOrNull() ?: OrderAccessToken.generate()
                        },
                    articles = fixture.articles,
                    promotions = fixture.promotions,
                    productionOutbox = fixture.production,
                    emailOutbox = fixture.email,
                    printImages = OrderTestSupport.FakePrintImages(),
                    paymentStatuses = fixture.paymentStatuses,
                    links = OrderTestSupport.LINKS,
                )

            val result = service.place(OrderTestSupport.placeOrderInput(cartId = 2))

            assertTrue(
                result is OrderPlacementResult.Placed,
                "A token collision is a retry, never an AlreadyPlaced for a cart without an " +
                    "order: $result",
            )
            assertEquals(2, fixture.orderCount(), "The retry wrote the second order")
            assertNotEquals(
                taken.value,
                fixture.accessTokenOf(result.order.orderId),
                "The retry used a fresh token, not the colliding one",
            )
            assertEquals(0, queued.size)
        }

    /** The stored access token of [orderId] — the column no API answer ever carries. */
    private fun Fixture.accessTokenOf(orderId: Long): String? =
        OrderTestSupport.singleString(
            dataSource,
            "SELECT access_token FROM voenix.orders WHERE id = $orderId",
        )

    /** The seven billing columns of the order placed from [cartId]. */
    private fun Fixture.billingAddressOf(cartId: Long): Map<String, String?> =
        OrderTestSupport.singleRow(
            dataSource,
            "SELECT billing_first_name, billing_last_name, billing_street, " +
                "billing_house_number, billing_postal_code, billing_city, billing_country " +
                "FROM voenix.orders WHERE cart_id = $cartId",
        )
}
