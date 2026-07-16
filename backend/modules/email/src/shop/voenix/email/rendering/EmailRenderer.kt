package shop.voenix.email.rendering

import freemarker.cache.ClassTemplateLoader
import freemarker.template.Configuration
import freemarker.template.TemplateExceptionHandler
import java.io.StringWriter
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.format.DateTimeFormatter
import java.util.Locale
import shop.voenix.email.EmailRecipient
import shop.voenix.email.QueuedEmail
import shop.voenix.email.UserEmail

internal class EmailRenderer(private val configuration: Configuration = defaultConfiguration()) {
    internal fun render(email: UserEmail): RenderedEmail =
        when (email) {
            is UserEmail.AccountConfirmation ->
                render(
                    recipient = email.recipient,
                    subject = "Bitte bestätige deine E-Mail-Adresse",
                    template = "account-confirmation",
                    model = mapOf("actionUrl" to email.confirmationUrl.value),
                )
            is UserEmail.ChangeEmailConfirmation ->
                render(
                    recipient = email.recipient,
                    subject = "Bitte bestätige deine neue E-Mail-Adresse",
                    template = "change-email-confirmation",
                    model =
                        mapOf(
                            "actionUrl" to email.confirmationUrl.value,
                            "newEmail" to email.recipient.value,
                        ),
                )
            is UserEmail.PasswordReset ->
                render(
                    recipient = email.recipient,
                    subject = "Setze dein Passwort zurück",
                    template = "password-reset",
                    model = mapOf("actionUrl" to email.resetUrl.value),
                )
            is UserEmail.PasswordChangedNotification ->
                render(
                    recipient = email.recipient,
                    subject = "Dein Passwort wurde geändert",
                    template = "password-changed",
                    model = emptyMap(),
                )
            is UserEmail.ChangeEmailNotification ->
                render(
                    recipient = email.recipient,
                    subject = "Änderung deiner E-Mail-Adresse angefordert",
                    template = "change-email-notification",
                    model = mapOf("newEmail" to email.newEmail.value),
                )
        }

    internal fun render(email: QueuedEmail): RenderedEmail =
        when (email) {
            is QueuedEmail.OrderConfirmation -> renderOrderConfirmation(email)
            is QueuedEmail.ProducerPdfNotification -> renderProducerNotification(email)
        }

    private fun renderOrderConfirmation(email: QueuedEmail.OrderConfirmation): RenderedEmail {
        val items =
            email.items.map { item ->
                val total = Math.multiplyExact(item.unitPriceInCents, item.quantity.toLong())
                mapOf(
                    "articleName" to item.articleName,
                    "variantName" to item.variantName,
                    "quantity" to item.quantity,
                    "unitPrice" to formatPrice(item.unitPriceInCents),
                    "totalPrice" to formatPrice(total),
                )
            }
        val model =
            mapOf(
                "orderId" to email.orderId,
                "orderDate" to DATE_FORMAT.format(email.orderDate),
                "customerFirstName" to email.customerFirstName,
                "items" to items,
                "subtotal" to
                    formatPrice(Math.subtractExact(email.totalInCents, email.shippingCostInCents)),
                "shippingCost" to
                    if (email.shippingCostInCents == 0L) {
                        "Kostenlos"
                    } else {
                        formatPrice(email.shippingCostInCents)
                    },
                "total" to formatPrice(email.totalInCents),
                "shippingAddress" to email.shippingAddress.toTemplateModel(),
                "billingAddress" to email.billingAddress.toTemplateModel(),
            )
        return render(
            recipient = email.recipient,
            recipientName = "${email.shippingAddress.firstName} ${email.shippingAddress.lastName}",
            subject = "Bestellbestätigung #${email.orderId}",
            template = "order-confirmation",
            model = model,
        )
    }

    private fun renderProducerNotification(
        email: QueuedEmail.ProducerPdfNotification
    ): RenderedEmail {
        val greeting =
            email.producerName?.let { producerName -> "Hallo $producerName," }
                ?: "Sehr geehrte Damen und Herren,"
        return render(
            recipient = email.recipient,
            recipientName = email.producerName ?: email.serverName,
            subject = "Neue Bestellung #${email.orderId} – ${email.fileName}",
            template = "producer-pdf-notification",
            model =
                mapOf(
                    "orderId" to email.orderId,
                    "fileName" to email.fileName,
                    "serverName" to email.serverName,
                    "orderDate" to DATE_FORMAT.format(email.orderDate),
                    "itemCount" to email.itemCount,
                    "producerName" to email.producerName,
                    "greeting" to greeting,
                ),
        )
    }

    private fun render(
        recipient: EmailRecipient,
        subject: String,
        template: String,
        model: Map<String, Any?>,
        recipientName: String? = null,
    ): RenderedEmail =
        RenderedEmail(
            recipient = recipient,
            recipientName = recipientName,
            subject = subject,
            html = process("$template.ftlh", model),
            text = process("$template.ftl", model),
        )

    private fun process(template: String, model: Map<String, Any?>): String =
        StringWriter().use { writer ->
            configuration.getTemplate(template).process(model, writer)
            writer.toString().trim()
        }

    private fun QueuedEmail.OrderConfirmation.Address.toTemplateModel(): Map<String, String> =
        mapOf(
            "firstName" to firstName,
            "lastName" to lastName,
            "street" to street,
            "houseNumber" to houseNumber,
            "city" to city,
            "postalCode" to postalCode,
            "country" to country,
        )

    private fun formatPrice(cents: Long): String {
        val euros = BigDecimal.valueOf(cents, 2).setScale(2, RoundingMode.UNNECESSARY)
        return PRICE_FORMAT.get().format(euros) + " €"
    }

    private companion object {
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
        val PRICE_FORMAT: ThreadLocal<DecimalFormat> = ThreadLocal.withInitial {
            DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.GERMANY))
        }

        fun defaultConfiguration(): Configuration =
            Configuration(Configuration.VERSION_2_3_34).apply {
                templateLoader = ClassTemplateLoader(EmailRenderer::class.java, "/email/templates")
                defaultEncoding = Charsets.UTF_8.name()
                templateExceptionHandler = TemplateExceptionHandler.RETHROW_HANDLER
                logTemplateExceptions = false
                wrapUncheckedExceptions = true
                fallbackOnNullLoopVariable = false
                recognizeStandardFileExtensions = true
            }
    }
}
