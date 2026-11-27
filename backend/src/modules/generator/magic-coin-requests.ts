import { sql, type Kysely } from 'kysely'
import type { AccountDirectory } from '@/modules/account'
import type { CartModule } from '@/modules/cart'
import {
  renderMagicCoinRequestAlert,
  renderMagicCoinsGranted,
  type EmailModule,
} from '@/modules/email'
import type { Orders } from '@/modules/order'
import { read, write, type Tables } from '@/platform/db/database'
import { executePostgresWrite } from '@/platform/db/postgres-write'
import type { Logger } from '@/platform/logging'
import {
  GENERATION_COST,
  grantCoinsInTransaction,
  INITIAL_BALANCE,
  type MagicCoins,
} from './magic-coins'
import type { MagicCoinRequestStatus } from './schema'

/** Maximum number of decided requests returned in the admin history list. */
const DECIDED_LIST_LIMIT = 100

/** What a signed-in user's out-of-coins popup needs to know, without revealing a coin count. */
export type CustomerMagicCoinRequestStatus = 'CAN_REQUEST' | 'PENDING' | 'BLOCKED'

/** One Magic Coin refill request as shown in the admin dashboard, enriched with verification signals. */
export interface AdminMagicCoinRequest {
  id: number
  userId: number
  userEmail: string
  userName: string | null
  accountCreatedAt: Date | null
  currentBalance: number
  coinsFirstSpentAt: Date | null
  coinsLastUpdatedAt: Date | null
  paidOrderCount: number
  activeCartItemCount: number
  totalRequestCount: number
  totalApprovedCoins: number
  status: MagicCoinRequestStatus
  grantedCoins: number | null
  blockedUser: boolean
  decidedByEmail: string | null
  createdAt: Date
  decidedAt: Date | null
  isCurrentlyBlocked: boolean
}

/** One blocked account as shown in the admin dashboard. */
export interface AdminBlockedMagicCoinAccount {
  userId: number
  userEmail: string
  userName: string | null
  accountCreatedAt: Date | null
  currentBalance: number
  paidOrderCount: number
  blockedAt: Date
  blockedByEmail: string | null
}

export type SubmitRequestOutcome =
  | { kind: 'created'; request: { id: number; status: 'PENDING'; createdAt: Date } }
  | { kind: 'blocked' }
  | { kind: 'alreadyPending' }
  | { kind: 'coinsAvailable' }

export type ApproveRequestOutcome =
  | {
      kind: 'approved'
      id: number
      userId: number
      grantedCoins: number
      newBalance: number
      decidedAt: Date
    }
  | { kind: 'notFound' }
  | { kind: 'alreadyDecided' }

export type RejectRequestOutcome =
  | { kind: 'rejected'; id: number; userId: number; blockedUser: boolean; decidedAt: Date }
  | { kind: 'notFound' }
  | { kind: 'alreadyDecided' }

export type ManualGrantOutcome =
  | {
      kind: 'granted'
      userId: number
      userEmail: string
      grantedCoins: number
      newBalance: number
    }
  | { kind: 'userNotFound' }

export type MagicCoinRequestsService = ReturnType<typeof createMagicCoinRequestsService>

/**
 * Handles customer refill requests and the admin review workflow:
 *
 * - Signed-in customers whose balance reached zero may submit at most one `PENDING` request at a
 *   time (`ux_magic_coin_requests_pending_user`), unless permanently blocked by an admin.
 * - Submitting a request atomically enqueues one alert mail per address in `alertEmails`
 *   (`OPERATIONS_ALERT_EMAIL`), or logs when absent.
 * - Approving a request (or granting manually) atomically credits the coins and enqueues a customer
 *   mail without mentioning a specific coin count.
 * - Rejecting never sends a customer mail; rejecting with `blockUser: true` records a block row.
 */
export function createMagicCoinRequestsService({
  db,
  coins,
  accounts,
  orders,
  carts,
  outbox,
  alertEmails,
  frontendBaseUrl,
  logger,
}: {
  db: Kysely<Tables>
  coins: Pick<MagicCoins, 'readUserSummaries'>
  accounts: Pick<AccountDirectory, 'findByIds' | 'findByEmail'>
  orders: Pick<Orders, 'paidOrderCounts'>
  carts: Pick<CartModule, 'activeCartItemCounts'>
  outbox: Pick<EmailModule['outbox'], 'enqueue'>
  alertEmails: readonly string[]
  frontendBaseUrl: string
  logger: Logger
}) {
  const shopUrl = `${frontendBaseUrl}/products`

  return {
    /** Whether the signed-in user can submit a new request, already has one pending, or is blocked. */
    requestStatus: (userId: number): Promise<CustomerMagicCoinRequestStatus> =>
      read(db, async (trx) => {
        const block = await trx
          .selectFrom('magic_coin_request_blocks')
          .select('user_id')
          .where('user_id', '=', userId)
          .executeTakeFirst()
        if (block !== undefined) return 'BLOCKED'
        const pending = await trx
          .selectFrom('magic_coin_requests')
          .select('id')
          .where('user_id', '=', userId)
          .where('status', '=', 'PENDING')
          .executeTakeFirst()
        return pending === undefined ? 'CAN_REQUEST' : 'PENDING'
      }),

    /** Creates one `PENDING` request for `userId` and enqueues the admin alert in one transaction. */
    submitRequest: async (userId: number): Promise<SubmitRequestOutcome> => {
      const account = (await accounts.findByIds([userId])).get(userId)
      const userEmail = account?.email ?? `user-${String(userId)}`
      const result = await executePostgresWrite({ uniqueViolation: 'alreadyPending' }, () =>
        write(db, async (trx) => {
          const block = await trx
            .selectFrom('magic_coin_request_blocks')
            .select('user_id')
            .where('user_id', '=', userId)
            .executeTakeFirst()
          if (block !== undefined) return { kind: 'blocked' as const }

          const coinRow = await trx
            .selectFrom('magic_coins')
            .select('balance')
            .where('user_id', '=', userId)
            .forUpdate()
            .executeTakeFirst()
          if (coinRow === undefined || coinRow.balance >= GENERATION_COST) {
            return { kind: 'coinsAvailable' as const }
          }

          const created = await trx
            .insertInto('magic_coin_requests')
            .values({ user_id: userId, status: 'PENDING' })
            .returning(['id', 'created_at'])
            .executeTakeFirstOrThrow()

          for (const recipient of alertEmails) {
            await outbox.enqueue(
              trx,
              renderMagicCoinRequestAlert({
                recipient,
                frontendBaseUrl,
                requestId: created.id,
                userEmail,
              }),
            )
          }

          return {
            kind: 'created' as const,
            request: { id: created.id, status: 'PENDING' as const, createdAt: created.created_at },
          }
        }),
      )
      if (result === 'alreadyPending') return { kind: 'alreadyPending' }
      if (result.kind === 'created' && alertEmails.length === 0) {
        logger.info(
          { requestId: result.request.id, userId },
          'New Magic Coin request created without OPERATIONS_ALERT_EMAIL configured',
        )
      }
      return result
    },

    /** Count of open `PENDING` requests, for the admin sidebar badge. */
    pendingCount: async (): Promise<number> => {
      const row = await read(db, (trx) =>
        trx
          .selectFrom('magic_coin_requests')
          .select((eb) => eb.fn.countAll<number>().as('count'))
          .where('status', '=', 'PENDING')
          .executeTakeFirstOrThrow(),
      )
      return row.count
    },

    /** Lists either open (`PENDING`) or decided (`DECIDED`) requests enriched with verification signals. */
    listRequests: async (filter: 'PENDING' | 'DECIDED'): Promise<AdminMagicCoinRequest[]> => {
      const rows = await read(db, (trx) =>
        trx
          .selectFrom('magic_coin_requests')
          .selectAll()
          .$call((query) =>
            filter === 'PENDING'
              ? query
                  .where('status', '=', 'PENDING')
                  .orderBy('created_at', 'desc')
                  .orderBy('id', 'desc')
              : query
                  .where('status', '<>', 'PENDING')
                  .orderBy('decided_at', 'desc')
                  .orderBy('id', 'desc')
                  .limit(DECIDED_LIST_LIMIT),
          )
          .execute(),
      )
      if (rows.length === 0) return []

      const requesterIds = [...new Set(rows.map((row) => row.user_id))]
      const deciderIds = rows.flatMap((row) =>
        row.decided_by_user_id === null ? [] : [row.decided_by_user_id],
      )
      const [
        accountMap,
        paidOrders,
        activeCartItems,
        coinSummaries,
        requestAggregates,
        blockedIds,
      ] = await Promise.all([
        accounts.findByIds([...requesterIds, ...deciderIds]),
        orders.paidOrderCounts(requesterIds),
        carts.activeCartItemCounts(requesterIds),
        coins.readUserSummaries(requesterIds),
        read(db, (trx) =>
          trx
            .selectFrom('magic_coin_requests')
            .select([
              'user_id',
              (eb) => eb.fn.countAll<number>().as('total_requests'),
              (eb) =>
                eb.fn.coalesce(eb.fn.sum<number>('granted_coins'), eb.lit(0)).as('approved_coins'),
            ])
            .where('user_id', 'in', requesterIds)
            .groupBy('user_id')
            .execute(),
        ),
        read(db, (trx) =>
          trx
            .selectFrom('magic_coin_request_blocks')
            .select('user_id')
            .where('user_id', 'in', requesterIds)
            .execute(),
        ),
      ])

      const aggregateMap = new Map(
        requestAggregates.map((row) => [
          row.user_id,
          {
            totalRequestCount: row.total_requests,
            totalApprovedCoins: row.approved_coins,
          },
        ]),
      )
      const blockedSet = new Set(blockedIds.map((row) => row.user_id))

      return rows.map((row) => {
        const account = accountMap.get(row.user_id)
        const coinSummary = coinSummaries.get(row.user_id)
        const aggregate = aggregateMap.get(row.user_id)
        const decider =
          row.decided_by_user_id === null ? undefined : accountMap.get(row.decided_by_user_id)
        return {
          id: row.id,
          userId: row.user_id,
          userEmail: account?.email ?? `User #${String(row.user_id)}`,
          userName: account?.name ?? null,
          accountCreatedAt: account?.createdAt ?? null,
          currentBalance: coinSummary?.balance ?? INITIAL_BALANCE,
          coinsFirstSpentAt: coinSummary?.firstSpentAt ?? null,
          coinsLastUpdatedAt: coinSummary?.lastUpdatedAt ?? null,
          paidOrderCount: paidOrders.get(row.user_id) ?? 0,
          activeCartItemCount: activeCartItems.get(row.user_id) ?? 0,
          totalRequestCount: aggregate?.totalRequestCount ?? 1,
          totalApprovedCoins: aggregate?.totalApprovedCoins ?? 0,
          status: row.status,
          grantedCoins: row.granted_coins,
          blockedUser: row.blocked_user,
          decidedByEmail: decider?.email ?? null,
          createdAt: row.created_at,
          decidedAt: row.decided_at,
          isCurrentlyBlocked: blockedSet.has(row.user_id),
        }
      })
    },

    /** Approves one `PENDING` request, credits `amount`, and enqueues the customer mail atomically. */
    approveRequest: async (
      requestId: number,
      amount: number,
      adminUserId: number,
    ): Promise<ApproveRequestOutcome> => {
      const target = await read(db, (trx) =>
        trx
          .selectFrom('magic_coin_requests')
          .select('user_id')
          .where('id', '=', requestId)
          .executeTakeFirst(),
      )
      if (target === undefined) return { kind: 'notFound' }
      const recipientEmail = (await accounts.findByIds([target.user_id])).get(target.user_id)?.email

      return write(db, async (trx) => {
        const locked = await trx
          .selectFrom('magic_coin_requests')
          .select(['id', 'user_id', 'status'])
          .where('id', '=', requestId)
          .forUpdate()
          .executeTakeFirst()
        if (locked === undefined) return { kind: 'notFound' }
        if (locked.status !== 'PENDING') return { kind: 'alreadyDecided' }

        const decided = await trx
          .updateTable('magic_coin_requests')
          .set({
            status: 'APPROVED',
            granted_coins: amount,
            decided_by_user_id: adminUserId,
            decided_at: sql<Date>`now()`,
          })
          .where('id', '=', requestId)
          .returning('decided_at')
          .executeTakeFirstOrThrow()

        const newBalance = await grantCoinsInTransaction(trx, locked.user_id, amount)

        if (recipientEmail !== undefined) {
          await outbox.enqueue(trx, renderMagicCoinsGranted({ recipient: recipientEmail, shopUrl }))
        }

        return {
          kind: 'approved',
          id: locked.id,
          userId: locked.user_id,
          grantedCoins: amount,
          newBalance,
          decidedAt: decided.decided_at ?? new Date(),
        }
      })
    },

    /** Rejects one `PENDING` request, optionally blocking the account permanently. Never mails the user. */
    rejectRequest: (
      requestId: number,
      blockUser: boolean,
      adminUserId: number,
    ): Promise<RejectRequestOutcome> =>
      write(db, async (trx) => {
        const locked = await trx
          .selectFrom('magic_coin_requests')
          .select(['id', 'user_id', 'status'])
          .where('id', '=', requestId)
          .forUpdate()
          .executeTakeFirst()
        if (locked === undefined) return { kind: 'notFound' }
        if (locked.status !== 'PENDING') return { kind: 'alreadyDecided' }

        const decided = await trx
          .updateTable('magic_coin_requests')
          .set({
            status: 'REJECTED',
            blocked_user: blockUser,
            decided_by_user_id: adminUserId,
            decided_at: sql<Date>`now()`,
          })
          .where('id', '=', requestId)
          .returning('decided_at')
          .executeTakeFirstOrThrow()

        if (blockUser) {
          await trx
            .insertInto('magic_coin_request_blocks')
            .values({ user_id: locked.user_id, blocked_by_user_id: adminUserId })
            .onConflict((conflict) => conflict.column('user_id').doNothing())
            .execute()
        }

        return {
          kind: 'rejected',
          id: locked.id,
          userId: locked.user_id,
          blockedUser: blockUser,
          decidedAt: decided.decided_at ?? new Date(),
        }
      }),

    /**
     * Grants `amount` Magic Coins directly to an account identified by e-mail, auto-approving any
     * open pending request for that account and enqueuing the customer notification mail.
     */
    grantManually: async (
      email: string,
      amount: number,
      adminUserId: number,
    ): Promise<ManualGrantOutcome> => {
      const account = await accounts.findByEmail(email)
      if (account === undefined) return { kind: 'userNotFound' }

      return write(db, async (trx) => {
        const newBalance = await grantCoinsInTransaction(trx, account.userId, amount)
        await trx
          .updateTable('magic_coin_requests')
          .set({
            status: 'APPROVED',
            granted_coins: amount,
            decided_by_user_id: adminUserId,
            decided_at: sql<Date>`now()`,
          })
          .where('user_id', '=', account.userId)
          .where('status', '=', 'PENDING')
          .execute()
        await outbox.enqueue(trx, renderMagicCoinsGranted({ recipient: account.email, shopUrl }))
        return {
          kind: 'granted',
          userId: account.userId,
          userEmail: account.email,
          grantedCoins: amount,
          newBalance,
        }
      })
    },

    /** Lists all currently blocked accounts, newest block first. */
    listBlocks: async (): Promise<AdminBlockedMagicCoinAccount[]> => {
      const rows = await read(db, (trx) =>
        trx
          .selectFrom('magic_coin_request_blocks')
          .selectAll()
          .orderBy('blocked_at', 'desc')
          .orderBy('user_id', 'desc')
          .execute(),
      )
      if (rows.length === 0) return []
      const userIds = rows.map((row) => row.user_id)
      const blockerIds = rows.flatMap((row) =>
        row.blocked_by_user_id === null ? [] : [row.blocked_by_user_id],
      )
      const [accountMap, paidOrders, coinSummaries] = await Promise.all([
        accounts.findByIds([...userIds, ...blockerIds]),
        orders.paidOrderCounts(userIds),
        coins.readUserSummaries(userIds),
      ])
      return rows.map((row) => {
        const account = accountMap.get(row.user_id)
        const blocker =
          row.blocked_by_user_id === null ? undefined : accountMap.get(row.blocked_by_user_id)
        const coinSummary = coinSummaries.get(row.user_id)
        return {
          userId: row.user_id,
          userEmail: account?.email ?? `User #${String(row.user_id)}`,
          userName: account?.name ?? null,
          accountCreatedAt: account?.createdAt ?? null,
          currentBalance: coinSummary?.balance ?? INITIAL_BALANCE,
          paidOrderCount: paidOrders.get(row.user_id) ?? 0,
          blockedAt: row.blocked_at,
          blockedByEmail: blocker?.email ?? null,
        }
      })
    },

    /** Removes a permanent request block; answers `false` when the user was not blocked. */
    unblockUser: async (userId: number): Promise<boolean> => {
      const deleted = await db
        .deleteFrom('magic_coin_request_blocks')
        .where('user_id', '=', userId)
        .executeTakeFirst()
      return deleted.numDeletedRows > 0n
    },
  }
}
