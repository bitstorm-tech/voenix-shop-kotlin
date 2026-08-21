# How the frontend talks to the backend

The Vue frontend has exactly **one** place that calls `fetch`:
[`frontend/src/lib/api.ts`](../../../frontend/src/lib/api.ts). Everything else —
every store, every composable, every view — goes through it. That is a rule, not
a habit: CSRF tokens, error parsing, and `204` handling all live in that one file,
so a call that bypasses it silently loses all three.

This guide explains the client, the error shape it produces, and the conventions
the Pinia stores follow. It assumes you can read TypeScript but not that you know
this codebase. The route-by-route inventory lives next door in
[`api-contract-map.md`](api-contract-map.md).

## The two functions you will use

```ts
import { fetchJson, fetchForm } from '@/lib/api'

// JSON in, JSON out
const mugs = await fetchJson<MugDto[]>('/api/articles/mugs')

// JSON in, nothing out (the backend answers 204 No Content)
await fetchJson<void>(`/api/admin/suppliers/${id}`, {
  method: 'DELETE',
  responseType: 'void',
})

// A file upload: multipart/form-data. The part name is the backend's — `file`, from
// `UploadedImage.FILE_PART_NAME` — not something the frontend gets to choose.
const formData = new FormData()
formData.append('file', file, file.name)
const { id } = await fetchForm<PrintImageId>('/api/cart/images', formData)
```

`fetchJson` sends and parses JSON; pass a plain object as `body` and it is
serialized and given a `Content-Type: application/json` header for you.
`fetchForm` posts a `FormData` and deliberately sets **no** `Content-Type`, so
the browser can add the multipart boundary. It takes the same options object as
`fetchJson`, which is how the one multipart request that answers with bytes
rather than JSON is made:

```ts
// Three parts in, an image out: the generator's own call.
const formData = new FormData()
formData.append('image', file, file.name)
formData.append('promptId', String(promptId))
formData.append('articleId', String(articleId))
const image = await fetchForm<Blob>('/api/generator/generate', formData, {
  responseType: 'blob',
})
```

`responseType` says how the answer is read: `'json'` (the default), `'void'` for
a `204`, `'blob'` for a download, `'text'` for a plain string. A `204` answer is
read as `undefined` whatever you asked for, so a store never tries to parse an
empty body.

Both functions **throw** on a non-2xx answer. There is no `{ ok, data }` result
object to check — the happy path reads like straight-line code, and a refusal is
caught where it can be turned into UI:

```ts
try {
  suppliers.value = await fetchJson<AdminSupplierDto[]>('/api/admin/suppliers')
} catch (err) {
  error.value = toSupplierError(err)
}
```

## What gets thrown: `ApiError`

Every refusal arrives as one class,
[`ApiError`](../../../frontend/src/lib/api.ts). The Kotlin backend answers a
single error body everywhere — `{ "message": …, "code": …, "errors": … }` — and
`ApiError` is that body plus the parts of the HTTP response that carry meaning:

| Property | What it holds |
| --- | --- |
| `status` | The HTTP status. Very often *this* is the discriminator — see below. |
| `message` | The backend's `message`, or `HTTP error {status}` when the body had none. |
| `code` | The machine-readable `code`, or `null`. Most routes have none. |
| `fieldErrors` | Validation messages keyed by the **JSON path** of the offending field. `{}` when there are none. |
| `retryAfterSeconds` | The `Retry-After` header of a `429`, as a number, or `null`. |
| `rawBody` | The unparsed body, for debugging. |

### Discriminate by status and route, not by an invented code

This is the convention that surprises people coming from the legacy .NET
backend, which answered a `{ success, message, code }` envelope with a code for
everything. The Kotlin backend does not. A code exists only where the *same*
status on the *same* route can mean two different things.

So: **the route you called plus the status you got back is the discriminator**,
and a `code` is a bonus.

```ts
// Login: no code anywhere on /api/auth. The status is the answer.
// 401 wrong credentials · 403 e-mail not confirmed · 429 locked out
if (err.status === 403) return 'confirm-email'
```

```ts
// Image generation: 413 is "your file is too big", 429 is "slow down".
// Neither carries a code, on purpose (decision 3 of issue #84).
if (err.status === 413) return t('…imageTooLarge')
if (err.status === 429) return waitMessage(err.retryAfterSeconds)
```

```ts
// The cart reorder does have a code, because 409 alone would be ambiguous.
if (err.status === 409 && err.code === 'ORDER_IMAGE_UNAVAILABLE') { … }
```

Never write a `switch` over codes the backend does not send. If you find one,
it is legacy code that survived the migration.

### `fieldErrors` are keyed by JSON path

A `400 Validation failed` names the field of the **request body** that was
rejected, using its JSON path — not a form input id, and not a C# member name:

```json
{
  "message": "Validation failed",
  "errors": {
    "price.salesVatId": ["Sales VAT does not exist"],
    "mugVariants[0].exampleImageFilename": ["Unknown file name"]
  }
}
```

Views map those paths onto their inputs themselves. Where a screen has tabs, a
small helper does the mapping and also decides which tab to open — see
[`lib/adminArticleErrors.ts`](../../../frontend/src/lib/adminArticleErrors.ts)
for the pattern. That module carries one mapping per article type — a mug's
`mugDetails.heightMm` folds onto the `heightMm` input of the details tab, while a
shirt's `printFrame.widthPct` keeps its whole path, because the calibrator has one
input per percentage and the path is already the name of that input.

## CSRF: handled for you, except where the backend does not ask

Anti-forgery protection is **route-scoped**, not global. Nearly every unsafe
method (`POST`, `PUT`, `PATCH`, `DELETE`) sits inside a protected subtree, and
for those `fetchApi` fetches the token from `GET /api/antiforgery/token`, caches
it, and sends it as the `X-XSRF-TOKEN` header. You do not write any of that.

The exception is the anonymous `/api/auth` routes — login, register, confirm
e-mail, resend confirmation, forgot password, reset password, confirm e-mail
change. They are installed outside the protected subtree
(`installAnonymousRoutes` in `AccountRoutes.kt`), because a visitor who has no
session yet cannot be asked for a session-bound token. Calling
`GET /api/antiforgery/token` for them would be a pointless extra round trip on
the slowest path there is, so those calls pass `skipAntiforgery: true`:

```ts
await fetchJson<void>('/api/auth/login', {
  method: 'POST',
  body: { email, password },
  responseType: 'void',
  skipAntiforgery: true, // an anonymous /api/auth route: nothing to prove yet
})
```

`stores/shared/auth.ts` sets it from one `anonymous` flag per route, and
`auth.spec.ts` asserts that no token request goes out for them. Do not reach for
this option anywhere else: on a protected route, skipping the token turns a
working call into a `400`.

One detail is worth knowing because it looks odd in the code: a stale token is
rejected with `400` and the exact message `Invalid CSRF token`, **without** a
code. The client recognises that message, refreshes the token once, and replays
the request. That message string is a contract with the backend; if it ever
changes there, `CSRF_ERROR_MESSAGE` has to change with it.

Call `clearApiClientCache()` when the session identity changes (login, logout),
so the next request mints a fresh token.

## Store conventions

The stores are Pinia composition-API stores under `stores/shared/`,
`stores/shop/`, `stores/admin/`, and `stores/supplier/`. Five rules run through
all of them.

### 1. The wire shape is the type

A store's interfaces mirror what the backend actually sends, field name for field
name. No renaming into "nicer" names, no adapter layer. If the backend calls it
`imageId`, the TypeScript interface calls it `imageId`. This is what makes the
contract map checkable: you can read a store next to a backend package guide and
compare them line by line.

There is exactly one deliberate exception, and it is worth knowing because it
looks like the adapter layer this rule forbids: the **article stores stamp
`articleType` onto what they return**. The admin article routes are one family
per type (`/api/admin/articles/mugs`, `/api/admin/articles/tshirts`), so the
type is the *path* and not a field of the body — a store that reads both and
merges them would otherwise hand the UI a union nothing can narrow. Stamping the
discriminator the path already stated is not renaming a field; it is writing down
which request the row came from. `stores/shop/catalog.ts` does the same for the
two storefront reads, and everything downstream — `AdminArticleDto`,
`ShopArticle` — is a discriminated union over that tag.

The same applies to values. Statuses are uppercase on the wire, so the unions are
uppercase:

```ts
export type OrderStatus = 'PENDING' | 'PAID' | 'CANCELLED'
export type OrderPaymentStatus =
  | 'OPEN' | 'PENDING' | 'AUTHORIZED' | 'PAID'
  | 'FAILED' | 'CANCELED' | 'EXPIRED'
```

Yes, the payment word has one `L` and the order word has two. They are different
facts written by two different systems (Mollie cancelled the payment; the shop
cancelled the order), and mixing them up is a bug the type system should catch.
Nothing lowercases a status; the German and English labels come from vue-i18n,
keyed by the wire value.

The i18n namespaces follow the same idea one level up: they are named after the
*surface*, not after the article type it happened to show first. Since the shop
sells two types, the storefront namespaces are `productCard` and
`productOverview` — the former `mug*` namespaces are gone, and a key that says
"Tasse" where it means "article" is a bug of the same family as a renamed field.

### 2. Lists are bare arrays

The backend answers a collection as a plain JSON array. There is no `{ items: […] }`
wrapper anywhere, so no store ever writes `data.items`:

```ts
suppliers.value = await fetchJson<AdminSupplierDto[]>('/api/admin/suppliers')
```

### 3. Refusals become named error classes

A store does not hand an `ApiError` to a view. It translates it into a small
class that says what happened in domain terms, and the view decides how to show
it:

```ts
if (error.status === 409) {
  return new ArticleCategoryNameConflictError(error.message)
}
```

The view then does `if (error instanceof ArticleCategoryNameConflictError)` and
puts the message on the name input. The point is that the *store* owns the
knowledge of what a `409` means on that route — that knowledge is written down
once, next to the call, instead of being re-derived in every component.

Validation errors are the exception that proves the rule: they are carried as a
`…ValidationError` holding the whole `fieldErrors` map, because only the view
knows which input each JSON path belongs to.

### 4. Admin reorder is one shared body

Every admin `PUT …/order` route sends the same
[`ReorderRequest { sourceId, targetId }`](../../../frontend/src/stores/admin/reorder.ts)
and gets the complete new order back as a bare array. Do not invent
`sourceCategoryId` / `targetPromptId` variants — the backend takes one shape.

### 5. Write-only secrets are never read back

Some admin bodies carry a secret the backend stores and never answers again: the
SFTP password and the SPOD access token of a production destination
(`stores/admin/productionDestinations.ts`). The response types simply do not have
the field, so there is nothing a form could pre-fill.

That has one consequence a form has to get right: the input starts empty every
time the dialog opens, including on an existing destination, and an empty field
means "keep what is stored" rather than "clear it". The store therefore leaves
the key out of the body instead of sending `null`:

```ts
// undefined disappears in JSON.stringify - the backend keeps the stored secret.
accessToken: value === '' ? undefined : value
```

On a create there is nothing to keep, so the backend answers a missing secret as
a field error on `spod.accessToken` (or `sftp.password`) and the dialog shows it
on that input like any other validation message. The admin surface for these
destinations lives at `/admin/logistics/destinations`: one row per destination
with its supplier, channel, account, and enabled state, and a dialog that swaps
its detail form when the channel changes, because a destination carries exactly
the account block its channel names.

## Where to look next

| Question | File |
| --- | --- |
| Which backend route does this frontend call use? | [`api-contract-map.md`](api-contract-map.md) |
| What does the backend answer on route X? | `docs/dev/backend/<module>-package.md` |
| What changed against the legacy .NET contract, and why? | `docs/migration/<module>-post-migration.md` |
| The frontend's own conventions (folders, UI primitives, routing) | [`frontend/CLAUDE.md`](../../../frontend/CLAUDE.md) |
