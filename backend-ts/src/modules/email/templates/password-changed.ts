import { contentSection, markup, htmlEmail, row, textEmail, type RenderedEmail } from './layout'

const WARNING =
  'Falls du diese Änderung nicht selbst vorgenommen hast, kontaktiere uns bitte umgehend.'

/** The notice that the account's password was just changed. */
export function renderPasswordChanged(data: {
  recipient: string
}): RenderedEmail<'PASSWORD_CHANGED'> {
  return {
    kind: 'PASSWORD_CHANGED',
    recipient: data.recipient,
    recipientName: null,
    subject: 'Dein Passwort wurde geändert',
    html: htmlEmail(
      'Diese E-Mail wurde automatisch versendet, weil dein Passwort bei Voenix Shop geändert wurde.',
      [
        contentSection([
          markup`<h2>Passwort geändert</h2>`,
          markup`<p>Dein Passwort wurde soeben erfolgreich geändert.</p>`,
        ]),
        row('0 32px 24px', markup`<p>${WARNING}</p>`),
      ],
    ),
    text: textEmail('Passwort geändert', [
      'Dein Passwort wurde soeben erfolgreich geändert.',
      '',
      WARNING,
    ]),
  }
}
