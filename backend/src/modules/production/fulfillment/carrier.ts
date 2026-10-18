/**
 * The carriers a shipment may name, the same list the CHECK of `production_jobs.shipping_carrier`
 * holds, and the tracking page each one has (decision J2 of issue #119). The shipping mail leaves
 * under the shop's name, so its link is built here from a template and the number; no caller ever
 * supplies a URL. `OTHER` has no page: the mail shows the number as text.
 */
export const SHIPPING_CARRIERS = [
  'DHL',
  'DPD',
  'GLS',
  'HERMES',
  'UPS',
  'DEUTSCHE_POST',
  'OTHER',
] as const
export type ShippingCarrier = (typeof SHIPPING_CARRIERS)[number]

const CARRIERS: Record<ShippingCarrier, { displayName: string | null; template: string | null }> = {
  DHL: {
    displayName: 'DHL',
    template: 'https://www.dhl.de/de/privatkunden/pakete-empfangen/verfolgen.html?piececode=',
  },
  DPD: { displayName: 'DPD', template: 'https://tracking.dpd.de/status/de_DE/parcel/' },
  GLS: { displayName: 'GLS', template: 'https://gls-group.com/DE/de/paketverfolgung?match=' },
  HERMES: {
    displayName: 'Hermes',
    template: 'https://www.myhermes.de/empfangen/sendungsverfolgung/sendungsinformation/#',
  },
  UPS: { displayName: 'UPS', template: 'https://www.ups.com/track?loc=de_DE&tracknum=' },
  DEUTSCHE_POST: {
    displayName: 'Deutsche Post',
    template: 'https://www.deutschepost.de/de/s/sendungsverfolgung.html?piececode=',
  },
  OTHER: { displayName: null, template: null },
}

/** The carrier of an exact stored or submitted name, or `null` when it is not one of ours. */
export const carrierOf = (name: string | null): ShippingCarrier | null =>
  SHIPPING_CARRIERS.find((carrier) => carrier === name) ?? null

/** How the carrier is named to a customer; `null` where the shop has no name to print. */
export const carrierDisplayName = (carrier: ShippingCarrier): string | null =>
  CARRIERS[carrier].displayName

/**
 * The carrier's tracking page for `trackingNumber`, or `null` without a template or a number. The
 * number is percent-encoded the way a form encoder does it, with a space as `%20` rather than `+`,
 * because two of the templates end in a path segment and a fragment where `+` is a literal plus.
 */
export function trackingUrl(
  carrier: ShippingCarrier,
  trackingNumber: string | null,
): string | null {
  const { template } = CARRIERS[carrier]
  if (template === null || trackingNumber === null || trackingNumber.trim() === '') return null
  const encoded = encodeURIComponent(trackingNumber).replace(
    /[!'()~]/g,
    (character) => `%${character.charCodeAt(0).toString(16).toUpperCase()}`,
  )
  return template + encoded
}

/**
 * The carrier a fulfillment channel reported, mapped case- and separator-insensitively onto the
 * list ("Deutsche Post", "deutsche_post"), and `OTHER` for anything else: never a guess.
 */
export function carrierOfReportedName(name: string | null): ShippingCarrier {
  const normalized = (name ?? '')
    .trim()
    .toUpperCase()
    .replace(/[^A-Z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '')
  return (
    SHIPPING_CARRIERS.find((carrier) => carrier !== 'OTHER' && carrier === normalized) ?? 'OTHER'
  )
}
