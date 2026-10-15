import { beforeEach, describe, expect, test } from 'bun:test'
import { sql } from 'kysely'
import { write } from '@/platform/db/database'
import type { Settings } from '@/platform/settings'
import { testDatabase } from '@test-support/database'
import type { DeliveryResult, EmailDelivery } from './delivery/sweego'
import { enqueue } from './outbox'
import { renderPasswordReset } from './templates/password-reset'
import { renderProducerPdfNotification } from './templates/producer-pdf-notification'
import type { RenderedEmail } from './templates/layout'
import { createUserEmails, EmailDeliveryError } from './user-emails'
import { createEmailWorker } from './worker'

const database = testDatabase()

const settings = (enabled: boolean): Settings['email'] => ({
  enabled,
  pollIntervalMinutes: 1,
  apiKey: 'test-key',
  fromEmail: 'mail@voenix.shop',
  fromName: 'Voenix Shop',
})

/** The test implementation of `EmailDelivery`: records every mail and answers from a script. */
function recordingDelivery(results: DeliveryResult[] = []) {
  const sent: { email: RenderedEmail; campaignId: string | null }[] = []
  const delivery: EmailDelivery = {
    deliver: (email, campaignId) => {
      sent.push({ email, campaignId })
      return Promise.resolve(results.shift() ?? { kind: 'accepted' })
    },
  }
  return { delivery, sent }
}

const producerMail = (orderId: number) =>
  renderProducerPdfNotification({
    recipient: 'producer@example.com',
    orderId,
    fileName: `ORD-${String(orderId)}.pdf`,
    destinationLabel: 'Produktion',
    orderDate: '2026-07-16',
    itemCount: 1,
    producerName: null,
  })

const enqueueMails = (...orderIds: number[]) =>
  write(database.db, async (trx) => {
    for (const orderId of orderIds) await enqueue(trx, producerMail(orderId))
  })

const jobs = () =>
  database.db
    .selectFrom('email_jobs')
    .select(['payload', 'attempt_count as attempts', 'last_error_code as errorCode'])
    .select(sql<boolean>`sent_at IS NOT NULL`.as('sent'))
    .orderBy('id')
    .execute()

beforeEach(async () => {
  await sql`TRUNCATE email_jobs RESTART IDENTITY`.execute(database.db)
})

describe('outbox.enqueue', () => {
  test('stores the rendered payload inside the caller transaction', async () => {
    await enqueueMails(1)
    const [job] = await jobs()
    expect(job).toEqual({ payload: producerMail(1), attempts: 0, errorCode: null, sent: false })
  })

  test('a failing outer transaction leaves no job', async () => {
    const failure = await write(database.db, async (trx) => {
      await enqueue(trx, producerMail(1))
      throw new Error('the business change failed')
    }).catch((error: unknown) => error)
    expect(failure).toHaveProperty('message', 'the business change failed')
    expect(await jobs()).toEqual([])
  })
})

describe('worker', () => {
  test('a disabled worker leaves jobs untouched; an enabled one sends and marks every job', async () => {
    await enqueueMails(1, 2, 3)
    const { delivery, sent } = recordingDelivery()

    await createEmailWorker({ db: database.db, settings: settings(false), delivery }).runOnce()
    expect(sent).toEqual([])
    expect((await jobs()).map((job) => job.attempts)).toEqual([0, 0, 0])

    await createEmailWorker({ db: database.db, settings: settings(true), delivery }).runOnce()
    expect(sent).toEqual([
      { email: producerMail(1), campaignId: 'voenix-email-1' },
      { email: producerMail(2), campaignId: 'voenix-email-2' },
      { email: producerMail(3), campaignId: 'voenix-email-3' },
    ])
    expect((await jobs()).map(({ sent, attempts }) => ({ sent, attempts }))).toEqual([
      { sent: true, attempts: 1 },
      { sent: true, attempts: 1 },
      { sent: true, attempts: 1 },
    ])

    await createEmailWorker({ db: database.db, settings: settings(true), delivery }).runOnce()
    expect(sent).toHaveLength(3)
  })

  test('a failure keeps the job open with its code, and the next scan retries the stored mail', async () => {
    await enqueueMails(7)
    await sql`UPDATE email_jobs SET attempt_count = 999`.execute(database.db)
    const { delivery, sent } = recordingDelivery([{ kind: 'failed', code: 'PROVIDER_HTTP_503' }])
    const worker = createEmailWorker({ db: database.db, settings: settings(true), delivery })

    await worker.runOnce()
    expect((await jobs())[0]).toMatchObject({
      sent: false,
      attempts: 1000,
      errorCode: 'PROVIDER_HTTP_503',
    })

    await worker.runOnce()
    expect((await jobs())[0]).toMatchObject({ sent: true, attempts: 1001, errorCode: null })
    expect(sent.map((entry) => entry.campaignId)).toEqual(['voenix-email-1', 'voenix-email-1'])
    expect(sent[1]?.email).toEqual(producerMail(7))
  })

  test('start scans at once and stop ends the loop without waiting for the interval', async () => {
    await enqueueMails(1)
    const { delivery, sent } = recordingDelivery()
    const worker = createEmailWorker({ db: database.db, settings: settings(true), delivery })

    worker.start()
    expect(() => {
      worker.start()
    }).toThrow('already started')
    for (let tries = 0; tries < 100 && sent.length === 0; tries++) await Bun.sleep(10)
    await worker.stop()

    expect(sent).toHaveLength(1)
    expect((await jobs())[0]?.sent).toBe(true)
  })
})

describe('userEmails.send', () => {
  const mail = renderPasswordReset({
    recipient: 'kunde@example.com',
    resetUrl: 'https://shop.example/reset-password?token=t',
  })

  test('sends once without a campaign id and stores nothing', async () => {
    const { delivery, sent } = recordingDelivery()
    await createUserEmails(settings(true), delivery).send(mail)
    expect(sent).toEqual([{ email: mail, campaignId: null }])
    expect(await jobs()).toEqual([])
  })

  test('is a no-op while email is disabled', async () => {
    const { delivery, sent } = recordingDelivery()
    await createUserEmails(settings(false), delivery).send(mail)
    expect(sent).toEqual([])
  })

  test('a provider failure becomes the secret-free EmailDeliveryError', async () => {
    const { delivery } = recordingDelivery([{ kind: 'failed', code: 'PROVIDER_HTTP_503' }])
    const failure = await createUserEmails(settings(true), delivery)
      .send(mail)
      .catch((error: unknown) => error)
    expect(failure).toBeInstanceOf(EmailDeliveryError)
  })
})
