import { zValidator } from '@hono/zod-validator'
import { Hono, type Context } from 'hono'
import { z } from 'zod'
import { requireAdmin, requireAuthenticated, type Auth } from '@/platform/auth/auth'
import { apiError, jsonBody, validationFailed } from '@/platform/http/respond'
import type { Address } from './repository'
import type {
  AccountService,
  ChangeEmailInput,
  ChangePasswordInput,
  ConfirmChangeEmailInput,
  Credentials,
  LinkInput,
  ProfileInput,
  ResetPasswordInput,
  SupplierLoginInput,
} from './service'

// --- Field rules several bodies share ------------------------------------------------------------

/**
 * An address the mail provider accepts: at most 255 characters, no whitespace or control
 * characters, exactly one `@` with text on both sides. The value is trimmed first.
 */
const isEmailAddress = (value: string) =>
  value.length <= 255 && /^[^@\s\p{Cc}]+@[^@\s\p{Cc}]+$/u.test(value)

const emailField = z
  .string({ error: 'Email is required' })
  .trim()
  .min(1, { error: 'Email is required', abort: true })
  .refine(isEmailAddress, 'Invalid email format')

const newPasswordField = z
  .string({ error: 'Password is required' })
  .min(1, { error: 'Password is required', abort: true })
  .min(8, 'Password must be at least 8 characters')

/** A password that is only checked against a stored hash, never against the length rule. */
const presentPassword = (message: string) => z.string({ error: message }).min(1, message)

const tokenField = z
  .string({ error: 'Token is required' })
  .refine((value) => value.trim() !== '', 'Token is required')

const userIdField = z.int({ error: 'User id is required' })

// --- Bodies --------------------------------------------------------------------------------------

export const registerInput = z.strictObject({
  email: emailField,
  password: newPasswordField,
}) satisfies z.ZodType<Credentials>

/**
 * Login checks only that a password is present: a password from before the length rule must still
 * sign in, and the login must not tell which rules current passwords follow.
 */
export const loginInput = z.strictObject({
  email: emailField,
  password: presentPassword('Password is required'),
}) satisfies z.ZodType<Credentials>

export const confirmEmailInput = z.strictObject({
  userId: userIdField,
  token: tokenField,
}) satisfies z.ZodType<LinkInput>

/** Shared by resend-confirmation and forgot-password. */
export const accountEmailInput = z.strictObject({ email: emailField })

export const resetPasswordInput = z.strictObject({
  email: emailField,
  token: tokenField,
  newPassword: newPasswordField,
}) satisfies z.ZodType<ResetPasswordInput>

export const changeEmailInput = z.strictObject({
  newEmail: emailField,
  currentPassword: presentPassword('Current password is required'),
}) satisfies z.ZodType<ChangeEmailInput>

export const confirmChangeEmailInput = z.strictObject({
  userId: userIdField,
  newEmail: emailField,
  token: tokenField,
}) satisfies z.ZodType<ConfirmChangeEmailInput>

export const changePasswordInput = z.strictObject({
  currentPassword: presentPassword('Current password is required'),
  newPassword: newPasswordField,
}) satisfies z.ZodType<ChangePasswordInput>

/** An optional address field: checked as sent, then trimmed, a blank becoming `null`. */
const addressText = (maximum: number) =>
  z
    .string()
    .max(maximum, `Must be at most ${String(maximum)} characters`)
    .nullish()
    .transform(cleaned)

const address = (error?: string) =>
  z
    .strictObject(
      {
        firstName: addressText(100),
        lastName: addressText(100),
        street: addressText(200),
        houseNumber: addressText(20),
        postalCode: addressText(10),
        city: addressText(100),
        country: z
          .string()
          .refine(
            (value) => value.trim() === '' || /^\p{L}{2}$/u.test(value.trim()),
            'Country must be a two-letter code',
          )
          .nullish()
          .transform(cleaned),
        phone: z
          .string()
          .refine(
            (value) => value === '' || /^\+?[\d\s\-().\/]+$/.test(value),
            'Phone is not a valid phone number',
          )
          .nullish()
          .transform(cleaned),
      },
      error === undefined ? undefined : { error },
    )
    .transform((value): Address | null =>
      Object.values(value).every((field) => field === null) ? null : value,
    )

/**
 * `PUT profile` replaces the whole profile. Both addresses follow the same field rules; the
 * billing address is stored only while `hasSeparateBillingAddress` is set.
 */
export const profileInput = z.strictObject({
  shippingAddress: address('Shipping address is required'),
  hasSeparateBillingAddress: z
    .boolean({ error: 'HasSeparateBillingAddress must be a boolean' })
    .default(false),
  billingAddress: address()
    .nullish()
    .transform((value) => value ?? null),
}) satisfies z.ZodType<ProfileInput>

/**
 * Only the *shape* of `supplierId` is checked here; whether the supplier exists is decided by the
 * foreign key of the insert. There is no password: the invited person sets one by the mailed link.
 */
export const supplierLoginInput = z.strictObject({
  supplierId: z.int({ error: 'Supplier id is required' }).positive('Supplier id must be positive'),
  email: emailField,
}) satisfies z.ZodType<SupplierLoginInput>

const POSITIVE_SUPPLIER_ID = 'A positive supplier id is required'

/** The list is always scoped to one supplier. */
export const supplierLoginQuery = z.object({
  supplierId: z
    .string({ error: POSITIVE_SUPPLIER_ID })
    .regex(/^\d+$/, { error: POSITIVE_SUPPLIER_ID, abort: true })
    .transform(Number)
    .pipe(z.int({ error: POSITIVE_SUPPLIER_ID }).positive(POSITIVE_SUPPLIER_ID)),
})

function cleaned(value: string | null | undefined): string | null {
  const trimmed = value?.trim() ?? ''
  return trimmed === '' ? null : trimmed
}

// --- Routes --------------------------------------------------------------------------------------

const INVALID_LINK = 'INVALID_LINK'
const CONFIRMATION_LINK_MESSAGE = 'Invalid or expired confirmation link'
const RESET_LINK_MESSAGE = 'Invalid or expired password reset link'

/**
 * The visitor and customer routes under `/api/auth` and the administrator's supplier logins under
 * `/api/admin/supplier-logins`. The signed-in routes install `requireAuthenticated` one by one,
 * because a protection on a sub-app mounted at `/api/auth` would cover the anonymous routes too.
 */
export function accountRoutes(accounts: AccountService, auth: Auth): Hono {
  const routes = new Hono()

  routes.post('/api/auth/register', jsonBody(registerInput), async (c) => {
    switch (await accounts.register(c.req.valid('json'))) {
      case 'registered':
        return c.body(null, 204)
      case 'emailTaken':
        return c.json(apiError('Email already exists'), 409)
      case 'deliveryFailed':
        return c.json(apiError('Confirmation email could not be delivered'), 502)
    }
  })

  routes.post('/api/auth/login', jsonBody(loginInput), async (c) => {
    const result = await accounts.login(c.req.valid('json'))
    switch (result) {
      case 'invalidCredentials':
        return c.json(apiError('Invalid email or password'), 401)
      case 'emailNotConfirmed':
        return c.json(apiError('Email is not confirmed'), 403)
      case 'lockedOut':
        return c.json(apiError('Too many failed login attempts'), 429)
      default:
        await auth.signIn(c, result.userId, result.roles)
        return c.body(null, 204)
    }
  })

  routes.post('/api/auth/confirm-email', jsonBody(confirmEmailInput), async (c) =>
    (await accounts.confirmEmail(c.req.valid('json')))
      ? c.body(null, 204)
      : c.json(apiError(CONFIRMATION_LINK_MESSAGE, {}, INVALID_LINK), 400),
  )

  routes.post('/api/auth/resend-confirmation', jsonBody(accountEmailInput), async (c) => {
    await accounts.resendConfirmation(c.req.valid('json').email)
    return c.body(null, 204)
  })

  routes.post('/api/auth/forgot-password', jsonBody(accountEmailInput), async (c) => {
    await accounts.forgotPassword(c.req.valid('json').email)
    return c.body(null, 204)
  })

  routes.post('/api/auth/reset-password', jsonBody(resetPasswordInput), async (c) =>
    (await accounts.resetPassword(c.req.valid('json')))
      ? c.body(null, 204)
      : c.json(apiError(RESET_LINK_MESSAGE, {}, INVALID_LINK), 400),
  )

  routes.post('/api/auth/confirm-change-email', jsonBody(confirmChangeEmailInput), async (c) => {
    switch (await accounts.confirmChangeEmail(c.req.valid('json'))) {
      case 'confirmed':
        return c.body(null, 204)
      case 'invalidLink':
        return c.json(apiError(CONFIRMATION_LINK_MESSAGE, {}, INVALID_LINK), 400)
      case 'emailTaken':
        return c.json(apiError('Email already exists'), 409)
    }
  })

  routes.get('/api/auth/me', requireAuthenticated, async (c) => {
    const userId = c.var.principal?.userId
    if (userId === undefined) return authenticationRequired(c)
    const profile = await accounts.profile(userId)
    return profile === undefined ? userNotFound(c) : c.json(profile)
  })

  routes.put('/api/auth/profile', requireAuthenticated, jsonBody(profileInput), async (c) => {
    const userId = c.var.principal?.userId
    if (userId === undefined) return authenticationRequired(c)
    const profile = await accounts.updateProfile(userId, c.req.valid('json'))
    return profile === undefined ? userNotFound(c) : c.json(profile)
  })

  routes.post(
    '/api/auth/change-email',
    requireAuthenticated,
    jsonBody(changeEmailInput),
    async (c) => {
      const userId = c.var.principal?.userId
      if (userId === undefined) return authenticationRequired(c)
      switch (await accounts.changeEmail(userId, c.req.valid('json'))) {
        case 'sent':
          return c.body(null, 204)
        case 'notFound':
          return userNotFound(c)
        case 'wrongPassword':
          return c.json(apiError('Invalid password'), 401)
        case 'emailTaken':
          return c.json(apiError('Email already exists'), 409)
        case 'deliveryFailed':
          return c.json(apiError('Confirmation email could not be delivered'), 502)
      }
    },
  )

  routes.post(
    '/api/auth/change-password',
    requireAuthenticated,
    jsonBody(changePasswordInput),
    async (c) => {
      const userId = c.var.principal?.userId
      if (userId === undefined) return authenticationRequired(c)
      switch (await accounts.changePassword(userId, c.req.valid('json'))) {
        case 'changed':
          return c.body(null, 204)
        case 'notFound':
          return userNotFound(c)
        case 'wrongPassword':
          return c.json(apiError('Invalid password'), 401)
      }
    },
  )

  routes.post('/api/auth/logout', requireAuthenticated, (c) => {
    auth.signOut(c)
    return c.body(null, 204)
  })

  const admin = new Hono()
  admin.use(requireAdmin)

  admin.post('/', jsonBody(supplierLoginInput), async (c) => {
    const result = await accounts.createSupplierLogin(c.req.valid('json'))
    switch (result) {
      case 'emailTaken':
        return c.json(apiError('Email already exists'), 409)
      // The foreign key decided this; the caller learns the field, never a constraint name.
      case 'unknownSupplier':
        return c.json(
          apiError('Validation failed', { supplierId: ['Supplier does not exist'] }),
          400,
        )
      case 'invitationDeliveryFailed':
        return c.json(
          apiError(
            'The supplier login was created, but its invitation email could not be delivered',
          ),
          502,
        )
      default:
        c.header('Location', `/api/admin/supplier-logins/${String(result.userId)}`)
        return c.json(result, 201)
    }
  })

  admin.get(
    '/',
    zValidator('query', supplierLoginQuery, (result, c) =>
      result.success ? undefined : c.json(validationFailed(result.error), 400),
    ),
    async (c) => c.json(await accounts.listSupplierLogins(c.req.valid('query').supplierId)),
  )

  // A malformed id can never name a supplier login, so it answers like an id naming a customer.
  admin.delete('/:userId', async (c) => {
    const text = c.req.param('userId')
    const userId = Number(text)
    const deleted =
      /^\d+$/.test(text) && Number.isSafeInteger(userId)
        ? await accounts.deleteSupplierLogin(userId)
        : false
    return deleted ? c.body(null, 204) : c.json(apiError('Supplier login not found'), 404)
  })

  routes.route('/api/admin/supplier-logins', admin)
  return routes
}

const authenticationRequired = (c: Context) => c.json(apiError('Authentication required'), 401)
const userNotFound = (c: Context) => c.json(apiError('User not found'), 401)
