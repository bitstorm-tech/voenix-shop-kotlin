import {
  actionSection,
  contentSection,
  DURABLE_LINK_HINT,
  explanationSection,
  markup,
  htmlEmail,
  row,
  SEPARATOR,
  textEmail,
  type RenderedEmail,
} from './layout'

export interface ShippingNotificationData {
  recipient: string
  orderId: number
  customerFirstName: string
  /** The lines of *this* package: what and how many, never what it cost. */
  items: { articleName: string; variantName: string; quantity: number }[]
  /** The permanent link to the order; a bearer credential, so never log it. */
  orderUrl: string
  carrierName: string | null
  trackingNumber: string | null
  /** Built by the shop from its own bounded carrier list, never taken from a caller. */
  trackingUrl: string | null
}

const SPLIT_SHIPMENT_HINT =
  'Deine Bestellung kann in mehreren Paketen ankommen. Für jedes weitere Paket erhältst du eine eigene E-Mail.'

/**
 * The mail a customer receives when **one package** of their order leaves a supplier. It is
 * supplier-neutral, never says the order is complete (another package may follow), and carries no
 * price: the money is the confirmation mail's job. Without a tracking link the number is still
 * shown as text.
 */
export function renderShippingNotification(
  data: ShippingNotificationData,
): RenderedEmail<'SHIPPING_NOTIFICATION'> {
  const subject = `Dein Paket ist unterwegs – Bestellung ORD-${String(data.orderId)}`
  const { carrierName, trackingNumber, trackingUrl } = data
  const tracking =
    carrierName === null && trackingNumber === null
      ? []
      : [
          row(
            '0 32px 16px',
            markup`<p>${
              carrierName === null
                ? null
                : markup`Versanddienstleister: <strong>${carrierName}</strong><br>`
            }${
              trackingNumber === null
                ? null
                : markup`Sendungsnummer: <strong>${trackingNumber}</strong>`
            }</p>`,
          ),
          ...(trackingUrl === null ? [] : [actionSection(trackingUrl, 'Sendung verfolgen')]),
        ]
  return {
    kind: 'SHIPPING_NOTIFICATION',
    recipient: data.recipient,
    recipientName: data.customerFirstName,
    subject,
    html: htmlEmail('Bei Fragen zu deiner Bestellung erreichst du uns jederzeit per E-Mail.', [
      contentSection([
        markup`<h2>Dein Paket ist unterwegs</h2>`,
        markup`<p>Hallo ${data.customerFirstName}, ein Paket deiner Bestellung ORD-${data.orderId} ist unterwegs.</p>`,
        markup`<p>${SPLIT_SHIPMENT_HINT}</p>`,
      ]),
      row('8px 32px 16px', [
        markup`<p><strong>Inhalt dieses Pakets</strong></p>`,
        markup`<table role="presentation" style="width:100%;border-collapse:collapse">${data.items.map(
          (item) =>
            markup`<tr><td>${item.articleName}<br><small>${item.variantName}</small></td><td style="text-align:right">${item.quantity}x</td></tr>`,
        )}</table>`,
      ]),
      ...tracking,
      actionSection(data.orderUrl, 'Bestellung ansehen'),
      explanationSection(data.orderUrl, DURABLE_LINK_HINT),
    ]),
    text: textEmail(subject, [
      `Hallo ${data.customerFirstName},`,
      `ein Paket deiner Bestellung ORD-${String(data.orderId)} ist unterwegs.`,
      SPLIT_SHIPMENT_HINT,
      '',
      'Inhalt dieses Pakets:',
      SEPARATOR,
      ...data.items.map(
        (item) => `  ${String(item.quantity)}x ${item.articleName} (${item.variantName})`,
      ),
      SEPARATOR,
      ...(carrierName === null ? [] : [`Versanddienstleister: ${carrierName}`]),
      ...(trackingNumber === null ? [] : [`Sendungsnummer: ${trackingNumber}`]),
      ...(trackingUrl === null ? [] : ['', 'Sendung verfolgen:', trackingUrl]),
      '',
      'Deine Bestellung ansehen:',
      '',
      data.orderUrl,
      '',
      DURABLE_LINK_HINT,
    ]),
  }
}
