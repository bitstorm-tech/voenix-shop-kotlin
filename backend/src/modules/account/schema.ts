import type { Generated } from 'kysely'

/**
 * The `users` table (V11): the e-mail doubles as the login name (unique on `LOWER(email)`), the
 * lockout state, the flat shipping and billing columns, and the `supplier_id` of a supplier login.
 */
export interface UsersTable {
  id: Generated<number>
  email: string
  email_confirmed: Generated<boolean>
  password_hash: string
  created_at: Generated<Date>
  failed_login_count: Generated<number>
  locked_until: Date | null
  shipping_first_name: string | null
  shipping_last_name: string | null
  shipping_street: string | null
  shipping_house_number: string | null
  shipping_postal_code: string | null
  shipping_city: string | null
  shipping_country: string | null
  shipping_phone: string | null
  billing_first_name: string | null
  billing_last_name: string | null
  billing_street: string | null
  billing_house_number: string | null
  billing_postal_code: string | null
  billing_city: string | null
  billing_country: string | null
  billing_phone: string | null
  has_separate_billing_address: Generated<boolean>
  supplier_id: number | null
}

/** The `user_roles` table (V11): plain role strings, primary key `(user_id, role)`. */
export interface UserRolesTable {
  user_id: number
  role: string
}

/**
 * The `account_tokens` table (V11): the SHA-256 hash of a mailed single-use token, one row per
 * `(user_id, purpose)`, with the pending address of an e-mail change.
 */
export interface AccountTokensTable {
  id: Generated<number>
  user_id: number
  purpose: string
  token_hash: string
  new_email: string | null
  expires_at: Date
}

declare module '@/platform/db/database' {
  interface Tables {
    users: UsersTable
    user_roles: UserRolesTable
    account_tokens: AccountTokensTable
  }
}
