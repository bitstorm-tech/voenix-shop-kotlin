import { describe, expect, test } from 'bun:test'
import { renderAccountConfirmation } from './account-confirmation'
import { renderChangeEmailConfirmation } from './change-email-confirmation'
import { renderChangeEmailNotification } from './change-email-notification'
import { DURABLE_LINK_HINT, type RenderedEmail } from './layout'
import {
  renderOperationsAlert,
  type OperationsAlertFinding,
  type OperationsAlertKind,
} from './operations-alert'
import { renderOrderConfirmation, type OrderConfirmationData } from './order-confirmation'
import { renderPasswordChanged } from './password-changed'
import { renderPasswordReset } from './password-reset'
import { renderProducerPdfNotification } from './producer-pdf-notification'
import { renderShippingNotification, type ShippingNotificationData } from './shipping-notification'
import { renderSpodOpsAlert, type SpodOpsAlertReason } from './spod-ops-alert'
import { renderSupplierInvitation } from './supplier-invitation'

/** How the owning modules build a link: the normalized `FRONTEND_BASE_URL` plus their path. */
const BASE_URL = 'https://shop.example'
const recipient = 'kunde@example.com'
const ORDER_URL = `${BASE_URL}/order/abc_DEF-123`

/** The common frame, and the link both as the button target and as text. */
function expectFramedWithLink(mail: RenderedEmail, url: string) {
  expect(mail.html).toStartWith('<!DOCTYPE html>\n<html lang="de">')
  expect(mail.html).toContain(
    '<h1 style="margin:0;font-size:24px;font-weight:600">Voenix Shop</h1>',
  )
  expect(mail.html).toContain(`href="${url.replaceAll('&', '&amp;')}"`)
  expect(mail.text).toContain(url)
}

describe('direct user mails', () => {
  test('account confirmation', () => {
    const url = `${BASE_URL}/confirm-email?userId=1&token=a%2Bb`
    const mail = renderAccountConfirmation({ recipient, confirmationUrl: url })
    expect(mail.subject).toBe('Bitte bestätige deine E-Mail-Adresse')
    expectFramedWithLink(mail, url)
    expect(mail.html).toContain(
      'href="https://shop.example/confirm-email?userId=1&amp;token=a%2Bb"',
    )
    expect(mail.text).toBe(
      [
        'E-Mail-Adresse bestätigen',
        '========================================',
        '',
        'Vielen Dank für deine Registrierung bei Voenix Shop!',
        '',
        'Bitte öffne den folgenden Link, um deine E-Mail-Adresse zu bestätigen:',
        '',
        url,
        '',
        'Dieser Link ist 24 Stunden gültig.',
        '',
        'Falls du dich nicht bei Voenix Shop registriert hast, kannst du diese E-Mail ignorieren.',
      ].join('\n'),
    )
  })

  test('change-email confirmation names the new address, escaped in HTML', () => {
    const url = `${BASE_URL}/confirm-change-email?userId=1&token=t`
    const mail = renderChangeEmailConfirmation({
      recipient: 'kunde&shop@example.com',
      confirmationUrl: url,
    })
    expect(mail.subject).toBe('Bitte bestätige deine neue E-Mail-Adresse')
    expectFramedWithLink(mail, url)
    expect(mail.html).toContain('<strong>kunde&amp;shop@example.com</strong>')
    expect(mail.text).toContain('zu kunde&shop@example.com angefordert.')
  })

  test('password reset', () => {
    const url = `${BASE_URL}/reset-password?email=kunde%40example.com&token=t`
    const mail = renderPasswordReset({ recipient, resetUrl: url })
    expect(mail.subject).toBe('Setze dein Passwort zurück')
    expectFramedWithLink(mail, url)
    expect(mail.html).toContain('Passwort zurücksetzen</a>')
  })

  test('supplier invitation', () => {
    const url = `${BASE_URL}/set-password?email=lieferant%40example.com&token=t`
    const mail = renderSupplierInvitation({ recipient, invitationUrl: url })
    expect(mail.subject).toBe('Dein Zugang zum Voenix Lieferantenportal')
    expectFramedWithLink(mail, url)
    expect(mail.text).toContain('„Passwort vergessen“')
  })

  test('password changed carries no link', () => {
    const mail = renderPasswordChanged({ recipient })
    expect(mail.subject).toBe('Dein Passwort wurde geändert')
    expect(mail.html).not.toContain('href=')
    expect(mail.text).toBe(
      [
        'Passwort geändert',
        '========================================',
        '',
        'Dein Passwort wurde soeben erfolgreich geändert.',
        '',
        'Falls du diese Änderung nicht selbst vorgenommen hast, kontaktiere uns bitte umgehend.',
      ].join('\n'),
    )
  })

  test('change-email notification warns the old address about the new one', () => {
    const mail = renderChangeEmailNotification({ recipient, newEmail: 'neu@example.com' })
    expect(mail.subject).toBe('Änderung deiner E-Mail-Adresse angefordert')
    expect(mail.recipient).toBe(recipient)
    expect(mail.html).toContain('<strong>neu@example.com</strong>')
    expect(mail.html).not.toContain('href=')
  })
})

const order = (overrides: Partial<OrderConfirmationData> = {}): OrderConfirmationData => ({
  recipient,
  orderId: 42,
  orderDate: '2026-12-31',
  orderUrl: ORDER_URL,
  customerFirstName: 'Max',
  shippingAddress: {
    firstName: 'Max',
    lastName: 'Mustermann',
    street: 'Musterstraße',
    houseNumber: '1',
    city: 'Berlin',
    postalCode: '10115',
    country: 'DE',
  },
  billingAddress: {
    firstName: 'Erika',
    lastName: 'Mustermann',
    street: 'Rechnungsweg',
    houseNumber: '9',
    city: 'Hamburg',
    postalCode: '20095',
    country: 'DE',
  },
  items: [{ articleName: 'Tasse', variantName: 'Weiß', quantity: 2, unitPriceInCents: 1500 }],
  subtotalInCents: 3000,
  shippingCostInCents: 0,
  discountInCents: 0,
  totalInCents: 3000,
  ...overrides,
})

describe('order confirmation', () => {
  test('German money, date, free shipping, both addresses, and the permanent link', () => {
    const mail = renderOrderConfirmation(order())
    expect(mail.subject).toBe('Bestellbestätigung #42')
    expect(mail.recipientName).toBe('Max Mustermann')
    expectFramedWithLink(mail, ORDER_URL)
    expect(mail.html).toContain('Bestellung ansehen</a>')
    expect(mail.html).toContain(DURABLE_LINK_HINT)
    expect(mail.html).not.toContain('Rabatt')
    expect(mail.text).toBe(
      [
        'Bestellbestätigung #42',
        '========================================',
        '',
        'Hallo Max,',
        'vielen Dank für deine Bestellung!',
        'Sobald deine Zahlung eingegangen ist und deine Bestellung versendet wurde, erhältst du eine Versandbestätigung per E-Mail.',
        '',
        'Bestellnummer: #42',
        'Bestelldatum:  31.12.2026',
        '',
        'Artikel:',
        '----------------------------------------',
        '  Tasse (Weiß)',
        '    2x 15,00 € = 30,00 €',
        '----------------------------------------',
        'Zwischensumme: 30,00 €',
        'Versand:       Kostenlos',
        'Gesamtbetrag:  30,00 €',
        '',
        'Lieferadresse:',
        '  Max Mustermann',
        '  Musterstraße 1',
        '  10115 Berlin',
        '  DE',
        '',
        'Rechnungsadresse:',
        '  Erika Mustermann',
        '  Rechnungsweg 9',
        '  20095 Hamburg',
        '  DE',
        '',
        'Deine Bestellung ansehen:',
        '',
        ORDER_URL,
        '',
        DURABLE_LINK_HINT,
      ].join('\n'),
    )
  })

  test('a discount gets its line with a leading minus, and thousands are grouped', () => {
    const mail = renderOrderConfirmation(
      order({
        items: [
          { articleName: 'Tasse', variantName: 'Weiß', quantity: 100, unitPriceInCents: 1505 },
        ],
        subtotalInCents: 150_500,
        shippingCostInCents: 490,
        discountInCents: 400,
        totalInCents: 150_590,
      }),
    )
    expect(mail.html).toContain('Rabatt: -4,00 €<br>')
    expect(mail.text).toContain(
      [
        'Zwischensumme: 1.505,00 €',
        'Versand:       4,90 €',
        'Rabatt:        -4,00 €',
        'Gesamtbetrag:  1.505,90 €',
      ].join('\n'),
    )
    expect(mail.text).toContain('    100x 15,05 € = 1.505,00 €')
  })

  test('HTML escapes display values while the text keeps them readable', () => {
    const mail = renderOrderConfirmation(
      order({
        items: [
          {
            articleName: 'Tasse & <Sondermodell>',
            variantName: 'Weiß',
            quantity: 1,
            unitPriceInCents: 0,
          },
        ],
      }),
    )
    expect(mail.html).toContain('Tasse &amp; &lt;Sondermodell&gt;')
    expect(mail.html).not.toContain('<Sondermodell>')
    expect(mail.text).toContain('Tasse & <Sondermodell>')
  })
})

describe('producer PDF notification', () => {
  test('names every value and falls back to a neutral greeting without a producer name', () => {
    const data = {
      recipient,
      orderId: 42,
      fileName: 'ORD-42.pdf',
      destinationLabel: 'Produktion A',
      orderDate: '2026-12-31',
      itemCount: 2,
      producerName: 'Manufaktur Müller',
    }
    const named = renderProducerPdfNotification(data)
    expect(named.subject).toBe('Neue Bestellung #42 – ORD-42.pdf')
    expect(named.html).toContain('Anzahl Artikel: <strong>2</strong>')
    expect(named.html).toContain('Bestelldatum: <strong>31.12.2026</strong><br>')
    expect(named.text).toBe(
      [
        'Neue Bestellung per SFTP übermittelt',
        '========================================',
        '',
        'Hallo Manufaktur Müller,',
        'Eine neue Bestellung wurde per SFTP auf Ihren Server hochgeladen.',
        '',
        'Bestellnummer:  #42',
        'Dateiname:      ORD-42.pdf',
        'SFTP-Server:    Produktion A',
        'Bestelldatum:   31.12.2026',
        'Anzahl Artikel: 2',
      ].join('\n'),
    )

    const unnamed = renderProducerPdfNotification({ ...data, producerName: null })
    expect(unnamed.html).toContain('Sehr geehrte Damen und Herren,')
    expect(unnamed.recipientName).toBe('Produktion A')
  })
})

describe('shipping notification', () => {
  const shipping = (overrides: Partial<ShippingNotificationData> = {}) =>
    renderShippingNotification({
      recipient,
      orderId: 42,
      customerFirstName: 'Erika',
      items: [{ articleName: 'Zaubertasse', variantName: 'Blau', quantity: 2 }],
      orderUrl: ORDER_URL,
      carrierName: null,
      trackingNumber: null,
      trackingUrl: null,
      ...overrides,
    })

  test('announces one package with its tracking link and the order link, and never prices it', () => {
    const trackingUrl = 'https://www.dhl.de/verfolgen.html?piececode=0034043'
    const mail = shipping({ carrierName: 'DHL', trackingNumber: '0034043', trackingUrl })
    expect(mail.subject).toBe('Dein Paket ist unterwegs – Bestellung ORD-42')
    expectFramedWithLink(mail, ORDER_URL)
    expectFramedWithLink(mail, trackingUrl)
    for (const snippet of ['ORD-42 ist unterwegs', 'mehreren Paketen', 'Zaubertasse', 'DHL']) {
      expect(mail.html).toContain(snippet)
      expect(mail.text).toContain(snippet)
    }
    for (const forbidden of ['€', 'Gesamtbetrag', 'abgeschlossen']) {
      expect(mail.html).not.toContain(forbidden)
      expect(mail.text).not.toContain(forbidden)
    }
  })

  test('a number without a link is shown escaped, and no tracking data renders no block', () => {
    const numberOnly = shipping({ trackingNumber: 'XY<42>' })
    expect(numberOnly.html).toContain('Sendungsnummer: <strong>XY&lt;42&gt;</strong>')
    expect(numberOnly.html).not.toContain('Sendung verfolgen')
    expect(numberOnly.text).toContain('Sendungsnummer: XY<42>')

    const nothing = shipping()
    expect(nothing.html).not.toContain('Versanddienstleister')
    expect(nothing.text).not.toContain('Sendungsnummer')
  })
})

describe('SPOD ops alert', () => {
  test('names the job, the order, and the partner id, and links nowhere', () => {
    const mail = renderSpodOpsAlert({
      recipient: 'ops@voenix.shop',
      jobId: 7,
      orderId: 42,
      reason: 'CANCELLED',
      externalReference: 'SPOD-9911',
    })
    expect(mail.subject).toBe('Aktion nötig: Produktionsauftrag #7 (Bestellung ORD-42)')
    expect(mail.recipientName).toBeNull()
    expect(mail.html).not.toContain('href=')
    for (const snippet of ['#7', 'ORD-42', 'SPOD-9911', 'storniert']) {
      expect(mail.html).toContain(snippet)
      expect(mail.text).toContain(snippet)
    }
  })

  test('each reason reads differently, also without a partner id', () => {
    const reasons: SpodOpsAlertReason[] = [
      'CANCELLED',
      'NEEDS_ACTION',
      'OUTCOME_UNKNOWN',
      'SUBMISSION_BLOCKED',
    ]
    const texts = reasons.map(
      (reason) =>
        renderSpodOpsAlert({
          recipient: 'ops@voenix.shop',
          jobId: 7,
          orderId: 42,
          reason,
          externalReference: null,
        }).text,
    )
    expect(new Set(texts).size).toBe(4)
    for (const text of texts) expect(text).toContain('SPOD-Auftragsnummer:  noch keine')
  })
})

describe('operations alert', () => {
  const finding = (
    kind: OperationsAlertKind,
    overrides: Partial<OperationsAlertFinding> = {},
  ): OperationsAlertFinding => ({
    kind,
    subjectType: 'JOB',
    subjectId: 3,
    orderId: 42,
    since: new Date('2026-10-16T10:00:00.000Z'),
    attemptCount: 3,
    lastErrorCode: 'SFTP_CONNECT_FAILED',
    ...overrides,
  })
  const alert = (findings: OperationsAlertFinding[]) =>
    renderOperationsAlert({ recipient: 'ops@voenix.shop', frontendBaseUrl: BASE_URL, findings })

  test('one row per finding, every mailed kind under its own German name', () => {
    const mail = alert([
      finding('PAYMENT_UNRESOLVED', {
        subjectType: 'PAYMENT',
        subjectId: 9,
        orderId: 41,
        attemptCount: null,
        lastErrorCode: null,
      }),
      finding('PAYMENT_PAID_ORDER_UNPAID', {
        subjectType: 'PAYMENT',
        subjectId: 10,
        attemptCount: null,
        lastErrorCode: null,
      }),
      finding('PRODUCTION_REQUEST_STALLED', { subjectType: 'REQUEST', subjectId: 1 }),
      finding('JOB_GENERATION_STALLED', { lastErrorCode: 'MISSING_IMAGE' }),
      finding('DELIVERY_STALLED', { subjectType: 'DELIVERY', subjectId: 2 }),
      finding('SPOD_SUBMISSION_STALLED', { subjectId: 4, attemptCount: 0, lastErrorCode: null }),
      finding('SHIPMENT_OVERDUE', { subjectId: 5, attemptCount: null, lastErrorCode: null }),
      finding('EMAIL_STALLED', {
        subjectType: 'EMAIL',
        subjectId: 6,
        orderId: null,
        lastErrorCode: 'SWEEGO_UNAVAILABLE',
      }),
    ])
    expect(mail.kind).toBe('OPERATIONS_ALERT')
    expect(mail.recipient).toBe('ops@voenix.shop')
    expect(mail.recipientName).toBeNull()
    expect(mail.subject).toBe('Betrieb: 8 neue Befunde')
    expectFramedWithLink(mail, `${BASE_URL}/admin/orders/41`)
    expect(mail.text).toBe(
      [
        'Neue Befunde im Betrieb',
        '========================================',
        '',
        'Neu seit dem letzten Prüflauf: 8 neue Befunde. Alle offenen Befunde stehen in der Administration unter „Operations“.',
        '',
        '----------------------------------------',
        'Zahlung seit über einer Stunde ohne Ergebnis',
        'Betroffen:      PAYMENT 9',
        'Bestellnummer:  ORD-41',
        'Seit:           16.10.2026, 12:00',
        'Versuche:       –',
        'Fehlercode:     keine',
        'Verlauf:        https://shop.example/admin/orders/41',
        '',
        '----------------------------------------',
        'Zahlung bezahlt, Bestellung aber nicht',
        'Betroffen:      PAYMENT 10',
        'Bestellnummer:  ORD-42',
        'Seit:           16.10.2026, 12:00',
        'Versuche:       –',
        'Fehlercode:     keine',
        'Verlauf:        https://shop.example/admin/orders/42',
        '',
        '----------------------------------------',
        'Produktionsanfrage wird nicht aufgeteilt',
        'Betroffen:      REQUEST 1',
        'Bestellnummer:  ORD-42',
        'Seit:           16.10.2026, 12:00',
        'Versuche:       3',
        'Fehlercode:     SFTP_CONNECT_FAILED',
        'Verlauf:        https://shop.example/admin/orders/42',
        '',
        '----------------------------------------',
        'Produktions-PDF wird nicht erzeugt',
        'Betroffen:      JOB 3',
        'Bestellnummer:  ORD-42',
        'Seit:           16.10.2026, 12:00',
        'Versuche:       3',
        'Fehlercode:     MISSING_IMAGE',
        'Verlauf:        https://shop.example/admin/orders/42',
        '',
        '----------------------------------------',
        'SFTP-Übertragung kommt nicht an',
        'Betroffen:      DELIVERY 2',
        'Bestellnummer:  ORD-42',
        'Seit:           16.10.2026, 12:00',
        'Versuche:       3',
        'Fehlercode:     SFTP_CONNECT_FAILED',
        'Verlauf:        https://shop.example/admin/orders/42',
        '',
        '----------------------------------------',
        'SPOD-Auftrag wird nicht angelegt',
        'Betroffen:      JOB 4',
        'Bestellnummer:  ORD-42',
        'Seit:           16.10.2026, 12:00',
        'Versuche:       0',
        'Fehlercode:     keine',
        'Verlauf:        https://shop.example/admin/orders/42',
        '',
        '----------------------------------------',
        'Versand seit über sieben Tagen überfällig',
        'Betroffen:      JOB 5',
        'Bestellnummer:  ORD-42',
        'Seit:           16.10.2026, 12:00',
        'Versuche:       –',
        'Fehlercode:     keine',
        'Verlauf:        https://shop.example/admin/orders/42',
        '',
        '----------------------------------------',
        'E-Mail wird nicht versendet',
        'Betroffen:      EMAIL 6',
        'Bestellnummer:  keine',
        'Seit:           16.10.2026, 12:00',
        'Versuche:       3',
        'Fehlercode:     SWEEGO_UNAVAILABLE',
      ].join('\n'),
    )
    // A finding without an order has no timeline link.
    expect(mail.html.match(/href=/g)).toHaveLength(7)
  })

  test('a single finding names the count in the singular', () => {
    const mail = alert([finding('DELIVERY_STALLED', { subjectType: 'DELIVERY', subjectId: 2 })])
    expect(mail.subject).toBe('Betrieb: 1 neuer Befund')
    expect(mail.html).toContain('<h3>SFTP-Übertragung kommt nicht an</h3>')
    expect(mail.html).toContain(
      '<a href="https://shop.example/admin/orders/42">Verlauf der Bestellung öffnen</a>',
    )
    expect(mail.text).toBe(
      [
        'Neue Befunde im Betrieb',
        '========================================',
        '',
        'Neu seit dem letzten Prüflauf: 1 neuer Befund. Alle offenen Befunde stehen in der Administration unter „Operations“.',
        '',
        '----------------------------------------',
        'SFTP-Übertragung kommt nicht an',
        'Betroffen:      DELIVERY 2',
        'Bestellnummer:  ORD-42',
        'Seit:           16.10.2026, 12:00',
        'Versuche:       3',
        'Fehlercode:     SFTP_CONNECT_FAILED',
        'Verlauf:        https://shop.example/admin/orders/42',
      ].join('\n'),
    )
  })
})
