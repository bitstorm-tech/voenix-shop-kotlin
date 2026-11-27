import {
  actionSection,
  contentSection,
  htmlEmail,
  labelledValue,
  markup,
  row,
  textEmail,
  type RenderedEmail,
} from './layout'

const TITLE = 'Neue Magic-Coins-Anfrage'

/**
 * The alert sent to the operations mailbox whenever a signed-in customer requests additional Magic
 * Coins. Links straight to the admin Magic Coins page where the operator reviews account signals
 * and approves or rejects the request.
 */
export function renderMagicCoinRequestAlert(data: {
  recipient: string
  frontendBaseUrl: string
  requestId: number
  userEmail: string
}): RenderedEmail<'MAGIC_COIN_REQUEST_ALERT'> {
  const adminUrl = `${data.frontendBaseUrl}/admin/magic-coins`
  const intro =
    'Ein angemeldeter Account hat weitere Magic Coins angefordert. Bitte prüfe die Anfrage im Admin-Dashboard.'
  return {
    kind: 'MAGIC_COIN_REQUEST_ALERT',
    recipient: data.recipient,
    recipientName: null,
    subject: 'Neue Magic-Coins-Anfrage',
    html: htmlEmail(
      'Diese Nachricht kommt vom Voenix Shop und richtet sich an den Betrieb, nicht an Kundinnen und Kunden.',
      [
        contentSection([markup`<h2>${TITLE}</h2>`, markup`<p>${intro}</p>`]),
        row(
          '0 32px 24px',
          markup`<p>${[
            labelledValue('Anfrage', `#${String(data.requestId)}`),
            labelledValue('Account', data.userEmail),
          ]}</p>`,
        ),
        actionSection(adminUrl, 'Anfrage im Admin öffnen'),
      ],
    ),
    text: textEmail(TITLE, [
      intro,
      '',
      `Anfrage:  #${String(data.requestId)}`,
      `Account:  ${data.userEmail}`,
      `Admin:    ${adminUrl}`,
    ]),
  }
}
