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
  'Falls du keinen Lieferantenzugang bei Voenix Shop erwartest, kannst du diese E-Mail ignorieren.'
const VALIDITY =
  'Dieser Link ist 24 Stunden gültig. Danach kannst du über „Passwort vergessen“ einen neuen Link anfordern.'

/**
 * The invitation to a supplier login an administrator created. It links to the same set-password
 * page as the password reset but has its own copy: the reset says "you requested", and nobody
 * requested this mail.
 */
export function renderSupplierInvitation(data: {
  recipient: string
  invitationUrl: string
}): RenderedEmail<'SUPPLIER_INVITATION'> {
  const url = data.invitationUrl
  return {
    kind: 'SUPPLIER_INVITATION',
    recipient: data.recipient,
    recipientName: null,
    subject: 'Dein Zugang zum Voenix Lieferantenportal',
    html: htmlEmail(FOOTER, [
      contentSection([
        markup`<h2>Lieferantenzugang einrichten</h2>`,
        markup`<p>Voenix Shop hat für diese E-Mail-Adresse einen Lieferantenzugang angelegt. Bitte vergib über den Link dein Passwort, um den Zugang zu nutzen.</p>`,
      ]),
      actionSection(url, 'Passwort festlegen'),
      explanationSection(url, VALIDITY),
    ]),
    text: textEmail('Lieferantenzugang einrichten', [
      'Voenix Shop hat für diese E-Mail-Adresse einen Lieferantenzugang angelegt.',
      '',
      'Bitte öffne den folgenden Link, um dein Passwort festzulegen:',
      '',
      url,
      '',
      VALIDITY,
      '',
      FOOTER,
    ]),
  }
}
