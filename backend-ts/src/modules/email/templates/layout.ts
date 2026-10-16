/**
 * The frame every mail shares: the rendered result, the HTML builder that escapes every dynamic
 * value, the branded HTML layout with its sections, the plain-text layout, and the German date and
 * money formatting. One template file per mail builds on these.
 */

/** The mails a user action sends directly through `userEmails.send`. */
export type UserEmailKind =
  | 'ACCOUNT_CONFIRMATION'
  | 'CHANGE_EMAIL_CONFIRMATION'
  | 'PASSWORD_RESET'
  | 'SUPPLIER_INVITATION'
  | 'PASSWORD_CHANGED'
  | 'CHANGE_EMAIL_NOTIFICATION'

/** The mails an owning module stores in the outbox with `outbox.enqueue`. */
export type QueuedEmailKind =
  | 'ORDER_CONFIRMATION'
  | 'PRODUCER_PDF_NOTIFICATION'
  | 'SHIPPING_NOTIFICATION'
  | 'SPOD_OPS_ALERT'
  | 'OPERATIONS_ALERT'

export type EmailKind = UserEmailKind | QueuedEmailKind

/**
 * A complete mail, ready for the provider. It is also the outbox payload: stored as JSON exactly
 * like this, and sent as stored. `kind` is for the log lines; `recipientName` is the display name
 * Sweego shows next to the address, `null` where the mail addresses no person.
 */
export interface RenderedEmail<K extends EmailKind = EmailKind> {
  kind: K
  recipient: string
  recipientName: string | null
  subject: string
  html: string
  text: string
}

/** HTML that is already safe to emit: built by `markup`, never from a raw string. */
export interface Html {
  readonly safe: string
}

type HtmlValue = string | number | Html | Html[] | null

/**
 * Builds HTML from a template literal. Every interpolated string or number is escaped, so a name, an
 * address, or a link can never break out of its element or attribute; an `Html` value (or a list of
 * them) is inserted as it is, and `null` inserts nothing. Keep every dynamic value on this path.
 */
export function markup(strings: TemplateStringsArray, ...values: HtmlValue[]): Html {
  let safe = strings[0] ?? ''
  values.forEach((value, index) => {
    safe += insert(value) + (strings[index + 1] ?? '')
  })
  return { safe }
}

function insert(value: HtmlValue): string {
  if (value === null) return ''
  if (typeof value === 'number') return String(value)
  if (typeof value === 'string') return escape(value)
  if (Array.isArray(value)) return value.map((part) => part.safe).join('')
  return value.safe
}

function escape(value: string): string {
  return value
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;')
}

const presentation = markup`role="presentation" cellpadding="0" cellspacing="0"`

/** The branded document: header, the template's table rows, and the footer sentence. */
export function htmlEmail(footer: string, rows: Html[]): string {
  return (
    '<!DOCTYPE html>\n' +
    markup`<html lang="de"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1.0"><title>Voenix Shop</title></head><body style="margin:0;padding:0;background:#f4f4f5;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif"><table ${presentation} style="width:100%;background:#f4f4f5"><tr><td style="padding:24px 0"><table ${presentation} style="width:600px;max-width:100%;margin:0 auto;background:#fff;border-radius:8px;overflow:hidden"><tr><td style="background:#18181b;color:#fff;padding:24px 32px;text-align:center"><h1 style="margin:0;font-size:24px;font-weight:600">Voenix Shop</h1></td></tr>${rows}<tr><td style="background:#f4f4f5;padding:24px 32px;text-align:center"><p style="margin:0;color:#71717a;font-size:13px;line-height:1.5">${footer}</p><p style="margin:8px 0 0;color:#a1a1aa;font-size:12px">© Voenix Shop</p></td></tr></table></td></tr></table></body></html>`
      .safe
  )
}

/** One table row with the given cell padding. */
export function row(padding: string, content: Html | Html[]): Html {
  return markup`<tr><td style="padding:${padding}">${content}</td></tr>`
}

/** The opening section: heading and the first paragraphs. */
export function contentSection(content: Html[]): Html {
  return row('32px 32px 16px', content)
}

/** The centred action button. */
export function actionSection(actionUrl: string, label: string): Html {
  return markup`<tr><td style="padding:0 32px 24px;text-align:center"><a href="${actionUrl}" style="display:inline-block;background:#18181b;color:#fff;padding:14px 32px;border-radius:6px;text-decoration:none">${label}</a></td></tr>`
}

/** The copyable link under the button, for mail clients that do not render it, and its validity. */
export function explanationSection(actionUrl: string, validity: string): Html {
  return row('0 32px 24px', [
    markup`<p>Falls der Button nicht funktioniert, kopiere diesen Link in deinen Browser:</p>`,
    markup`<p style="word-break:break-all">${actionUrl}</p>`,
    markup`<p>${validity}</p>`,
  ])
}

/** One `Label: <strong>value</strong><br>` line of a detail block. */
export function labelledValue(label: string, value: string | number): Html {
  return markup`${label}: <strong>${value}</strong><br>`
}

const HEADING_SEPARATOR = '='.repeat(40)
export const SEPARATOR = '-'.repeat(40)

/** The plain-text variant: the title, its underline, a blank line, then the lines. */
export function textEmail(title: string, lines: string[]): string {
  return [title, HEADING_SEPARATOR, '', ...lines].join('\n').trimEnd()
}

/** The German short date of an ISO calendar date: `2026-08-14` becomes `14.08.2026`. */
export function formatDate(isoDate: string): string {
  const [year, month, day] = isoDate.split('-')
  return `${day ?? ''}.${month ?? ''}.${year ?? ''}`
}

/** A non-negative amount in cents as German money: `123450` becomes `1.234,50 €`. */
export function formatPrice(cents: number): string {
  const euros = String(Math.floor(cents / 100)).replace(/\B(?=(\d{3})+(?!\d))/g, '.')
  return `${euros},${String(cents % 100).padStart(2, '0')} €`
}

/**
 * The sentence the order confirmation and the shipping notification both say about their link: the
 * mail is the handle to the order, the link does not expire, and whoever holds it can read this one
 * order. One owner, so the second copy cannot drift from the first.
 */
export const DURABLE_LINK_HINT =
  'Bewahre diese E-Mail auf: Der Link bleibt dauerhaft gültig und zeigt dir jederzeit den ' +
  'aktuellen Stand dieser Bestellung, auch ohne Konto. Wer den Link hat, kann diese ' +
  'eine Bestellung ansehen — gib ihn deshalb nicht weiter.'
