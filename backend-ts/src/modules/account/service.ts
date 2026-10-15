import { AuthRoles } from '@/platform/auth/auth'
import { createLogger } from '@/platform/logging'
import { newAccountToken, tokenHashOf, type PasswordHasher } from './credentials'
import type { AccountMails } from './mails'
import type {
  AccountRepository,
  Address,
  SupplierLogin,
  TokenPurpose,
  UserAccount,
} from './repository'

/** The validated bodies, as the schemas of `routes.ts` produce them (e-mails trimmed). */
export interface Credentials {
  email: string
  password: string
}
export interface LinkInput {
  userId: number
  token: string
}
export interface ResetPasswordInput {
  email: string
  token: string
  newPassword: string
}
export interface ChangeEmailInput {
  newEmail: string
  currentPassword: string
}
export interface ConfirmChangeEmailInput {
  userId: number
  newEmail: string
  token: string
}
export interface ChangePasswordInput {
  currentPassword: string
  newPassword: string
}
/** Addresses arrive normalized: fields trimmed, blanks `null`, an all-blank address `null`. */
export interface ProfileInput {
  shippingAddress: Address | null
  hasSeparateBillingAddress: boolean
  billingAddress: Address | null
}
export interface SupplierLoginInput {
  supplierId: number
  email: string
}

/** The one profile representation, answered by `GET me` and `PUT profile`. */
export interface AccountProfile {
  id: number
  email: string
  roles: string[]
  shippingAddress: Address | null
  billingAddress: Address | null
  hasSeparateBillingAddress: boolean
  createdAt: Date
}

/** A successful login: what the route writes into the session, and nothing else. */
export interface SignedIn {
  userId: number
  roles: string[]
}

const CUSTOMER_ROLE = 'CUSTOMER'
const MAX_FAILED_LOGINS = 15
const LOCKOUT_MILLISECONDS = 10 * 60 * 1000
const TOKEN_LIFETIME_MILLISECONDS = 24 * 60 * 60 * 1000

const log = createLogger('account')

export type AccountService = ReturnType<typeof createAccountService>

/**
 * Everything a visitor, a customer, and an administrator do with accounts. `now` is the one clock:
 * token expiry, lockout, and the stored creation time read it, so tests travel through time.
 */
export function createAccountService({
  repository,
  mails,
  passwords,
  now,
}: {
  repository: AccountRepository
  mails: AccountMails
  passwords: PasswordHasher
  now: () => Date
}) {
  /** Verified for unknown e-mails too, so both login failures cost one hash comparison. */
  const unknownUserPasswordHash = passwords.hash(newAccountToken())

  const later = (milliseconds: number) => new Date(now().getTime() + milliseconds)

  /** Stores a fresh token, replacing the previous one of its purpose, and answers it unhashed. */
  const issueToken = async (userId: number, purpose: TokenPurpose, newEmail: string | null) => {
    const token = newAccountToken()
    await repository.issueToken({
      userId,
      purpose,
      tokenHash: tokenHashOf(token),
      newEmail,
      expiresAt: later(TOKEN_LIFETIME_MILLISECONDS),
    })
    return token
  }

  const sendConfirmation = async (userId: number, email: string) =>
    mails.sendAccountConfirmation(userId, email, await issueToken(userId, 'CONFIRM_EMAIL', null))

  /**
   * `resend-confirmation` and `forgot-password` answer the same whether or not the account
   * exists, so any failure after validation, a database error as much as a failed delivery, is
   * logged and swallowed.
   */
  const enumerationSafe = async (operation: string, block: () => Promise<unknown>) => {
    try {
      await block()
    } catch (error) {
      log.warn({ err: error }, `Suppressed ${operation} failure (enumeration-safe)`)
    }
  }

  return {
    register: async ({
      email,
      password,
    }: Credentials): Promise<'registered' | 'emailTaken' | 'deliveryFailed'> => {
      const stored = await repository.insertUser({
        email,
        passwordHash: await passwords.hash(password),
        role: CUSTOMER_ROLE,
        createdAt: now(),
        emailConfirmed: false,
        supplierId: null,
      })
      if (stored === 'emailTaken') return 'emailTaken'
      if (stored === 'unknownSupplier') throw new Error('A registration carries no supplier link')
      return (await sendConfirmation(stored, email)) ? 'registered' : 'deliveryFailed'
    },

    login: async ({
      email,
      password,
    }: Credentials): Promise<
      SignedIn | 'invalidCredentials' | 'emailNotConfirmed' | 'lockedOut'
    > => {
      const user = await repository.findByEmail(email)
      if (user === undefined) {
        await passwords.verify(password, await unknownUserPasswordHash)
        return 'invalidCredentials'
      }
      if (!user.emailConfirmed) return 'emailNotConfirmed'
      if (user.lockedUntil !== null && user.lockedUntil > now()) return 'lockedOut'
      if (!(await passwords.verify(password, user.passwordHash))) {
        const failures = await repository.recordFailedLogin(
          user.id,
          MAX_FAILED_LOGINS,
          later(LOCKOUT_MILLISECONDS),
        )
        return failures >= MAX_FAILED_LOGINS ? 'lockedOut' : 'invalidCredentials'
      }
      if (user.failedLoginCount > 0 || user.lockedUntil !== null) {
        await repository.resetLockout(user.id)
      }
      return { userId: user.id, roles: user.roles }
    },

    /** Answers whether the link was valid; an invalid one changes nothing. */
    confirmEmail: ({ userId, token }: LinkInput): Promise<boolean> =>
      repository.confirmEmail(userId, tokenHashOf(token), now()),

    resendConfirmation: (email: string) =>
      enumerationSafe('resend-confirmation', async () => {
        const user = await repository.findByEmail(email)
        if (user !== undefined && !user.emailConfirmed) await sendConfirmation(user.id, user.email)
      }),

    forgotPassword: (email: string) =>
      enumerationSafe('forgot-password', async () => {
        const user = await repository.findByEmail(email)
        if (user === undefined) return
        await mails.sendPasswordReset(user.email, await issueToken(user.id, 'RESET_PASSWORD', null))
      }),

    /** Answers whether the link was valid; a valid one also announces the change best effort. */
    resetPassword: async ({ email, token, newPassword }: ResetPasswordInput): Promise<boolean> => {
      const user = await repository.findByEmail(email)
      if (user === undefined) return false
      const reset = await repository.resetPassword(
        user.id,
        tokenHashOf(token),
        await passwords.hash(newPassword),
        now(),
      )
      if (reset) await mails.sendPasswordChanged(user.email)
      return reset
    },

    profile: async (userId: number): Promise<AccountProfile | undefined> => {
      const user = await repository.findById(userId)
      return user === undefined ? undefined : toProfile(user)
    },

    /** A full replace; switching the flag off clears the stored billing address. */
    updateProfile: async (
      userId: number,
      input: ProfileInput,
    ): Promise<AccountProfile | undefined> => {
      const billing = input.hasSeparateBillingAddress ? input.billingAddress : null
      const user = await repository.updateProfile(
        userId,
        input.shippingAddress,
        billing,
        input.hasSeparateBillingAddress,
      )
      return user === undefined ? undefined : toProfile(user)
    },

    changeEmail: async (
      userId: number,
      { newEmail, currentPassword }: ChangeEmailInput,
    ): Promise<'sent' | 'notFound' | 'wrongPassword' | 'emailTaken' | 'deliveryFailed'> => {
      const user = await repository.findById(userId)
      if (user === undefined) return 'notFound'
      if (!(await passwords.verify(currentPassword, user.passwordHash))) return 'wrongPassword'
      // An early comfort check; the unique index decides again at confirmation time.
      if ((await repository.findByEmail(newEmail)) !== undefined) return 'emailTaken'
      const token = await issueToken(user.id, 'CHANGE_EMAIL', newEmail)
      return (await mails.sendChangeEmail(user.id, user.email, newEmail, token))
        ? 'sent'
        : 'deliveryFailed'
    },

    confirmChangeEmail: ({ userId, newEmail, token }: ConfirmChangeEmailInput) =>
      repository.confirmChangeEmail(userId, tokenHashOf(token), newEmail, now()),

    changePassword: async (
      userId: number,
      { currentPassword, newPassword }: ChangePasswordInput,
    ): Promise<'changed' | 'notFound' | 'wrongPassword'> => {
      const user = await repository.findById(userId)
      if (user === undefined) return 'notFound'
      if (!(await passwords.verify(currentPassword, user.passwordHash))) return 'wrongPassword'
      await repository.updatePasswordHash(userId, await passwords.hash(newPassword))
      await mails.sendPasswordChanged(user.email)
      return 'changed'
    },

    /**
     * Creates a supplier login the way an invitation works. The user, its one `SUPPLIER` role, and
     * the supplier link are one transaction; the address counts as confirmed, because nobody mails
     * it a confirmation and the login refuses unconfirmed addresses; the stored hash covers a
     * random value nobody knows. Token and invitation follow the transaction, so a provider
     * failure leaves a usable login behind instead of rolling one back.
     */
    createSupplierLogin: async ({
      supplierId,
      email,
    }: SupplierLoginInput): Promise<
      SupplierLogin | 'emailTaken' | 'unknownSupplier' | 'invitationDeliveryFailed'
    > => {
      const createdAt = now()
      const stored = await repository.insertUser({
        email,
        passwordHash: await passwords.hash(newAccountToken()),
        role: AuthRoles.SUPPLIER,
        createdAt,
        emailConfirmed: true,
        supplierId,
      })
      if (stored === 'emailTaken' || stored === 'unknownSupplier') return stored
      // The invitation is a reset link: the invited person walks the set-password path.
      const token = await issueToken(stored, 'RESET_PASSWORD', null)
      return (await mails.sendSupplierInvitation(stored, email, token))
        ? { userId: stored, email, supplierId, createdAt }
        : 'invitationDeliveryFailed'
    },

    listSupplierLogins: (supplierId: number) => repository.listSupplierLogins(supplierId),

    deleteSupplierLogin: (userId: number) => repository.deleteSupplierLogin(userId),
  }
}

function toProfile(user: UserAccount): AccountProfile {
  return {
    id: user.id,
    email: user.email,
    roles: user.roles,
    shippingAddress: user.shippingAddress,
    billingAddress: user.billingAddress,
    hasSeparateBillingAddress: user.hasSeparateBillingAddress,
    createdAt: user.createdAt,
  }
}
