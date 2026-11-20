import type { Kysely } from 'kysely'
import type { EmailModule } from '@/modules/email'
import type { AccessTokens, Auth, SupplierAccounts } from '@/platform/auth/auth'
import type { Tables } from '@/platform/db/database'
import { createPasswordHasher, DEFAULT_PBKDF2_ITERATIONS, tokenHashOf } from './credentials'
import { createAccountMails } from './mails'
import { createAccountRepository } from './repository'
import { accessTokenRoutes, accountRoutes } from './routes'
import { createAccountService } from './service'

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
  now = () => new Date(),
  pbkdf2Iterations = DEFAULT_PBKDF2_ITERATIONS,
}: {
  db: Kysely<Tables>
  auth: Auth
  userEmails: EmailModule['userEmails']
  frontendBaseUrl: string
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
  const routes = accountRoutes(service, auth)
  routes.route('/', accessTokenRoutes(repository))
  return { routes, supplierAccounts }
}

/**
 * The lookup `createAuth` resolves a bearer token with. It is created on its own, before the auth
 * and therefore before this module, which needs the auth.
 */
export function createAccessTokens(db: Kysely<Tables>): AccessTokens {
  const repository = createAccountRepository(db)
  return { principalOf: (token) => repository.accessTokenPrincipal(tokenHashOf(token)) }
}
