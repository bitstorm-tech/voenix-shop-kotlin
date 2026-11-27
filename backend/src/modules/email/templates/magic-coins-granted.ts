import {
  actionSection,
  contentSection,
  htmlEmail,
  markup,
  row,
  textEmail,
  type RenderedEmail,
} from './layout'

const TITLE = 'Du hast neue Magic Coins erhalten'

/**
 * Customer notification sent after an admin credits additional Magic Coins (either by approving a
 * request or through a manual grant). Deliberately omits the exact number of coins, matching the
 * storefront where balances are never displayed.
 */
export function renderMagicCoinsGranted(data: {
  recipient: string
  shopUrl: string
}): RenderedEmail<'MAGIC_COINS_GRANTED'> {
  const intro =
    'Gute Nachrichten: Wir haben deinem Konto neue Magic Coins gutgeschrieben. Du kannst jetzt im Voenix Shop wieder neue KI-Designs erstellen.'
  return {
    kind: 'MAGIC_COINS_GRANTED',
    recipient: data.recipient,
    recipientName: null,
    subject: TITLE,
    html: htmlEmail(
      'Diese E-Mail wurde automatisch versendet, weil deinem Konto bei Voenix Shop neue Magic Coins gutgeschrieben wurden.',
      [
        contentSection([markup`<h2>${TITLE}</h2>`, markup`<p>${intro}</p>`]),
        actionSection(data.shopUrl, 'Jetzt weiter gestalten'),
        row(
          '0 32px 24px',
          markup`<p>Falls der Button nicht funktioniert, öffne diesen Link in deinem Browser:<br><span style="word-break:break-all">${data.shopUrl}</span></p>`,
        ),
      ],
    ),
    text: textEmail(TITLE, [intro, '', `Zum Shop: ${data.shopUrl}`]),
  }
}
