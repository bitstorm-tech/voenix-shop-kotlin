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
  'Falls du dein Passwort nicht zurücksetzen möchtest, kannst du diese E-Mail ignorieren.'

/** The link a customer asked for to set a new password. */
export function renderPasswordReset(data: {
  recipient: string
  resetUrl: string
}): RenderedEmail<'PASSWORD_RESET'> {
  const url = data.resetUrl
  return {
    kind: 'PASSWORD_RESET',
    recipient: data.recipient,
    recipientName: null,
    subject: 'Setze dein Passwort zurück',
    html: htmlEmail(FOOTER, [
      contentSection([
        markup`<h2>Passwort zurücksetzen</h2>`,
        markup`<p>Du hast angefordert, dein Passwort bei Voenix Shop zurückzusetzen. Bitte vergib über den Link ein neues Passwort.</p>`,
      ]),
      actionSection(url, 'Passwort zurücksetzen'),
      explanationSection(
        url,
        'Dieser Link ist zeitlich begrenzt gültig. Danach musst du einen neuen Link anfordern.',
      ),
    ]),
    text: textEmail('Passwort zurücksetzen', [
      'Du hast angefordert, dein Passwort bei Voenix Shop zurückzusetzen.',
      '',
      'Bitte öffne den folgenden Link, um ein neues Passwort zu vergeben:',
      '',
      url,
      '',
      'Dieser Link ist zeitlich begrenzt gültig.',
      '',
      FOOTER,
    ]),
  }
}
