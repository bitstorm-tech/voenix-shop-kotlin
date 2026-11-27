import type { Kysely } from 'kysely'
import type { EmailModule } from '@/modules/email'
import type { AccessTokens, Auth, SessionAccounts, SupplierAccounts } from '@/platform/auth/auth'
import type { Tables } from '@/platform/db/database'
import { createPasswordHasher, DEFAULT_PBKDF2_ITERATIONS, tokenHashOf } from './credentials'
import { createAccountMails } from './mails'
import { createAccountRepository } from './repository'
import { accessTokenRoutes, accountRoutes, type AccountRateLimits } from './routes'
import { createAccountService } from './service'

export type { AccountSummary } from './repository'
export type { AccountRateLimits } from './routes'

/**
 * The account module: registration, login (the one production caller of `auth.signIn`), profile,
 * the mailed links, the administrator's supplier logins and access tokens. It exports one
 * capability, `supplierAccounts`, which the composition root hands to `requireSupplier`.
 *
 * `frontendBaseUrl` is `settings.frontend.baseUrl`, the base of every mailed link. `now` and
 * `pbkdf2Iterations` exist for the tests: a clock they can move, and a cheap work factor.
 */
export function createAccountModule({
  db,
  auth,
  userEmails,
  frontendBaseUrl,
  rateLimits,
  now = () => new Date(),
  pbkdf2Iterations = DEFAULT_PBKDF2_ITERATIONS,
}: {
  db: Kysely<Tables>
  auth: Auth
  userEmails: EmailModule['userEmails']
  frontendBaseUrl: string
  rateLimits?: AccountRateLimits
  now?: () => Date
  pbkdf2Iterations?: number
}) {
  const repository = createAccountRepository(db)
  const service = createAccountService({
    repository,
    mails: createAccountMails({ userEmails, frontendBaseUrl }),
    passwords: createPasswordHasher(pbkdf2Iterations),
    now,
  })
  const supplierAccounts: SupplierAccounts = { supplierIdOf: repository.findSupplierId }
  const accountDirectory = {
    findByIds: repository.findSummariesByIds,
    findByEmail: repository.findSummaryByEmail,
  }
  const routes = accountRoutes(service, auth, rateLimits)
  routes.route('/', accessTokenRoutes(repository))
  return { routes, supplierAccounts, accountDirectory }
}

export type AccountDirectory = ReturnType<typeof createAccountModule>['accountDirectory']

/**
 * The lookup `createAuth` resolves a bearer token with. It is created on its own, before the auth
 * and therefore before this module, which needs the auth.
 */
export function createAccessTokens(db: Kysely<Tables>): AccessTokens {
  const repository = createAccountRepository(db)
  return {
    principalOf: (token) => repository.accessTokenPrincipal(tokenHashOf(token)),
    markUsed: (token) => repository.markAccessTokenUsed(tokenHashOf(token)),
  }
}

/**
 * The lookup `createAuth` resolves a session cookie with, created before the auth for the same
 * reason as `createAccessTokens`.
 */
export function createSessionAccounts(db: Kysely<Tables>): SessionAccounts {
  return { principalOf: createAccountRepository(db).sessionPrincipal }
}
