import {
  actionSection,
  contentSection,
  DURABLE_LINK_HINT,
  explanationSection,
  formatDate,
  formatPrice,
  markup,
  htmlEmail,
  row,
  SEPARATOR,
  textEmail,
  type Html,
  type RenderedEmail,
} from './layout'

export interface OrderConfirmationAddress {
  firstName: string
  lastName: string
  street: string
  houseNumber: string
  city: string
  postalCode: string
  country: string
}

export interface OrderConfirmationData {
  recipient: string
  orderId: number
  /** The ISO calendar date the order was placed, `YYYY-MM-DD`. */
  orderDate: string
  /** The permanent link to this one order; a bearer credential, so never log it. */
  orderUrl: string
  customerFirstName: string
  shippingAddress: OrderConfirmationAddress
  billingAddress: OrderConfirmationAddress
  items: { articleName: string; variantName: string; quantity: number; unitPriceInCents: number }[]
  subtotalInCents: number
  shippingCostInCents: number
  discountInCents: number
  totalInCents: number
}

const SHIPPING_PROMISE =
  'Sobald deine Zahlung eingegangen ist und deine Bestellung versendet wurde, erhältst du eine Versandbestätigung per E-Mail.'

/**
 * The mail a customer receives when their order was *placed*, before anything is known about the
 * payment. Every sentence is payment-neutral: it confirms that the order arrived, never that it was
 * paid, and the link shows its real current status.
 */
export function renderOrderConfirmation(
  data: OrderConfirmationData,
): RenderedEmail<'ORDER_CONFIRMATION'> {
  const subject = `Bestellbestätigung #${String(data.orderId)}`
  const orderDate = formatDate(data.orderDate)
  const items = data.items.map((item) => ({
    ...item,
    unitPrice: formatPrice(item.unitPriceInCents),
    totalPrice: formatPrice(item.unitPriceInCents * item.quantity),
  }))
  const subtotal = formatPrice(data.subtotalInCents)
  // Free shipping is named rather than printed as 0,00 €; a zero discount prints no line at all.
  const shippingCost =
    data.shippingCostInCents === 0 ? 'Kostenlos' : formatPrice(data.shippingCostInCents)
  const discount = data.discountInCents > 0 ? `-${formatPrice(data.discountInCents)}` : null
  const total = formatPrice(data.totalInCents)

  const htmlAddress = (label: string, address: OrderConfirmationAddress): Html =>
    markup`<p><strong>${label}</strong><br>${address.firstName} ${address.lastName}<br>${address.street} ${address.houseNumber}<br>${address.postalCode} ${address.city}<br>${address.country}</p>`
  const textAddress = (address: OrderConfirmationAddress) => [
    `  ${address.firstName} ${address.lastName}`,
    `  ${address.street} ${address.houseNumber}`,
    `  ${address.postalCode} ${address.city}`,
    `  ${address.country}`,
  ]

  return {
    kind: 'ORDER_CONFIRMATION',
    recipient: data.recipient,
    recipientName: `${data.shippingAddress.firstName} ${data.shippingAddress.lastName}`,
    subject,
    html: htmlEmail('Bei Fragen zu deiner Bestellung erreichst du uns jederzeit per E-Mail.', [
      contentSection([
        markup`<h2>Bestellbestätigung</h2>`,
        markup`<p>Hallo ${data.customerFirstName}, vielen Dank für deine Bestellung! Hier ist deine Zusammenfassung:</p>`,
        markup`<p>${SHIPPING_PROMISE}</p>`,
      ]),
      actionSection(data.orderUrl, 'Bestellung ansehen'),
      explanationSection(data.orderUrl, DURABLE_LINK_HINT),
      row(
        '8px 32px 16px',
        markup`<p>Bestellnummer: <strong>#${data.orderId}</strong><br>Bestelldatum: <strong>${orderDate}</strong></p>`,
      ),
      row(
        '8px 32px 16px',
        markup`<table role="presentation" style="width:100%;border-collapse:collapse">${items.map(
          (item) =>
            markup`<tr><td>${item.articleName}<br><small>${item.variantName}</small></td><td style="text-align:center">${item.quantity}</td><td style="text-align:right">${item.totalPrice}</td></tr>`,
        )}</table>`,
      ),
      row(
        '0 32px 16px',
        markup`<p>Zwischensumme: ${subtotal}<br>Versand: ${shippingCost}<br>${
          discount === null ? null : markup`Rabatt: ${discount}<br>`
        }<strong>Gesamtbetrag: ${total}</strong></p>`,
      ),
      row('8px 32px 24px', [
        htmlAddress('Lieferadresse', data.shippingAddress),
        htmlAddress('Rechnungsadresse', data.billingAddress),
      ]),
    ]),
    text: textEmail(subject, [
      `Hallo ${data.customerFirstName},`,
      'vielen Dank für deine Bestellung!',
      SHIPPING_PROMISE,
      '',
      `Bestellnummer: #${String(data.orderId)}`,
      `Bestelldatum:  ${orderDate}`,
      '',
      'Artikel:',
      SEPARATOR,
      ...items.flatMap((item) => [
        `  ${item.articleName} (${item.variantName})`,
        `    ${String(item.quantity)}x ${item.unitPrice} = ${item.totalPrice}`,
      ]),
      SEPARATOR,
      `Zwischensumme: ${subtotal}`,
      `Versand:       ${shippingCost}`,
      ...(discount === null ? [] : [`Rabatt:        ${discount}`]),
      `Gesamtbetrag:  ${total}`,
      '',
      'Lieferadresse:',
      ...textAddress(data.shippingAddress),
      '',
      'Rechnungsadresse:',
      ...textAddress(data.billingAddress),
      '',
      'Deine Bestellung ansehen:',
      '',
      data.orderUrl,
      '',
      DURABLE_LINK_HINT,
    ]),
  }
}
