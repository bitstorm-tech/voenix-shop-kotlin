import type { EmailModule } from '@/modules/email'
import type { GeneratorModule } from '@/modules/generator'
import type { Orders } from '@/modules/order'
import type { ProductionModule } from '@/modules/production'
import { notFound, ok } from '@/platform/operation'
import type { FindingRepository } from './repository'

/** The admin order list shows this many orders, the newest first. */
const ORDER_LIST_SIZE = 50

export type OperationsService = ReturnType<typeof createOperationsService>

/**
 * The two admin reads about orders, each assembled from the owning modules' capabilities and this
 * module's own findings: the timeline of one order and the list of the newest orders; and the two
 * admin actions, each the owning module's: the payment recheck and the SPOD job release. No query
 * here touches another module's table.
 */
export function createOperationsService({
  findings,
  orders,
  production,
  outbox,
  magicCoins = { pendingRequestCount: () => Promise.resolve(0) },
}: {
  findings: Pick<FindingRepository, 'openOfOrder' | 'openCounts' | 'openCount'>
  orders: Pick<Orders, 'adminOrder' | 'adminOrders' | 'recheckPayment'>
  production: Pick<
    ProductionModule,
    'ofOrder' | 'releaseSpodJob' | 'shippingStatuses' | 'unshippedOrderCount'
  >
  outbox: Pick<EmailModule['outbox'], 'ofOrder'>
  magicCoins?: Pick<GeneratorModule, 'pendingRequestCount'>
}) {
  return {
    /**
     * Everything known about one order: the order with its payments and history (order module),
     * its production state (production module), its mails (email module), and its open findings.
     */
    timeline: async (orderId: number) => {
      const order = await orders.adminOrder(orderId)
      if (order === undefined) return notFound
      const [productionOfOrder, emails, open] = await Promise.all([
        production.ofOrder(orderId),
        outbox.ofOrder(orderId),
        findings.openOfOrder(orderId),
      ])
      return ok({
        ...order,
        production: productionOfOrder,
        emails,
        findings: open.map((finding) => ({
          kind: finding.kind,
          subject: { type: finding.subject_type, id: finding.subject_id },
          since: finding.since,
          attemptCount: finding.attempt_count,
          lastErrorCode: finding.last_error_code,
          firstSeenAt: finding.first_seen_at,
          alertedAt: finding.alerted_at,
        })),
      })
    },

    /** The newest orders, optionally of exactly one e-mail address, each with its shipping status and open findings. */
    orders: async (email: string | null) => {
      const list = await orders.adminOrders({ email, limit: ORDER_LIST_SIZE })
      const orderIds = list.map((order) => order.orderId)
      const [counts, shipping] = await Promise.all([
        findings.openCounts(orderIds),
        production.shippingStatuses(orderIds),
      ])
      return list.map((order) => ({
        ...order,
        shippingStatus: shipping.get(order.orderId) ?? null,
        openFindings: counts.get(order.orderId) ?? 0,
      }))
    },

    /** The numbers of the admin menu's badges: paid orders not fully shipped, open findings, pending Magic Coin requests. */
    menuCounts: async () => {
      const [unshippedOrders, openFindings, pendingMagicCoinRequests] = await Promise.all([
        production.unshippedOrderCount(),
        findings.openCount(),
        magicCoins.pendingRequestCount(),
      ])
      return { unshippedOrders, openFindings, pendingMagicCoinRequests }
    },

    /** Asks Mollie about the order's payment now, recorded with the admin's user id. */
    recheckPayment: orders.recheckPayment,

    /** Releases a quarantined SPOD job after the admin looked at the partner's backoffice. */
    releaseSpodJob: production.releaseSpodJob,
  }
}
