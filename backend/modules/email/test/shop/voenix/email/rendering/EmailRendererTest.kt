package shop.voenix.email.rendering

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import shop.voenix.email.EmailActionUrl
import shop.voenix.email.EmailRecipient
import shop.voenix.email.QueuedEmail
import shop.voenix.email.UserEmail

internal class EmailRendererTest {
    private val renderer = EmailRenderer()
    private val recipient = EmailRecipient("kunde@example.com")

    @Test
    fun `renders all five direct variants with exact subjects and independent text`() {
        val actionUrl = EmailActionUrl("https://shop.example/confirm?token=a%2Bb")
        val cases =
            listOf(
                UserEmail.AccountConfirmation(recipient, actionUrl) to
                    "Bitte bestätige deine E-Mail-Adresse",
                UserEmail.ChangeEmailConfirmation(recipient, actionUrl) to
                    "Bitte bestätige deine neue E-Mail-Adresse",
                UserEmail.PasswordReset(recipient, actionUrl) to "Setze dein Passwort zurück",
                UserEmail.PasswordChangedNotification(recipient) to "Dein Passwort wurde geändert",
                UserEmail.ChangeEmailNotification(recipient, EmailRecipient("neu@example.com")) to
                    "Änderung deiner E-Mail-Adresse angefordert",
            )

        cases.forEach { (email, subject) ->
            val rendered = renderer.render(email)
            assertEquals(subject, rendered.subject)
            assertContains(rendered.html, "Voenix Shop")
            assertContains(rendered.text, "========================================")
        }
    }

    @Test
    fun `html escapes dynamic values while action links stay complete`() {
        val rendered =
            renderer.render(
                UserEmail.ChangeEmailConfirmation(
                    EmailRecipient("kunde&shop@example.com"),
                    EmailActionUrl("https://shop.example/confirm?first=1&second=2"),
                )
            )

        assertContains(rendered.html, "kunde&amp;shop@example.com")
        assertContains(rendered.html, "first=1&amp;second=2")
        assertContains(rendered.text, "first=1&second=2")
    }

    @Test
    fun `order uses German money date free shipping addresses and unit prices in text`() {
        val rendered = renderer.render(orderEmail(shippingCost = 0, total = 3_000))

        assertEquals("Bestellbestätigung #42", rendered.subject)
        assertContains(rendered.html, "31.12.2026")
        assertContains(rendered.html, "Kostenlos")
        assertContains(rendered.html, "30,00 €")
        assertContains(rendered.text, "2x 15,00 € = 30,00 €")
        assertContains(rendered.text, "Musterstraße 1")
    }

    @Test
    fun `producer greeting falls back when no producer name exists`() {
        val rendered =
            renderer.render(
                QueuedEmail.ProducerPdfNotification(
                    recipient = recipient,
                    orderId = 42,
                    fileName = "ORD-42.pdf",
                    serverName = "Produktion A",
                    orderDate = LocalDate.of(2026, 12, 31),
                    itemCount = 2,
                )
            )

        assertContains(rendered.html, "Sehr geehrte Damen und Herren,")
        assertContains(rendered.text, "Anzahl Artikel: 2")
        assertFalse(rendered.html.contains("null"))
    }

    private fun orderEmail(shippingCost: Long, total: Long): QueuedEmail.OrderConfirmation {
        val address =
            QueuedEmail.OrderConfirmation.Address(
                firstName = "Max",
                lastName = "Mustermann",
                street = "Musterstraße",
                houseNumber = "1",
                city = "Berlin",
                postalCode = "10115",
                country = "DE",
            )
        return QueuedEmail.OrderConfirmation(
            recipient = recipient,
            orderId = 42,
            orderDate = LocalDate.of(2026, 12, 31),
            customerFirstName = "Max",
            shippingAddress = address,
            billingAddress = address,
            items =
                listOf(
                    QueuedEmail.OrderConfirmation.Item(
                        articleName = "Tasse",
                        variantName = "Weiß",
                        quantity = 2,
                        unitPriceInCents = 1_500,
                    )
                ),
            shippingCostInCents = shippingCost,
            totalInCents = total,
        )
    }
}
