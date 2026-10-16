import { timingSafeEqual } from 'node:crypto'
import type { Kysely, Selectable, Transaction } from 'kysely'
import { read, write, type Tables } from '@/platform/db/database'
import { executePostgresWrite } from '@/platform/db/postgres-write'
import type { UsersTable } from './schema'

/**
 * One shipping or billing address; every field optional. The same shape is what `PUT profile`
 * receives and what `GET me` answers, so a full replace sends back exactly what was read.
 */
export interface Address {
  firstName: string | null
  lastName: string | null
  street: string | null
  houseNumber: string | null
  postalCode: string | null
  city: string | null
  country: string | null
  phone: string | null
}

/** A stored user with its roles, credential, and lockout state. Never sent as it is. */
export interface UserAccount {
  id: number
  email: string
  emailConfirmed: boolean
  passwordHash: string
  createdAt: Date
  failedLoginCount: number
  lockedUntil: Date | null
  roles: string[]
  shippingAddress: Address | null
  billingAddress: Address | null
  hasSeparateBillingAddress: boolean
}

/** A user that carries a supplier link, reduced to what the admin surface shows; also its JSON. */
export interface SupplierLogin {
  userId: number
  email: string
  supplierId: number
  createdAt: Date
}

/** Confirmation, reset (also the supplier invitation), and e-mail change links. */
export type TokenPurpose = 'CONFIRM_EMAIL' | 'RESET_PASSWORD' | 'CHANGE_EMAIL'

export type AccountRepository = ReturnType<typeof createAccountRepository>

export function createAccountRepository(db: Kysely<Tables>) {
  return {
    /**
     * Stores a user with exactly one role in one transaction and answers the new id. The unique
     * `LOWER(email)` index decides a taken address; the `supplier_id` foreign key decides an
     * unknown supplier, so neither needs a racy lookup first.
     */
    insertUser: (user: {
      email: string
      passwordHash: string
      role: string
      createdAt: Date
      emailConfirmed: boolean
      supplierId: number | null
    }) =>
      executePostgresWrite(
        { uniqueViolation: 'emailTaken', foreignKeyViolation: 'unknownSupplier' },
        () =>
          write(db, async (trx) => {
            const { id } = await trx
              .insertInto('users')
              .values({
                email: user.email,
                password_hash: user.passwordHash,
                created_at: user.createdAt,
                email_confirmed: user.emailConfirmed,
                supplier_id: user.supplierId,
              })
              .returning('id')
              .executeTakeFirstOrThrow()
            await trx.insertInto('user_roles').values({ user_id: id, role: user.role }).execute()
            return id
          }),
      ),

    findByEmail: (email: string): Promise<UserAccount | undefined> =>
      read(db, async (trx) => {
        const row = await trx
          .selectFrom('users')
          .selectAll()
          .where((eb) => eb(eb.fn('lower', ['email']), '=', eb.fn('lower', [eb.val(email)])))
          .executeTakeFirst()
        return row === undefined ? undefined : toUserAccount(trx, row)
      }),

    findById: (id: number): Promise<UserAccount | undefined> =>
      read(db, (trx) => findAccount(trx, id)),

    /**
     * The supplier this user acts for, or `null` for a customer, an admin, and a user that does
     * not exist. The supplier route protection asks this on every request, which is what makes a
     * deleted supplier login lose access immediately.
     */
    findSupplierId: async (userId: number): Promise<number | null> => {
      const row = await read(db, (trx) =>
        trx.selectFrom('users').select('supplier_id').where('id', '=', userId).executeTakeFirst(),
      )
      return row?.supplier_id ?? null
    },

    /** The supplier logins of one supplier, oldest first. */
    listSupplierLogins: async (supplierId: number): Promise<SupplierLogin[]> => {
      const rows = await read(db, (trx) =>
        trx
          .selectFrom('users')
          .select(['id', 'email', 'created_at'])
          .where('supplier_id', '=', supplierId)
          .orderBy('created_at')
          .orderBy('id')
          .execute(),
      )
      return rows.map((row) => ({
        userId: row.id,
        email: row.email,
        supplierId,
        createdAt: row.created_at,
      }))
    },

    /**
     * Hard-deletes a supplier login. `supplier_id IS NOT NULL` is what makes an id a supplier
     * login, so a customer id answers `false` exactly like an unknown one. Roles and tokens
     * cascade away with the row.
     */
    deleteSupplierLogin: async (userId: number): Promise<boolean> => {
      const result = await db
        .deleteFrom('users')
        .where('id', '=', userId)
        .where('supplier_id', 'is not', null)
        .executeTakeFirst()
      return result.numDeletedRows > 0n
    },

    /**
     * Counts a failed login under `SELECT … FOR UPDATE`, so concurrent failures never lose an
     * increment. Reaching `lockThreshold` locks the account until `lockUntil` and resets the
     * counter, so an expired lockout counts from zero again. Answers the new count.
     */
    recordFailedLogin: (userId: number, lockThreshold: number, lockUntil: Date) =>
      write(db, async (trx) => {
        const row = await trx
          .selectFrom('users')
          .select('failed_login_count')
          .where('id', '=', userId)
          .forUpdate()
          .executeTakeFirst()
        if (row === undefined) return 0
        const count = row.failed_login_count + 1
        await trx
          .updateTable('users')
          .set(
            count >= lockThreshold
              ? { failed_login_count: 0, locked_until: lockUntil }
              : { failed_login_count: count },
          )
          .where('id', '=', userId)
          .execute()
        return count
      }),

    resetLockout: async (userId: number): Promise<void> => {
      await db
        .updateTable('users')
        .set({ failed_login_count: 0, locked_until: null })
        .where('id', '=', userId)
        .execute()
    },

    /** Replaces any previous token of the same purpose, so only the latest link counts. */
    issueToken: async (token: {
      userId: number
      purpose: TokenPurpose
      tokenHash: string
      newEmail: string | null
      expiresAt: Date
    }): Promise<void> => {
      await write(db, async (trx) => {
        await trx
          .deleteFrom('account_tokens')
          .where('user_id', '=', token.userId)
          .where('purpose', '=', token.purpose)
          .execute()
        await trx
          .insertInto('account_tokens')
          .values({
            user_id: token.userId,
            purpose: token.purpose,
            token_hash: token.tokenHash,
            new_email: token.newEmail,
            expires_at: token.expiresAt,
          })
          .execute()
      })
    },

    /** Consumes a valid confirmation token and marks the e-mail confirmed, atomically. */
    confirmEmail: (userId: number, tokenHash: string, now: Date): Promise<boolean> =>
      write(db, async (trx) => {
        if (!(await consumeToken(trx, userId, 'CONFIRM_EMAIL', tokenHash, now, null))) return false
        await trx
          .updateTable('users')
          .set({ email_confirmed: true })
          .where('id', '=', userId)
          .execute()
        return true
      }),

    /** Consumes a valid reset token and stores the new password hash, atomically. */
    resetPassword: (
      userId: number,
      tokenHash: string,
      newPasswordHash: string,
      now: Date,
    ): Promise<boolean> =>
      write(db, async (trx) => {
        if (!(await consumeToken(trx, userId, 'RESET_PASSWORD', tokenHash, now, null))) return false
        await trx
          .updateTable('users')
          .set({ password_hash: newPasswordHash })
          .where('id', '=', userId)
          .execute()
        return true
      }),

    /**
     * Consumes a valid change-e-mail token issued for exactly `newEmail` and replaces the login
     * address. The unique index decides again here: a violation rolls the consumption back.
     */
    confirmChangeEmail: (userId: number, tokenHash: string, newEmail: string, now: Date) =>
      executePostgresWrite({ uniqueViolation: 'emailTaken' }, () =>
        write(db, async (trx) => {
          if (!(await consumeToken(trx, userId, 'CHANGE_EMAIL', tokenHash, now, newEmail))) {
            return 'invalidLink'
          }
          await trx.updateTable('users').set({ email: newEmail }).where('id', '=', userId).execute()
          return 'confirmed'
        }),
      ),

    /** Full-replace profile update; answers the updated account, or `undefined` when it is gone. */
    updateProfile: (
      userId: number,
      shipping: Address | null,
      billing: Address | null,
      hasSeparateBillingAddress: boolean,
    ): Promise<UserAccount | undefined> =>
      write(db, async (trx) => {
        const result = await trx
          .updateTable('users')
          .set({
            shipping_first_name: shipping?.firstName ?? null,
            shipping_last_name: shipping?.lastName ?? null,
            shipping_street: shipping?.street ?? null,
            shipping_house_number: shipping?.houseNumber ?? null,
            shipping_postal_code: shipping?.postalCode ?? null,
            shipping_city: shipping?.city ?? null,
            shipping_country: shipping?.country ?? null,
            shipping_phone: shipping?.phone ?? null,
            billing_first_name: billing?.firstName ?? null,
            billing_last_name: billing?.lastName ?? null,
            billing_street: billing?.street ?? null,
            billing_house_number: billing?.houseNumber ?? null,
            billing_postal_code: billing?.postalCode ?? null,
            billing_city: billing?.city ?? null,
            billing_country: billing?.country ?? null,
            billing_phone: billing?.phone ?? null,
            has_separate_billing_address: hasSeparateBillingAddress,
          })
          .where('id', '=', userId)
          .executeTakeFirst()
        return result.numUpdatedRows === 0n ? undefined : findAccount(trx, userId)
      }),

    updatePasswordHash: async (userId: number, passwordHash: string): Promise<void> => {
      await db
        .updateTable('users')
        .set({ password_hash: passwordHash })
        .where('id', '=', userId)
        .execute()
    },
  }
}

async function findAccount(trx: Transaction<Tables>, id: number) {
  const row = await trx.selectFrom('users').selectAll().where('id', '=', id).executeTakeFirst()
  return row === undefined ? undefined : toUserAccount(trx, row)
}

/**
 * Deletes the stored token of this purpose when it is unexpired, matches the supplied hash
 * (compared in constant time), and was issued for `newEmail` (`null` but for an e-mail change);
 * answers whether it did. A refused link stays usable. The delete re-checks that the row is still
 * there, so two requests presenting the same link at once consume it only once.
 */
async function consumeToken(
  trx: Transaction<Tables>,
  userId: number,
  purpose: TokenPurpose,
  tokenHash: string,
  now: Date,
  newEmail: string | null,
): Promise<boolean> {
  const token = await trx
    .selectFrom('account_tokens')
    .select(['id', 'token_hash', 'new_email', 'expires_at'])
    .where('user_id', '=', userId)
    .where('purpose', '=', purpose)
    .executeTakeFirst()
  if (
    token === undefined ||
    token.expires_at <= now ||
    token.new_email !== newEmail ||
    !sameText(token.token_hash, tokenHash)
  ) {
    return false
  }
  const deleted = await trx
    .deleteFrom('account_tokens')
    .where('id', '=', token.id)
    .executeTakeFirst()
  return deleted.numDeletedRows > 0n
}

function sameText(stored: string, supplied: string): boolean {
  const a = Buffer.from(stored, 'utf8')
  const b = Buffer.from(supplied, 'utf8')
  return a.length === b.length && timingSafeEqual(a, b)
}

async function toUserAccount(
  trx: Transaction<Tables>,
  row: Selectable<UsersTable>,
): Promise<UserAccount> {
  const roles = await trx
    .selectFrom('user_roles')
    .select('role')
    .where('user_id', '=', row.id)
    .orderBy('role')
    .execute()
  return {
    id: row.id,
    email: row.email,
    emailConfirmed: row.email_confirmed,
    passwordHash: row.password_hash,
    createdAt: row.created_at,
    failedLoginCount: row.failed_login_count,
    lockedUntil: row.locked_until,
    roles: roles.map(({ role }) => role),
    shippingAddress: storedAddress({
      firstName: row.shipping_first_name,
      lastName: row.shipping_last_name,
      street: row.shipping_street,
      houseNumber: row.shipping_house_number,
      postalCode: row.shipping_postal_code,
      city: row.shipping_city,
      country: row.shipping_country,
      phone: row.shipping_phone,
    }),
    billingAddress: storedAddress({
      firstName: row.billing_first_name,
      lastName: row.billing_last_name,
      street: row.billing_street,
      houseNumber: row.billing_house_number,
      postalCode: row.billing_postal_code,
      city: row.billing_city,
      country: row.billing_country,
      phone: row.billing_phone,
    }),
    hasSeparateBillingAddress: row.has_separate_billing_address,
  }
}

/** An address with no field set is no address. */
const storedAddress = (address: Address): Address | null =>
  Object.values(address).every((value) => value === null) ? null : address
