import {
  actionSection,
  contentSection,
  explanationSection,
  markup,
  htmlEmail,
  textEmail,
  type RenderedEmail,
} from './layout'

/**
 * The link that confirms a requested address change. It goes to the *new* address, which is
 * therefore both the recipient and the address the copy names.
 */
export function renderChangeEmailConfirmation(data: {
  recipient: string
  confirmationUrl: string
}): RenderedEmail<'CHANGE_EMAIL_CONFIRMATION'> {
  const url = data.confirmationUrl
  const newEmail = data.recipient
  return {
    kind: 'CHANGE_EMAIL_CONFIRMATION',
    recipient: data.recipient,
    recipientName: null,
    subject: 'Bitte bestätige deine neue E-Mail-Adresse',
    html: htmlEmail(
      'Falls du diese Änderung nicht angefordert hast, kannst du diese E-Mail ignorieren. Deine E-Mail-Adresse bleibt unverändert.',
      [
        contentSection([
          markup`<h2>E-Mail-Adresse ändern</h2>`,
          markup`<p>Du hast eine Änderung deiner E-Mail-Adresse zu <strong>${newEmail}</strong> angefordert. Bitte bestätige die neue E-Mail-Adresse.</p>`,
        ]),
        actionSection(url, 'E-Mail-Adresse bestätigen'),
        explanationSection(
          url,
          'Dieser Link ist 24 Stunden gültig. Danach musst du die Änderung erneut anfordern.',
        ),
      ],
    ),
    text: textEmail('E-Mail-Adresse ändern', [
      `Du hast eine Änderung deiner E-Mail-Adresse zu ${newEmail} angefordert.`,
      '',
      'Bitte öffne den folgenden Link, um die neue E-Mail-Adresse zu bestätigen:',
      '',
      url,
      '',
      'Dieser Link ist 24 Stunden gültig.',
      '',
      'Falls du diese Änderung nicht angefordert hast, kannst du diese E-Mail ignorieren.',
    ]),
  }
}
