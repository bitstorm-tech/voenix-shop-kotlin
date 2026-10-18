import {
  contentSection,
  formatDate,
  markup,
  htmlEmail,
  labelledValue,
  row,
  textEmail,
  type RenderedEmail,
} from './layout'

/** The notice to a producer that an order's PDF was uploaded to their SFTP server. */
export function renderProducerPdfNotification(data: {
  recipient: string
  orderId: number
  fileName: string
  destinationLabel: string
  /** The ISO calendar date the order was placed, `YYYY-MM-DD`. */
  orderDate: string
  itemCount: number
  producerName: string | null
}): RenderedEmail<'PRODUCER_PDF_NOTIFICATION'> {
  const greeting =
    data.producerName === null ? 'Sehr geehrte Damen und Herren,' : `Hallo ${data.producerName},`
  const orderDate = formatDate(data.orderDate)
  const title = 'Neue Bestellung per SFTP übermittelt'
  const uploaded = 'Eine neue Bestellung wurde per SFTP auf Ihren Server hochgeladen.'
  return {
    kind: 'PRODUCER_PDF_NOTIFICATION',
    recipient: data.recipient,
    recipientName: data.producerName ?? data.destinationLabel,
    subject: `Neue Bestellung #${String(data.orderId)} – ${data.fileName}`,
    html: htmlEmail('Bei Rückfragen zur Bestellung wenden Sie sich bitte an den Voenix Shop.', [
      contentSection([
        markup`<h2>${title}</h2>`,
        markup`<p>${greeting}</p>`,
        markup`<p>${uploaded}</p>`,
      ]),
      row(
        '8px 32px 24px',
        markup`<p>${[
          labelledValue('Bestellnummer', `#${String(data.orderId)}`),
          labelledValue('Dateiname', data.fileName),
          labelledValue('SFTP-Server', data.destinationLabel),
          labelledValue('Bestelldatum', orderDate),
        ]}Anzahl Artikel: <strong>${data.itemCount}</strong></p>`,
      ),
    ]),
    text: textEmail(title, [
      greeting,
      uploaded,
      '',
      `Bestellnummer:  #${String(data.orderId)}`,
      `Dateiname:      ${data.fileName}`,
      `SFTP-Server:    ${data.destinationLabel}`,
      `Bestelldatum:   ${orderDate}`,
      `Anzahl Artikel: ${String(data.itemCount)}`,
    ]),
  }
}
