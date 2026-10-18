import {
  actionSection,
  contentSection,
  explanationSection,
  markup,
  htmlEmail,
  textEmail,
  type RenderedEmail,
} from './layout'

const FOOTER =
  'Falls du dich nicht bei Voenix Shop registriert hast, kannst du diese E-Mail ignorieren.'

/** The link a new customer opens to confirm their address and activate the account. */
export function renderAccountConfirmation(data: {
  recipient: string
  confirmationUrl: string
}): RenderedEmail<'ACCOUNT_CONFIRMATION'> {
  const url = data.confirmationUrl
  return {
    kind: 'ACCOUNT_CONFIRMATION',
    recipient: data.recipient,
    recipientName: null,
    subject: 'Bitte bestätige deine E-Mail-Adresse',
    html: htmlEmail(FOOTER, [
      contentSection([
        markup`<h2>E-Mail-Adresse bestätigen</h2>`,
        markup`<p>Vielen Dank für deine Registrierung bei Voenix Shop! Bitte bestätige deine E-Mail-Adresse, um dein Konto zu aktivieren.</p>`,
      ]),
      actionSection(url, 'E-Mail bestätigen'),
      explanationSection(
        url,
        'Dieser Link ist 24 Stunden gültig. Danach musst du eine neue Bestätigungs-E-Mail anfordern.',
      ),
    ]),
    text: textEmail('E-Mail-Adresse bestätigen', [
      'Vielen Dank für deine Registrierung bei Voenix Shop!',
      '',
      'Bitte öffne den folgenden Link, um deine E-Mail-Adresse zu bestätigen:',
      '',
      url,
      '',
      'Dieser Link ist 24 Stunden gültig.',
      '',
      FOOTER,
    ]),
  }
}
