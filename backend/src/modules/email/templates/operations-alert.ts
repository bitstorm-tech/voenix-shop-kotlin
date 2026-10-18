import {
  contentSection,
  labelledValue,
  markup,
  htmlEmail,
  row,
  SEPARATOR,
  textEmail,
  type Html,
  type RenderedEmail,
} from './layout'

/** The finding kinds the operations worker mails; the listed-only kinds never reach this template. */
export type OperationsAlertKind =
  | 'PAYMENT_UNRESOLVED'
  | 'PAYMENT_PAID_ORDER_UNPAID'
  | 'PRODUCTION_REQUEST_STALLED'
  | 'JOB_GENERATION_STALLED'
  | 'DELIVERY_STALLED'
  | 'SPOD_SUBMISSION_STALLED'
  | 'SHIPMENT_OVERDUE'
  | 'EMAIL_STALLED'

/** One new finding of a scan, as the operations module recorded it. */
export interface OperationsAlertFinding {
  kind: OperationsAlertKind
  subjectType: string
  subjectId: number
  /** `null` for a mail enqueued without an order; such a row has no timeline link. */
  orderId: number | null
  since: Date
  attemptCount: number | null
  lastErrorCode: string | null
}

/** What a kind means in German: a table of cases, so no provider text ever reaches the mail. */
const KIND_NAMES: Record<OperationsAlertKind, string> = {
  PAYMENT_UNRESOLVED: 'Zahlung seit über einer Stunde ohne Ergebnis',
  PAYMENT_PAID_ORDER_UNPAID: 'Zahlung bezahlt, Bestellung aber nicht',
  PRODUCTION_REQUEST_STALLED: 'Produktionsanfrage wird nicht aufgeteilt',
  JOB_GENERATION_STALLED: 'Produktions-PDF wird nicht erzeugt',
  DELIVERY_STALLED: 'SFTP-Übertragung kommt nicht an',
  SPOD_SUBMISSION_STALLED: 'SPOD-Auftrag wird nicht angelegt',
  SHIPMENT_OVERDUE: 'Versand seit über sieben Tagen überfällig',
  EMAIL_STALLED: 'E-Mail wird nicht versendet',
}

const TITLE = 'Neue Befunde im Betrieb'
const NONE = 'keine'

const sinceFormat = new Intl.DateTimeFormat('de-DE', {
  dateStyle: 'medium',
  timeStyle: 'short',
  timeZone: 'Europe/Berlin',
})

/**
 * The operations alert: one mail per scan of the operations worker, to the operations address,
 * listing every new finding of a mailed kind with its order's timeline link. It carries no customer
 * data and no provider text, and it addresses no person, so it has no recipient name.
 */
export function renderOperationsAlert(data: {
  recipient: string
  frontendBaseUrl: string
  findings: readonly OperationsAlertFinding[]
}): RenderedEmail<'OPERATIONS_ALERT'> {
  const count = data.findings.length
  const counted = count === 1 ? '1 neuer Befund' : `${String(count)} neue Befunde`
  const intro = `Neu seit dem letzten Prüflauf: ${counted}. Alle offenen Befunde stehen in der Administration unter „Operations“.`
  const entries = data.findings.map((finding) => ({
    name: KIND_NAMES[finding.kind],
    subject: `${finding.subjectType} ${String(finding.subjectId)}`,
    order: finding.orderId === null ? NONE : `ORD-${String(finding.orderId)}`,
    since: sinceFormat.format(finding.since),
    attempts: finding.attemptCount === null ? '–' : String(finding.attemptCount),
    errorCode: finding.lastErrorCode ?? NONE,
    timeline:
      finding.orderId === null
        ? null
        : `${data.frontendBaseUrl}/admin/orders/${String(finding.orderId)}`,
  }))
  return {
    kind: 'OPERATIONS_ALERT',
    recipient: data.recipient,
    recipientName: null,
    subject: `Betrieb: ${counted}`,
    html: htmlEmail(
      'Diese Nachricht kommt vom Voenix Shop und richtet sich an den Betrieb, nicht an Kundinnen und Kunden.',
      [
        contentSection([markup`<h2>${TITLE}</h2>`, markup`<p>${intro}</p>`]),
        ...entries.map(
          (entry): Html =>
            row(
              '8px 32px 16px',
              markup`<h3>${entry.name}</h3><p>${[
                labelledValue('Betroffen', entry.subject),
                labelledValue('Bestellnummer', entry.order),
                labelledValue('Seit', entry.since),
                labelledValue('Versuche', entry.attempts),
                labelledValue('Fehlercode', entry.errorCode),
              ]}${
                entry.timeline === null
                  ? null
                  : markup`<a href="${entry.timeline}">Verlauf der Bestellung öffnen</a>`
              }</p>`,
            ),
        ),
      ],
    ),
    text: textEmail(TITLE, [
      intro,
      ...entries.flatMap((entry) => [
        '',
        SEPARATOR,
        entry.name,
        `Betroffen:      ${entry.subject}`,
        `Bestellnummer:  ${entry.order}`,
        `Seit:           ${entry.since}`,
        `Versuche:       ${entry.attempts}`,
        `Fehlercode:     ${entry.errorCode}`,
        ...(entry.timeline === null ? [] : [`Verlauf:        ${entry.timeline}`]),
      ]),
    ]),
  }
}
