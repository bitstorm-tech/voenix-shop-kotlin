import type { Kysely } from 'kysely'
import type { Tables } from '@/platform/db/database'
import type { Fetch } from '@/platform/fetch'
import type { Settings } from '@/platform/settings'
import { createSweegoDelivery } from './delivery/sweego'
import { emailsOfOrder, enqueue, stalledEmails } from './outbox'
import { createUserEmails } from './user-emails'
import { createEmailWorker } from './worker'

export type { EmailKind, QueuedEmailKind, RenderedEmail, UserEmailKind } from './templates/layout'
export type { EmailFinding } from './outbox'
export { EmailDeliveryError } from './user-emails'
export { renderAccountConfirmation } from './templates/account-confirmation'
export { renderChangeEmailConfirmation } from './templates/change-email-confirmation'
export { renderChangeEmailNotification } from './templates/change-email-notification'
export { renderPasswordChanged } from './templates/password-changed'
export { renderPasswordReset } from './templates/password-reset'
export { renderSupplierInvitation } from './templates/supplier-invitation'
export {
  renderOrderConfirmation,
  type OrderConfirmationAddress,
  type OrderConfirmationData,
} from './templates/order-confirmation'
export { renderProducerPdfNotification } from './templates/producer-pdf-notification'
export {
  renderShippingNotification,
  type ShippingNotificationData,
} from './templates/shipping-notification'
export { renderSpodOpsAlert, type SpodOpsAlertReason } from './templates/spod-ops-alert'
export {
  renderOperationsAlert,
  type OperationsAlertFinding,
  type OperationsAlertKind,
} from './templates/operations-alert'

/**
 * The email module, a leaf: an owning module renders a mail with one of the `render…` functions
 * and either sends it directly (`userEmails.send`, for account mails that may carry a secret link)
 * or stores it in its own transaction (`outbox.enqueue`), and the worker delivers what is stored.
 * `outbox.findings` reports the stalled mails to the operations worker, `outbox.ofOrder` lists the
 * mails of one order. The composition root calls `worker.start()` once and `worker.stop()` at
 * shutdown.
 */
export function createEmailModule({
  db,
  settings,
  fetch,
}: {
  db: Kysely<Tables>
  settings: Settings['email']
  fetch: Fetch
}) {
  const delivery = createSweegoDelivery(settings, fetch)
  const worker = createEmailWorker({ db, settings, delivery })
  return {
    userEmails: createUserEmails(settings, delivery),
    outbox: {
      enqueue,
      findings: (now: Date) => stalledEmails(db, now),
      ofOrder: (orderId: number) => emailsOfOrder(db, orderId),
    },
    worker: { start: worker.start, stop: worker.stop },
  }
}

export type EmailModule = ReturnType<typeof createEmailModule>
