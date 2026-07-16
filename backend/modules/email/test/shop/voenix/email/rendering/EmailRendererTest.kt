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
                Triple(
                    UserEmail.AccountConfirmation(recipient, actionUrl),
                    "Bitte bestätige deine E-Mail-Adresse",
                    "E-Mail-Adresse bestätigen",
                ),
                Triple(
                    UserEmail.ChangeEmailConfirmation(recipient, actionUrl),
                    "Bitte bestätige deine neue E-Mail-Adresse",
                    "E-Mail-Adresse ändern",
                ),
                Triple(
                    UserEmail.PasswordReset(recipient, actionUrl),
                    "Setze dein Passwort zurück",
                    "Passwort zurücksetzen",
                ),
                Triple(
                    UserEmail.PasswordChangedNotification(recipient),
                    "Dein Passwort wurde geändert",
                    "Passwort geändert",
                ),
                Triple(
                    UserEmail.ChangeEmailNotification(
                        recipient,
                        EmailRecipient("neu@example.com"),
                    ),
                    "Änderung deiner E-Mail-Adresse angefordert",
                    "E-Mail-Änderung angefordert",
                ),
            )

        cases.forEach { (email, subject, heading) ->
            val rendered = renderer.render(email)
            assertEquals(subject, rendered.subject)
            assertContains(rendered.html, "<!DOCTYPE html>")
            assertContains(rendered.html, "<html lang=\"de\">")
            assertContains(rendered.html, "Voenix Shop")
            assertContains(rendered.html, heading)
            assertEquals(expectedTextLines(email).joinToString("\n"), rendered.text)
            expectedHtmlContent(email).forEach { snippet -> assertContains(rendered.html, snippet) }
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
        assertContains(
            rendered.html,
            "href=\"https://shop.example/confirm?first=1&amp;second=2\"",
        )
        assertContains(rendered.text, "first=1&second=2")
    }

    @Test
    fun `order html escapes display values while text keeps them readable`() {
        val rendered =
            renderer.render(
                orderEmail(
                    shippingCost = 500,
                    total = 3_500,
                    articleName = "Tasse & <Sondermodell>",
                )
            )

        assertContains(rendered.html, "Tasse &amp; &lt;Sondermodell&gt;")
        assertContains(rendered.text, "Tasse & <Sondermodell>")
    }

    @Test
    fun `order uses German money date free shipping addresses and unit prices in text`() {
        val rendered = renderer.render(orderEmail(shippingCost = 0, total = 3_000))

        assertEquals("Bestellbestätigung #42", rendered.subject)
        listOf(
                "Hallo Max",
                "#42",
                "31.12.2026",
                "Tasse",
                "Weiß",
                "Kostenlos",
                "30,00 €",
                "Musterstraße 1",
                "Rechnungsweg 9",
                "Hamburg",
            )
            .forEach { snippet ->
                assertContains(rendered.html, snippet)
                assertContains(rendered.text, snippet)
            }
        assertContains(rendered.text, "2x 15,00 € = 30,00 €")
        assertEquals(
            listOf(
                    "Bestellbestätigung #42",
                    "========================================",
                    "",
                    "Hallo Max,",
                    "vielen Dank für deine Bestellung!",
                    "Sobald deine Bestellung versendet wurde, erhältst du eine Versandbestätigung per E-Mail.",
                    "",
                    "Bestellnummer: #42",
                    "Bestelldatum:  31.12.2026",
                    "",
                    "Artikel:",
                    "----------------------------------------",
                    "  Tasse (Weiß)",
                    "    2x 15,00 € = 30,00 €",
                    "----------------------------------------",
                    "Zwischensumme: 30,00 €",
                    "Versand:       Kostenlos",
                    "Gesamtbetrag:  30,00 €",
                    "",
                    "Lieferadresse:",
                    "  Max Mustermann",
                    "  Musterstraße 1",
                    "  10115 Berlin",
                    "  DE",
                    "",
                    "Rechnungsadresse:",
                    "  Erika Mustermann",
                    "  Rechnungsweg 9",
                    "  20095 Hamburg",
                    "  DE",
                )
                .joinToString("\n"),
            rendered.text,
        )
    }

    @Test
    fun `producer includes all dynamic values and greeting falls back without a name`() {
        val named =
            renderer.render(
                QueuedEmail.ProducerPdfNotification(
                    recipient = recipient,
                    orderId = 42,
                    fileName = "ORD-42.pdf",
                    serverName = "Produktion A",
                    orderDate = LocalDate.of(2026, 12, 31),
                    itemCount = 2,
                    producerName = "Manufaktur Müller",
                )
            )

        listOf(
                "Hallo Manufaktur Müller,",
                "#42",
                "ORD-42.pdf",
                "Produktion A",
                "31.12.2026",
            )
            .forEach { snippet ->
                assertContains(named.html, snippet)
                assertContains(named.text, snippet)
            }
        assertContains(named.html, "Anzahl Artikel: <strong>2</strong>")
        assertEquals(
            listOf(
                    "Neue Bestellung per SFTP übermittelt",
                    "========================================",
                    "",
                    "Hallo Manufaktur Müller,",
                    "Eine neue Bestellung wurde per SFTP auf Ihren Server hochgeladen.",
                    "",
                    "Bestellnummer:  #42",
                    "Dateiname:      ORD-42.pdf",
                    "SFTP-Server:    Produktion A",
                    "Bestelldatum:   31.12.2026",
                    "Anzahl Artikel: 2",
                )
                .joinToString("\n"),
            named.text,
        )

        val unnamed =
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
        assertContains(unnamed.html, "Sehr geehrte Damen und Herren,")
        assertContains(unnamed.text, "Sehr geehrte Damen und Herren,")
        assertFalse(unnamed.html.contains("null"))
    }

    private fun orderEmail(
        shippingCost: Long,
        total: Long,
        articleName: String = "Tasse",
    ): QueuedEmail.OrderConfirmation {
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
        val billingAddress =
            address.copy(
                firstName = "Erika",
                street = "Rechnungsweg",
                houseNumber = "9",
                city = "Hamburg",
                postalCode = "20095",
            )
        return QueuedEmail.OrderConfirmation(
            recipient = recipient,
            orderId = 42,
            orderDate = LocalDate.of(2026, 12, 31),
            customerFirstName = "Max",
            shippingAddress = address,
            billingAddress = billingAddress,
            items =
                listOf(
                    QueuedEmail.OrderConfirmation.Item(
                        articleName = articleName,
                        variantName = "Weiß",
                        quantity = 2,
                        unitPriceInCents = 1_500,
                    )
                ),
            shippingCostInCents = shippingCost,
            totalInCents = total,
        )
    }

    private fun expectedHtmlContent(email: UserEmail): List<String> =
        when (email) {
            is UserEmail.AccountConfirmation ->
                listOf(
                    "Vielen Dank für deine Registrierung bei Voenix Shop! Bitte bestätige deine " +
                        "E-Mail-Adresse, um dein Konto zu aktivieren.",
                    "E-Mail bestätigen",
                    email.confirmationUrl.value,
                    "Falls der Button nicht funktioniert, kopiere diesen Link in deinen Browser:",
                    "Dieser Link ist 24 Stunden gültig. Danach musst du eine neue " +
                        "Bestätigungs-E-Mail anfordern.",
                    "Falls du dich nicht bei Voenix Shop registriert hast, kannst du diese " +
                        "E-Mail ignorieren.",
                )
            is UserEmail.ChangeEmailConfirmation ->
                listOf(
                    "Du hast eine Änderung deiner E-Mail-Adresse zu ",
                    email.recipient.value,
                    " angefordert. Bitte bestätige die neue E-Mail-Adresse.",
                    "E-Mail-Adresse bestätigen",
                    email.confirmationUrl.value,
                    "Dieser Link ist 24 Stunden gültig. Danach musst du die Änderung erneut " +
                        "anfordern.",
                    "Falls du diese Änderung nicht angefordert hast, kannst du diese E-Mail " +
                        "ignorieren. Deine E-Mail-Adresse bleibt unverändert.",
                )
            is UserEmail.PasswordReset ->
                listOf(
                    "Du hast angefordert, dein Passwort bei Voenix Shop zurückzusetzen. Bitte " +
                        "vergib über den Link ein neues Passwort.",
                    "Passwort zurücksetzen",
                    email.resetUrl.value,
                    "Dieser Link ist zeitlich begrenzt gültig. Danach musst du einen neuen Link " +
                        "anfordern.",
                    "Falls du dein Passwort nicht zurücksetzen möchtest, kannst du diese E-Mail " +
                        "ignorieren.",
                )
            is UserEmail.PasswordChangedNotification ->
                listOf(
                    "Dein Passwort wurde soeben erfolgreich geändert.",
                    "Falls du diese Änderung nicht selbst vorgenommen hast, kontaktiere uns " +
                        "bitte umgehend.",
                    "Diese E-Mail wurde automatisch versendet, weil dein Passwort bei Voenix " +
                        "Shop geändert wurde.",
                )
            is UserEmail.ChangeEmailNotification ->
                listOf(
                    "Es wurde eine Änderung deiner E-Mail-Adresse zu ",
                    email.newEmail.value,
                    " angefordert. Wenn die neue Adresse bestätigt wird, wird diese " +
                        "E-Mail-Adresse nicht mehr mit deinem Konto verknüpft sein.",
                    "Falls du diese Änderung nicht selbst angefordert hast, ändere bitte " +
                        "umgehend dein Passwort.",
                    "Diese E-Mail wurde automatisch versendet, weil eine Änderung deiner " +
                        "E-Mail-Adresse bei Voenix Shop angefordert wurde.",
                )
        }

    private fun expectedTextLines(email: UserEmail): List<String> =
        when (email) {
            is UserEmail.AccountConfirmation ->
                listOf(
                    "E-Mail-Adresse bestätigen",
                    "========================================",
                    "",
                    "Vielen Dank für deine Registrierung bei Voenix Shop!",
                    "",
                    "Bitte öffne den folgenden Link, um deine E-Mail-Adresse zu bestätigen:",
                    "",
                    email.confirmationUrl.value,
                    "",
                    "Dieser Link ist 24 Stunden gültig.",
                    "",
                    "Falls du dich nicht bei Voenix Shop registriert hast, kannst du diese E-Mail ignorieren.",
                )
            is UserEmail.ChangeEmailConfirmation ->
                listOf(
                    "E-Mail-Adresse ändern",
                    "========================================",
                    "",
                    "Du hast eine Änderung deiner E-Mail-Adresse zu ${email.recipient.value} angefordert.",
                    "",
                    "Bitte öffne den folgenden Link, um die neue E-Mail-Adresse zu bestätigen:",
                    "",
                    email.confirmationUrl.value,
                    "",
                    "Dieser Link ist 24 Stunden gültig.",
                    "",
                    "Falls du diese Änderung nicht angefordert hast, kannst du diese E-Mail ignorieren.",
                )
            is UserEmail.PasswordReset ->
                listOf(
                    "Passwort zurücksetzen",
                    "========================================",
                    "",
                    "Du hast angefordert, dein Passwort bei Voenix Shop zurückzusetzen.",
                    "",
                    "Bitte öffne den folgenden Link, um ein neues Passwort zu vergeben:",
                    "",
                    email.resetUrl.value,
                    "",
                    "Dieser Link ist zeitlich begrenzt gültig.",
                    "",
                    "Falls du dein Passwort nicht zurücksetzen möchtest, kannst du diese E-Mail ignorieren.",
                )
            is UserEmail.PasswordChangedNotification ->
                listOf(
                    "Passwort geändert",
                    "========================================",
                    "",
                    "Dein Passwort wurde soeben erfolgreich geändert.",
                    "",
                    "Falls du diese Änderung nicht selbst vorgenommen hast, kontaktiere uns bitte umgehend.",
                )
            is UserEmail.ChangeEmailNotification ->
                listOf(
                    "E-Mail-Änderung angefordert",
                    "========================================",
                    "",
                    "Es wurde eine Änderung deiner E-Mail-Adresse zu ${email.newEmail.value} angefordert.",
                    "Wenn die neue Adresse bestätigt wird, wird diese E-Mail-Adresse nicht mehr mit deinem Konto verknüpft sein.",
                    "",
                    "Falls du diese Änderung nicht selbst angefordert hast, ändere bitte umgehend dein Passwort.",
                )
        }
}
