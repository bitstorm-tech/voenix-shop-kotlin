import { Hono } from 'hono'
import { z } from 'zod'
import { SPOD_ENVIRONMENTS } from '@/modules/spod'
import { requireAdmin } from '@/platform/auth/auth'
import {
  apiError,
  integerParam,
  jsonBody,
  respondFailure,
  respondResult,
} from '@/platform/http/respond'
import type { DestinationDetail } from './repository'
import type { DestinationInput, DestinationService } from './service'

const MAXIMUM_TEXT_LENGTH = 255

/** Required text: missing, `null`, or blank is "is required"; trimmed, then bounded. */
const requiredText = (label: string) =>
  z
    .string({ error: `${label} is required` })
    .trim()
    .min(1, { error: `${label} is required`, abort: true })
    .max(MAXIMUM_TEXT_LENGTH, `${label} must be at most 255 characters`)

/** Optional text: trimmed and bounded; blank, `null`, or omitted is `null`. */
const optionalText = (label: string, maximum = MAXIMUM_TEXT_LENGTH) =>
  z
    .string()
    .trim()
    .max(maximum, `${label} must be at most ${String(maximum)} characters`)
    .nullish()
    .transform((value) => (value === undefined || value === '' ? null : value))

/** A secret is never trimmed: spaces at either end may be part of it. Blank means "none". */
const secret = (label: string, maximum: number) =>
  z
    .string()
    .max(maximum, `${label} must be at most ${String(maximum)} characters`)
    .nullish()
    .transform((value) =>
      value === undefined || value === null || value.trim() === '' ? null : value,
    )

const timeoutSeconds = z
  .int({ error: 'TimeoutSeconds is required' })
  .min(1, 'TimeoutSeconds must be between 1 and 3600')
  .max(3600, 'TimeoutSeconds must be between 1 and 3600')

/** One `@` with text on both sides and no whitespace; deliberately no stricter. */
const hasEmailShape = (email: string) => email === '' || /^[^@\s]+@[^@\s]+$/.test(email)

const sftpBlock = z.strictObject({
  host: requiredText('Host'),
  port: z
    .int('Port must be between 1 and 65535')
    .min(1, 'Port must be between 1 and 65535')
    .max(65535, 'Port must be between 1 and 65535')
    .nullish()
    .transform((value) => value ?? 22),
  username: requiredText('Username'),
  password: secret('Password', MAXIMUM_TEXT_LENGTH),
  hostKeyFingerprint: requiredText('HostKeyFingerprint'),
  remotePath: optionalText('RemotePath', 1024).transform((value) => value ?? '/'),
  timeoutSeconds,
})

const spodBlock = z.strictObject({
  environment: z.enum(SPOD_ENVIRONMENTS, {
    error: (issue) =>
      issue.input === undefined || issue.input === null
        ? 'Environment is required'
        : `Environment must be one of: ${SPOD_ENVIRONMENTS.join(', ')}`,
  }),
  accessToken: secret('AccessToken', 512),
  timeoutSeconds,
})

/**
 * The create and replace body. The channel decides which block the body must carry: `sftp` for
 * `SFTP`, `spod` for `SPOD`, never the other one. A missing or foreign block is a `channel` error,
 * because the channel is what makes the rest of the body right or wrong; an unknown channel says
 * nothing about the blocks. The block rule runs even when other fields are invalid, so one answer
 * names every problem.
 */
export const destinationInput = z
  .strictObject({
    supplierId: z.int({ error: 'SupplierId is required' }).positive('SupplierId must be positive'),
    channel: z
      .string({ error: 'Channel is required' })
      .trim()
      .min(1, { error: 'Channel is required', abort: true })
      .pipe(z.enum(['SFTP', 'SPOD'], { error: 'Channel must be one of: SFTP, SPOD' })),
    label: requiredText('Label'),
    enabled: z
      .boolean()
      .nullish()
      .transform((value) => value ?? true),
    notificationEmail: z
      .string()
      .trim()
      .max(MAXIMUM_TEXT_LENGTH, {
        error: 'NotificationEmail must be at most 255 characters',
        abort: true,
      })
      .refine(hasEmailShape, 'NotificationEmail must be a valid email address')
      .nullish()
      .transform((value) => (value === undefined || value === '' ? null : value)),
    notificationName: optionalText('NotificationName'),
    sftp: sftpBlock.nullish(),
    spod: spodBlock.nullish(),
  })
  .superRefine(
    (body, context) => {
      const blockError = (own: unknown, foreign: unknown, channel: string, foreignName: string) => {
        if (own === undefined || own === null) {
          context.addIssue({
            code: 'custom',
            path: ['channel'],
            message: `${channel} destinations require the ${channel.toLowerCase()} block`,
          })
        }
        if (foreign !== undefined && foreign !== null) {
          context.addIssue({
            code: 'custom',
            path: ['channel'],
            message: `${channel} destinations must not carry the ${foreignName} block`,
          })
        }
      }
      if (body.channel === 'SFTP') blockError(body.sftp, body.spod, 'SFTP', 'spod')
      if (body.channel === 'SPOD') blockError(body.spod, body.sftp, 'SPOD', 'sftp')
    },
    { when: (payload) => typeof payload.value === 'object' && payload.value !== null },
  )
  .transform((body): DestinationInput => {
    const { sftp, spod } = body
    let detail: DestinationDetail
    let secretValue: string | null
    if (body.channel === 'SFTP' && sftp) {
      const { password, ...rest } = sftp
      detail = { channel: 'SFTP', ...rest }
      secretValue = password
    } else if (body.channel === 'SPOD' && spod) {
      detail = {
        channel: 'SPOD',
        environment: spod.environment,
        timeoutSeconds: spod.timeoutSeconds,
      }
      secretValue = spod.accessToken
    } else {
      throw new Error('A validated destination body without the block of its channel')
    }
    return {
      supplierId: body.supplierId,
      label: body.label,
      enabled: body.enabled,
      notificationEmail: body.notificationEmail,
      notificationName: body.notificationName,
      detail,
      secret: secretValue,
    }
  })

const DESTINATION_RESPONSES = {
  notFound: 'Production destination not found',
  conflict: 'Production destination is in use and cannot be deleted; disable it instead',
}
const destinationId = integerParam('id', 'Invalid production destination id')

export function destinationRoutes(destinations: DestinationService): Hono {
  const admin = new Hono()
  admin.use(requireAdmin)
  admin.get('/', async (c) => c.json(await destinations.list()))
  admin.post('/', jsonBody(destinationInput), async (c) => {
    const result = await destinations.create(c.req.valid('json'))
    if (result.kind !== 'ok') return respondFailure(c, result, DESTINATION_RESPONSES)
    c.header('Location', `/api/admin/production/destinations/${String(result.value.id)}`)
    return c.json(result.value, 201)
  })
  admin.get('/:id', destinationId, async (c) =>
    respondResult(c, await destinations.get(c.req.valid('param').id), DESTINATION_RESPONSES),
  )
  admin.put('/:id', destinationId, jsonBody(destinationInput), async (c) =>
    respondResult(
      c,
      await destinations.update(c.req.valid('param').id, c.req.valid('json')),
      DESTINATION_RESPONSES,
    ),
  )
  // The admin waits for the run: a sync is a few seconds of partner calls, not a job to watch. A
  // run that happened answers 200 even when its report says FAILED.
  admin.post('/:id/sync-articles', destinationId, async (c) => {
    const result = await destinations.syncArticles(c.req.valid('param').id)
    switch (result.kind) {
      case 'reported':
        return c.json(result.report)
      case 'notFound':
        return c.json(apiError(DESTINATION_RESPONSES.notFound), 404)
      case 'notSyncable':
        return c.json(
          apiError(
            'Only print-on-demand destinations have a SPOD catalog to sync',
            {},
            'CHANNEL_WITHOUT_CATALOG',
          ),
          409,
        )
      case 'busy':
        return c.json(
          apiError(
            'This destination is already syncing; wait for that run to finish',
            {},
            'SYNC_RUNNING',
          ),
          409,
        )
    }
  })
  admin.delete('/:id', destinationId, async (c) => {
    const result = await destinations.delete(c.req.valid('param').id)
    if (result.kind !== 'ok') return respondFailure(c, result, DESTINATION_RESPONSES)
    return c.body(null, 204)
  })

  const routes = new Hono()
  routes.route('/api/admin/production/destinations', admin)
  return routes
}
