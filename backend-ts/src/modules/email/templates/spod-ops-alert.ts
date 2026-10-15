import {
  contentSection,
  markup,
  htmlEmail,
  labelledValue,
  row,
  textEmail,
  type RenderedEmail,
} from './layout'

/** Why an operator has to look at a print-on-demand job. */
export type SpodOpsAlertReason =
  /** The partner cancelled the order. */
  | 'CANCELLED'
  /** The partner flagged the order as needing action. */
  | 'NEEDS_ACTION'
  /** Two ambiguous creations in a row: the job is quarantined until a human decides. */
  | 'OUTCOME_UNKNOWN'
  /** The partner refuses the order, or the job's master data has to be fixed first. */
  | 'SUBMISSION_BLOCKED'

/**
 * What the mail says, from the bounded reason alone: no provider text ever reaches this template, so
 * the copy is a table of cases rather than a message passed through.
 */
const COPY: Record<SpodOpsAlertReason, { headline: string; instruction: string }> = {
  CANCELLED: {
    headline: 'SPOD hat den Auftrag storniert. Der Auftrag wird nicht produziert.',
    instruction:
      'Bitte im SPOD-Backoffice prüfen und die Bestellung im Shop erstatten oder neu anlegen.',
  },
  NEEDS_ACTION: {
    headline: 'SPOD meldet, dass der Auftrag eine Rückmeldung braucht.',
    instruction: 'Bitte im SPOD-Backoffice nachsehen, was dort offen ist.',
  },
  OUTCOME_UNKNOWN: {
    headline:
      'Zweimal in Folge blieb offen, ob bei SPOD ein Auftrag angelegt wurde. Der Auftrag wurde deshalb angehalten.',
    instruction:
      'Bitte im SPOD-Backoffice nachsehen, ob ein Auftrag mit dieser Bestellnummer existiert, bevor der Auftrag wieder freigegeben wird.',
  },
  SUBMISSION_BLOCKED: {
    headline: 'Der Auftrag kann bei SPOD nicht angelegt werden und kommt von allein nicht weiter.',
    instruction:
      'Bitte den Fehlercode des Auftrags in der Logistik prüfen: entweder die Stammdaten korrigieren oder die Bestellung erstatten.',
  },
}

const TITLE = 'Ein Print-on-Demand-Auftrag braucht eine Entscheidung'

/**
 * The one mail the shop sends to itself, to its operations address: a work item about one
 * print-on-demand job. It carries no customer data and no link, and it addresses no person, so it
 * has no recipient name.
 */
export function renderSpodOpsAlert(data: {
  recipient: string
  jobId: number
  orderId: number
  reason: SpodOpsAlertReason
  /** The partner's own order id, `null` while none is known. */
  externalReference: string | null
}): RenderedEmail<'SPOD_OPS_ALERT'> {
  const { headline, instruction } = COPY[data.reason]
  const job = `#${String(data.jobId)}`
  const order = `ORD-${String(data.orderId)}`
  const reference = data.externalReference ?? 'noch keine'
  return {
    kind: 'SPOD_OPS_ALERT',
    recipient: data.recipient,
    recipientName: null,
    subject: `Aktion nötig: Produktionsauftrag ${job} (Bestellung ${order})`,
    html: htmlEmail(
      'Diese Nachricht kommt vom Voenix Shop und richtet sich an den Betrieb, nicht an Kundinnen und Kunden.',
      [
        contentSection([
          markup`<h2>${TITLE}</h2>`,
          markup`<p>${headline}</p>`,
          markup`<p>${instruction}</p>`,
        ]),
        row(
          '8px 32px 24px',
          markup`<p>${[
            labelledValue('Produktionsauftrag', job),
            labelledValue('Bestellnummer', order),
            labelledValue('SPOD-Auftragsnummer', reference),
          ]}</p>`,
        ),
      ],
    ),
    text: textEmail(TITLE, [
      headline,
      instruction,
      '',
      `Produktionsauftrag:   ${job}`,
      `Bestellnummer:        ${order}`,
      `SPOD-Auftragsnummer:  ${reference}`,
    ]),
  }
}
