package shop.voenix.email.rendering

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.format.DateTimeFormatter
import java.util.Locale
import shop.voenix.email.EmailRecipient
import shop.voenix.email.QueuedEmail
import shop.voenix.email.UserEmail
import shop.voenix.email.template.AccountConfirmationEmailTemplate
import shop.voenix.email.template.ChangeEmailConfirmationEmailTemplate
import shop.voenix.email.template.ChangeEmailNotificationEmailTemplate
import shop.voenix.email.template.OrderConfirmationEmailTemplate
import shop.voenix.email.template.PasswordChangedEmailTemplate
import shop.voenix.email.template.PasswordResetEmailTemplate
import shop.voenix.email.template.ProducerPdfNotificationEmailTemplate

internal class EmailRenderer : UserEmailRenderer, QueuedEmailRenderer {
    override fun render(email: UserEmail): RenderedEmail =
        when (email) {
            is UserEmail.AccountConfirmation ->
                rendered(
                    recipient = email.recipient,
                    subject = AccountConfirmationEmailTemplate.SUBJECT,
                    html = AccountConfirmationEmailTemplate.renderHtml(email.confirmationUrl.value),
                    text = AccountConfirmationEmailTemplate.renderText(email.confirmationUrl.value),
                )
            is UserEmail.ChangeEmailConfirmation ->
                rendered(
                    recipient = email.recipient,
                    subject = ChangeEmailConfirmationEmailTemplate.SUBJECT,
                    html =
                        ChangeEmailConfirmationEmailTemplate.renderHtml(
                            actionUrl = email.confirmationUrl.value,
                            newEmail = email.recipient.value,
                        ),
                    text =
                        ChangeEmailConfirmationEmailTemplate.renderText(
                            actionUrl = email.confirmationUrl.value,
                            newEmail = email.recipient.value,
                        ),
                )
            is UserEmail.PasswordReset ->
                rendered(
                    recipient = email.recipient,
                    subject = PasswordResetEmailTemplate.SUBJECT,
                    html = PasswordResetEmailTemplate.renderHtml(email.resetUrl.value),
                    text = PasswordResetEmailTemplate.renderText(email.resetUrl.value),
                )
            is UserEmail.PasswordChangedNotification ->
                rendered(
                    recipient = email.recipient,
                    subject = PasswordChangedEmailTemplate.SUBJECT,
                    html = PasswordChangedEmailTemplate.renderHtml(),
                    text = PasswordChangedEmailTemplate.renderText(),
                )
            is UserEmail.ChangeEmailNotification ->
                rendered(
                    recipient = email.recipient,
                    subject = ChangeEmailNotificationEmailTemplate.SUBJECT,
                    html = ChangeEmailNotificationEmailTemplate.renderHtml(email.newEmail.value),
                    text = ChangeEmailNotificationEmailTemplate.renderText(email.newEmail.value),
                )
        }

    override fun render(email: QueuedEmail): RenderedEmail =
        when (email) {
            is QueuedEmail.OrderConfirmation -> renderOrderConfirmation(email)
            is QueuedEmail.ProducerPdfNotification -> renderProducerNotification(email)
        }

    private fun renderOrderConfirmation(email: QueuedEmail.OrderConfirmation): RenderedEmail {
        val content =
            OrderConfirmationEmailTemplate.Content(
                orderId = email.orderId,
                orderDate = DATE_FORMAT.format(email.orderDate),
                customerFirstName = email.customerFirstName,
                items =
                    email.items.map { item ->
                        OrderConfirmationEmailTemplate.Content.Item(
                            articleName = item.articleName,
                            variantName = item.variantName,
                            quantity = item.quantity,
                            unitPrice = formatPrice(item.unitPriceInCents),
                            totalPrice =
                                formatPrice(
                                    Math.multiplyExact(
                                        item.unitPriceInCents,
                                        item.quantity.toLong(),
                                    )
                                ),
                        )
                    },
                subtotal =
                    formatPrice(Math.subtractExact(email.totalInCents, email.shippingCostInCents)),
                shippingCost =
                    if (email.shippingCostInCents == 0L) {
                        "Kostenlos"
                    } else {
                        formatPrice(email.shippingCostInCents)
                    },
                total = formatPrice(email.totalInCents),
                shippingAddress = email.shippingAddress,
                billingAddress = email.billingAddress,
            )
        return rendered(
            recipient = email.recipient,
            recipientName = "${email.shippingAddress.firstName} ${email.shippingAddress.lastName}",
            subject = OrderConfirmationEmailTemplate.subject(email.orderId),
            html = OrderConfirmationEmailTemplate.renderHtml(content),
            text = OrderConfirmationEmailTemplate.renderText(content),
        )
    }

    private fun renderProducerNotification(
        email: QueuedEmail.ProducerPdfNotification
    ): RenderedEmail {
        val content =
            ProducerPdfNotificationEmailTemplate.Content(
                orderId = email.orderId,
                fileName = email.fileName,
                serverName = email.serverName,
                orderDate = DATE_FORMAT.format(email.orderDate),
                itemCount = email.itemCount,
                greeting =
                    email.producerName?.let { producerName -> "Hallo $producerName," }
                        ?: "Sehr geehrte Damen und Herren,",
            )
        return rendered(
            recipient = email.recipient,
            recipientName = email.producerName ?: email.serverName,
            subject = ProducerPdfNotificationEmailTemplate.subject(email.orderId, email.fileName),
            html = ProducerPdfNotificationEmailTemplate.renderHtml(content),
            text = ProducerPdfNotificationEmailTemplate.renderText(content),
        )
    }

    private fun rendered(
        recipient: EmailRecipient,
        subject: String,
        html: String,
        text: String,
        recipientName: String? = null,
    ): RenderedEmail =
        RenderedEmail(
            recipient = recipient,
            recipientName = recipientName,
            subject = subject,
            html = html,
            text = text,
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
    }
}
