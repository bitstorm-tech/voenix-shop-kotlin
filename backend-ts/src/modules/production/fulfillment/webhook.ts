import { timingSafeEqual } from 'node:crypto'
import { z } from 'zod'
import { createLogger } from '@/platform/logging'
import { errorClass } from '../orders'
import type { ReportedRemoteState } from '../spod/repository'
import { carrierOfReportedName } from './carrier'
import type { Shipment } from './repository'

/**
 * One event the print-on-demand partner reported, reduced to bounded values. The job is named by
 * the partner's order id, this shop's `ORD-{orderId}-JOB-{jobId}` reference, or both.
 */
export type SpodWebhookEvent = {
  externalReference: string | null
  shopReference: string | null
} & ({ kind: 'shipment'; shipment: Shipment } | { kind: 'remoteState'; state: ReportedRemoteState })

/** The width of the columns a reported value is stored in or compared against. */
const MAXIMUM_LENGTH = 128

const logger = createLogger('production-webhook')

/**
 * The partner sends ids as numbers in some fields and as strings in others, so both read as text;
 * a value of any other shape is treated as absent rather than failing the event.
 */
const text = z
  .union([z.string(), z.number()])
  .transform(String)
  .nullish()
  .catch(null)
  .transform((value) => value ?? null)

const orderPart = z
  .object({ orderId: text, externalOrderReference: text, carrier: text, trackingCode: text })
  .nullish()
  .catch(null)
  .transform((value) => value ?? null)

/**
 * The few fields this shop reads, nothing else: unknown fields are ignored, and the partner's
 * `trackingUrl` is not a field at all, so it can never be stored. The shop builds its own links.
 */
const payload = z.object({
  eventType: text,
  shipment: orderPart,
  order: orderPart,
  externalOrderReference: text,
})

/** Trimmed, non-empty, and within the column width, or `null`. */
const bounded = (value: string | null) => {
  const trimmed = value?.trim() ?? ''
  return trimmed === '' || trimmed.length > MAXIMUM_LENGTH ? null : trimmed
}

/**
 * The event, or `null` for anything this shop does not act on: an unknown event type, a payload
 * that names no order, or a body that is not JSON. A parse failure is logged by its class only,
 * because its message quotes the untrusted body.
 */
export function parseSpodWebhookEvent(body: string): SpodWebhookEvent | null {
  let parsed: z.output<typeof payload>
  try {
    parsed = payload.parse(JSON.parse(body))
  } catch (error) {
    logger.warn({ errorClass: errorClass(error) }, 'A SPOD webhook body could not be read')
    return null
  }
  const reference = (part: z.output<typeof orderPart>) => {
    const externalReference = bounded(part?.orderId ?? null)
    const shopReference =
      bounded(part?.externalOrderReference ?? null) ?? bounded(parsed.externalOrderReference)
    return externalReference === null && shopReference === null
      ? null
      : { externalReference, shopReference }
  }
  switch (parsed.eventType) {
    case 'Shipment.sent': {
      const named = reference(parsed.shipment)
      if (named === null) return null
      const reported = parsed.shipment?.carrier?.trim() ?? ''
      const trackingNumber = bounded(parsed.shipment?.trackingCode ?? null)
      return {
        ...named,
        kind: 'shipment',
        shipment: {
          carrier: carrierOfReportedName(reported),
          reportedCarrierName: reported === '' ? null : reported.slice(0, MAXIMUM_LENGTH),
          trackingNumber:
            trackingNumber === null || /\p{Cc}/u.test(trackingNumber) ? null : trackingNumber,
        },
      }
    }
    case 'Order.cancelled':
    case 'Order.needs-action': {
      const named = reference(parsed.order)
      if (named === null) return null
      const state = parsed.eventType === 'Order.cancelled' ? 'CANCELLED' : 'NEEDS_ACTION'
      return { ...named, kind: 'remoteState', state }
    }
    default:
      return null
  }
}

/**
 * The secret in the path against the configured one in constant time. A length difference answers
 * at once, which is acceptable: the length of the configured secret is not the secret.
 */
export function secretMatches(candidate: string, expected: string): boolean {
  const actual = Buffer.from(candidate, 'utf8')
  const wanted = Buffer.from(expected, 'utf8')
  return actual.length === wanted.length && timingSafeEqual(actual, wanted)
}
