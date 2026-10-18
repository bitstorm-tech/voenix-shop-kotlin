import { contentSection, markup, htmlEmail, row, textEmail, type RenderedEmail } from './layout'

const WARNING =
  'Falls du diese Änderung nicht selbst angefordert hast, ändere bitte umgehend dein Passwort.'

/** The warning to the *old* address that a change to `newEmail` was requested. */
export function renderChangeEmailNotification(data: {
  recipient: string
  newEmail: string
}): RenderedEmail<'CHANGE_EMAIL_NOTIFICATION'> {
  return {
    kind: 'CHANGE_EMAIL_NOTIFICATION',
    recipient: data.recipient,
    recipientName: null,
    subject: 'Änderung deiner E-Mail-Adresse angefordert',
    html: htmlEmail(
      'Diese E-Mail wurde automatisch versendet, weil eine Änderung deiner E-Mail-Adresse bei Voenix Shop angefordert wurde.',
      [
        contentSection([
          markup`<h2>E-Mail-Änderung angefordert</h2>`,
          markup`<p>Es wurde eine Änderung deiner E-Mail-Adresse zu <strong>${data.newEmail}</strong> angefordert. Wenn die neue Adresse bestätigt wird, wird diese E-Mail-Adresse nicht mehr mit deinem Konto verknüpft sein.</p>`,
        ]),
        row('0 32px 24px', markup`<p>${WARNING}</p>`),
      ],
    ),
    text: textEmail('E-Mail-Änderung angefordert', [
      `Es wurde eine Änderung deiner E-Mail-Adresse zu ${data.newEmail} angefordert.`,
      'Wenn die neue Adresse bestätigt wird, wird diese E-Mail-Adresse nicht mehr mit deinem Konto verknüpft sein.',
      '',
      WARNING,
    ]),
  }
}
