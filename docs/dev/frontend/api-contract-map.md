# API contract map

The Vue frontend in `frontend/` was written against the legacy .NET backend. The
Kotlin backend is now the authority, and the two do not agree everywhere. This
document is the complete list of the disagreements: **one row per `/api/…`
literal in `frontend/src`**, checked against the HTTP table of the backend
package that owns the route.

It exists so that nobody has to guess whether a surface has been migrated. When
the frontend migration is finished, every row is either `matches` or has an
explicit disposition. The closing sweep (issue #101) re-diffs this file against
the code and fails if a literal has no row.

## How to read a row

| Column | Meaning |
| --- | --- |
| Frontend file | Where the literal lives. Line numbers are omitted on purpose — they rot; the path and the method are enough to find it. |
| Called today | The method and path the frontend sends **right now**, before migration. |
| Kotlin route | The route the Kotlin backend actually serves, from `docs/dev/backend/<module>-package.md`. |
| Status | See the vocabulary below. |
| Ticket | The sub-ticket of issue #84 that closes the gap. `—` means nothing to do. |

The status vocabulary is fixed. Only these six values appear:

- **matches** — same method, same path, same body and response shape. Nothing to
  change. A row can still carry a ticket when the call moves onto `lib/api.ts`
  or when only its *error handling* changes.
- **renamed** — the route exists on the Kotlin side under a different path.
- **envelope mismatch** — right route, wrong wrapper: the frontend unwraps
  `data.items` (or `data.categories`) where the backend answers a bare JSON
  array, or the field names of the body differ.
- **behavior change** — the frontend has to do something structurally
  different, not just rename a field: an extra request, a different success
  status, a different error discriminator.
- **frontend-only** — a literal with no route behind it at all.
- **backend-only** — a Kotlin route no frontend file calls.

## How this map was produced

1. `grep -rn "/api/" frontend/src`, ignoring the colocated `__tests__/`
   directories. Test files only repeat the literals of the code they test, so
   they add no rows; the closing sweep updates them together with their store.
2. Every hit was matched against the HTTP table of the owning backend package
   guide in `docs/dev/backend/`, and — where a guide left a body shape open —
   against the Ktor route file itself.

That yields **106 call sites**. None of them is `frontend-only`: every literal
the frontend sends today has a counterpart in the Kotlin backend, even when the
path or the payload has to change. That is a useful result on its own — the
frontend invents no endpoint that the backend never grew.

Two backend-wide facts explain most of the rows, so they are stated once here
instead of in every line:

- **Lists are bare JSON arrays.** The legacy backend wrapped collections in
  `{ "items": [...] }`. The Kotlin backend does not, anywhere. Wherever a store
  reads `data.items`, the row says `envelope mismatch`.
- **Reorder bodies are `{ "sourceId", "targetId" }`.** The frontend still sends
  entity-specific names (`sourceCategoryId`, `sourcePromptId`, …). Every
  `PUT …/order` row therefore carries an envelope mismatch on top of its list
  wrapper.

## Storefront: catalog reads

| Frontend file | Called today | Kotlin route | Status | Ticket |
| --- | --- | --- | --- | --- |
| `stores/shop/articleCategories.ts` | `GET /api/articles/categories` | `GET /api/articles/mugs/categories` | renamed | #88 |
| `stores/shop/mugs.ts` | `GET /api/articles/mugs` | `GET /api/articles/mugs` | envelope mismatch | #88 |
| `stores/shop/prompts.ts` | `GET /api/prompts` | `GET /api/prompts` | envelope mismatch | #88 |

The categories route is the clearest rename in the whole map. The legacy answer
was a map from article type to category list, so the store still does
`allCategories['MUG']`. The Kotlin route answers a bare array of categories with
their subcategories nested, and the article type never appears
(`docs/dev/backend/article-package.md`, "The storefront").

## Storefront: cart

| Frontend file | Called today | Kotlin route | Status | Ticket |
| --- | --- | --- | --- | --- |
| `stores/shop/cart.ts` | `GET /api/cart` | `GET /api/cart` | envelope mismatch | #91 |
| `stores/shop/cart.ts` | `POST /api/cart/items` (multipart, part `image`) | `POST /api/cart/images` **then** `POST /api/cart/items` (JSON, `imageId`) | behavior change | #91 |
| `stores/shop/cart.ts` | `POST /api/cart/order-items/{orderItemId}` | same | matches | #91 |
| `stores/shop/cart.ts` | `PATCH /api/cart/items/{itemId}` | same | matches | #91 |
| `stores/shop/cart.ts` | `DELETE /api/cart/items/{itemId}` | same | matches | #91 |
| `stores/shop/cart.ts` | `POST /api/cart/promotion` | same | matches | #91 |
| `stores/shop/cart.ts` | `DELETE /api/cart/promotion` | same | matches | #91 |

Adding a line is the one structural change: today a single multipart request
carries both the print image and the line data. The Kotlin cart mints the image
first (`POST /api/cart/images` → `201 {"id": 42}`) and only then accepts a JSON
line that names `imageId` (`docs/dev/backend/cart-package.md`).

The envelope mismatch on `GET /api/cart` is not the `items` wrapper — the
`CartView` really does have an `items` array — but the line fields around it.
The frontend `CartItem` expects `generatedEditedImageId`, `originalPrice`,
`promptOriginalPrice`, and `customData`; the `CartView` line carries `imageId`,
`price`, `promptPrice`, and an `available` flag the frontend does not know yet.
Because every mutation answers the complete recalculated `CartView`, the same
mismatch applies to all seven rows, which is why they all belong to one ticket.

Nothing here pins how a guest cart becomes a user cart: the store refetches and
adopts whatever the backend answers.

## Storefront: checkout, orders, countries, coins, generation

| Frontend file | Called today | Kotlin route | Status | Ticket |
| --- | --- | --- | --- | --- |
| `stores/shop/checkout.ts` | `POST /api/checkout` | `POST /api/checkout` | matches | #93 |
| `stores/shop/checkout.ts` | `GET /api/checkout/orders/{orderId}` | `GET /api/orders/{orderId}` | renamed | #93 |
| `stores/shop/orders.ts` | `GET /api/checkout/orders` | `GET /api/orders` | renamed | #94 |
| `stores/shop/countries.ts` | `GET /api/countries` | `GET /api/countries` | envelope mismatch | #92 |
| `stores/shop/magicCoins.ts` | `GET /api/magic-coins/balance` | same | matches | — |
| `stores/shop/imageGeneration.ts` | `POST /api/generator/generate` | same | behavior change | #95 |

`/api/checkout` owns exactly two routes — the submit and the payment retry. The
order **reads** belong to the order module under `/api/orders`, so both order
paths in the frontend are renames (`docs/dev/backend/checkout-package.md`,
`order-package.md`).

The order representation also renames almost every money field:
`totalAmountInCents` → `total`, `shippingCostInCents` → `shippingCost`,
`priceAtTime` → `price`, `promptPriceAtTime` → `promptPrice`,
`generatedEditedImageId` → `imageId`, plus new `subtotal` and `discountAmount`
and no `customData`. The status strings stay uppercase on the wire
(`PENDING|PAID|CANCELLED`, payment `…|CANCELED|null` with one L), so
`normalizeStatus` in `stores/shop/orders.ts` and the `.toLowerCase()` calls in
`stores/shop/checkout.ts` both go away.

`POST /api/generator/generate` matches on method, path, and multipart field
names (`image`, `promptId`). What changes is the refusal handling: `429` for the
per-IP rate limit and `413` for the request-size bound carry **no** machine
readable `code` (decision 3 of issue #84), so the store branches on the HTTP
status: `imageGeneration.ts` records `errorStatus` and `errorRetryAfterSeconds`
next to `errorCode`, and `GenerateStep.vue` picks the localized message from
them. The `INSUFFICIENT_MAGIC_COINS` branch stays code-based, because that
refusal does carry a `code`.

## Auth and session

| Frontend file | Called today | Kotlin route | Status | Ticket |
| --- | --- | --- | --- | --- |
| `lib/api.ts` | `GET /api/antiforgery/token` | same | matches | — |
| `stores/shared/auth.ts` | `GET /api/auth/me` | same | matches | #89 |
| `stores/shared/auth.ts` | `POST /api/auth/login` | same | behavior change | #89 |
| `stores/shared/auth.ts` | `POST /api/auth/logout` | same | behavior change | #89 |
| `stores/shared/auth.ts` | `POST /api/auth/register` | same | behavior change | #89 |
| `stores/shared/auth.ts` | `POST /api/auth/confirm-email` | same | behavior change | #89 |
| `stores/shared/auth.ts` | `POST /api/auth/resend-confirmation` | same | behavior change | #89 |
| `stores/shared/auth.ts` | `POST /api/auth/forgot-password` | same | behavior change | #89 |
| `stores/shared/auth.ts` | `POST /api/auth/reset-password` | same | behavior change | #89 |
| `stores/shared/auth.ts` | `POST /api/auth/confirm-change-email` | same | behavior change | #89 |
| `stores/shared/auth.ts` | `PUT /api/auth/profile` | same | matches | #89 |
| `stores/shared/auth.ts` | `POST /api/auth/change-email` | same | behavior change | #89 |
| `stores/shared/auth.ts` | `POST /api/auth/change-password` | same | behavior change | #89 |

Every auth path and every request body already agrees with the Kotlin backend —
this is the one module the migration deliberately kept on its legacy paths
(`docs/dev/backend/account-package.md`). The break is on the way back: the
legacy backend answered a `{ success, message, code }` envelope, and the Kotlin
backend answers `204 No Content` on success and the shared `ApiError` shape on
failure.

That is not a cosmetic difference. The helper `postAuth` in
`stores/shared/auth.ts` does `await response.json()` unconditionally, and a
`204` body is empty, so parsing throws — the `catch` then reports a *successful*
login as a network error. Nine of the thirteen rows share exactly this defect,
which is why they are one ticket. `GET /api/auth/me` and `PUT /api/auth/profile`
answer the same `AccountProfile` JSON the frontend already expects, so they
match; they still move onto `lib/api.ts` so that the profile write sends
`X-XSRF-TOKEN`.

The frontend also branches on `result.code === 'EMAIL_NOT_CONFIRMED'`. There is
no such code. The discriminator is the status: `401` bad credentials, `403`
unconfirmed address, `429` lockout.

## Images

These literals are not `fetch` calls — they are `<img src>` URLs built from a
filename or an id. All of them match the image module's routes
(`docs/dev/backend/image-package.md`); the folder names under
`/api/images/public/` are the ones the backend writes into.

| Frontend file | Built URL | Kotlin route | Status | Ticket |
| --- | --- | --- | --- | --- |
| `lib/variantExampleImage.ts` | `/api/images/public/{size}/articles/mugs/variant-example-images/{filename}` | `GET /api/images/public/{size}/{filename...}` | matches | #97 |
| `lib/promptExampleImage.ts` | `/api/images/public/{size}/prompt-example-images/{filename}` | same | matches | #99 |
| `stores/shop/prompts.ts` | `/api/images/public/400/prompt-example-images/{filename}` | same | matches | #88 |
| `components/shop/HeaderCategoryMenuPanel.vue` | `/api/images/public/400/articles/subcategory-example-images/{filename}` | same | matches | — |
| `components/admin/article/subcategory/AdminArticleSubcategoryDialog.vue` | `/api/images/public/400/articles/subcategory-example-images/{filename}` | same | matches | #96 |
| `components/shop/wizard/steps/SelectMugStep.vue` | `/api/images/public/400/articles/mugs/variant-example-images/{filename}` | same | matches | — |
| `components/shop/editor/ProductContextBar.vue` | `/api/images/public/200/articles/mugs/variant-example-images/{filename}` | same | matches | — |
| `components/shop/CartItemPreviewDialog.vue` | `/api/images/public/200/articles/mugs/variant-example-images/{filename}` | same | matches | — |
| `components/shop/CartItemPreviewDialog.vue` | `/api/images/guest/1600/{id}` | `GET /api/images/guest/{size}/{id}` | matches | #91 |
| `components/shop/orders/OrderDetails.vue` | `/api/images/guest/320/{id}` | same | matches | #94 |
| `views/shop/CartView.vue` | `/api/images/guest/200/{id}` | same | matches | #91 |
| `views/shop/OrderView.vue` | `GET /api/images/guest/1600/{id}` (raw `fetch`, blob) | same | matches | #94 |

The three prompt/variant example-image helpers duplicate each other:
`stores/shop/prompts.ts` builds the same URL that `lib/promptExampleImage.ts`
already exports. The path is correct in all three, so this is a tidy-up for the
ticket that touches the store, not a contract gap.

`views/shop/OrderView.vue` is the only *image* row that is a real request: it
downloads the print image as a blob through raw `fetch`. It moves onto
`lib/api.ts` with the rest of the order surface.

The id a cart or order line carries is the field that changes
(`generatedEditedImageId` → `imageId`), not the route — hence the tickets on
those rows.

## Admin: reference data

| Frontend file | Called today | Kotlin route | Status | Ticket |
| --- | --- | --- | --- | --- |
| `stores/admin/suppliers.ts` | `GET /api/admin/suppliers` | same | envelope mismatch | #87 |
| `stores/admin/suppliers.ts` | `GET /api/admin/suppliers/{id}` | same | matches | #87 |
| `stores/admin/suppliers.ts` | `POST /api/admin/suppliers` | same | matches | #87 |
| `stores/admin/suppliers.ts` | `PUT /api/admin/suppliers/{id}` | same | matches | #87 |
| `stores/admin/suppliers.ts` | `DELETE /api/admin/suppliers/{id}` | same | matches | #87 |
| `stores/admin/vat.ts` | `GET /api/admin/vat` | same | matches | — |
| `stores/admin/vat.ts` | `GET /api/admin/vat/{id}` | same | matches | — |
| `stores/admin/vat.ts` | `POST /api/admin/vat` | same | matches | — |
| `stores/admin/vat.ts` | `PUT /api/admin/vat/{id}` | same | matches | — |
| `stores/admin/vat.ts` | `DELETE /api/admin/vat/{id}` | same | matches | — |
| `stores/admin/promotions.ts` | `GET /api/admin/promotions` | same | envelope mismatch | #87 |
| `stores/admin/promotions.ts` | `GET /api/admin/promotions/{id}` | same | envelope mismatch | #87 |
| `stores/admin/promotions.ts` | `POST /api/admin/promotions` | same | envelope mismatch | #87 |
| `stores/admin/promotions.ts` | `PUT /api/admin/promotions/{id}` | same | envelope mismatch | #87 |
| `stores/admin/promotions.ts` | `DELETE /api/admin/promotions/{id}` | same | matches | #87 |
| `stores/admin/prices.ts` | `GET /api/admin/prices/default` (raw `fetch`) | same | matches | #87 |
| `stores/admin/prices.ts` | `POST /api/admin/prices/calculate` | same | matches | #87 |
| `composables/useAdminCountries.ts` | `GET /api/admin/countries` (raw `fetch`) | same | envelope mismatch | #87 |

The VAT store is the only *admin* surface that already reads a bare array —
`stores/shop/orders.ts` is the other one in the frontend, and that one is on the
wrong path. VAT is listed anyway, because "already correct" is a result the
closing sweep needs to see.

The promotions rows are an envelope mismatch in an unusual direction: the shape
is **asymmetric**. A request sends flat `discountType` and `discountValue`, a
response groups them under a nested `discount` object, and validation error keys
stay flat (`docs/dev/backend/promotion-package.md`). Both directions have to be
modelled; a single shared type will not fit.

## Admin: article categories and subcategories

| Frontend file | Called today | Kotlin route | Status | Ticket |
| --- | --- | --- | --- | --- |
| `stores/admin/articleCategories.ts` | `GET /api/admin/articles/categories` | same | envelope mismatch | #96 |
| `stores/admin/articleCategories.ts` | `GET /api/admin/articles/categories/{id}` | same | matches | #96 |
| `stores/admin/articleCategories.ts` | `POST /api/admin/articles/categories` | same | matches | #96 |
| `stores/admin/articleCategories.ts` | `PUT /api/admin/articles/categories/{id}` | same | matches | #96 |
| `stores/admin/articleCategories.ts` | `DELETE /api/admin/articles/categories/{id}` | same | matches | #96 |
| `stores/admin/articleCategories.ts` | `PUT /api/admin/articles/categories/order` | same | envelope mismatch | #96 |
| `stores/admin/articleSubcategories.ts` | `GET /api/admin/articles/subcategories` | same | envelope mismatch | #96 |
| `stores/admin/articleSubcategories.ts` | `GET /api/admin/articles/subcategories/{id}` | same | matches | #96 |
| `stores/admin/articleSubcategories.ts` | `POST /api/admin/articles/subcategories` (multipart) | same path, **JSON** body | behavior change | #96 |
| `stores/admin/articleSubcategories.ts` | `PUT /api/admin/articles/subcategories/{id}` (multipart) | same path, **JSON** body | behavior change | #96 |
| `stores/admin/articleSubcategories.ts` | `DELETE /api/admin/articles/subcategories/{id}` | same | matches | #96 |
| `stores/admin/articleSubcategories.ts` | `PUT /api/admin/articles/subcategories/order` | same | envelope mismatch | #96 |

Both reorder rows send `{sourceCategoryId, targetCategoryId}` respectively
`{sourceSubcategoryId, targetSubcategoryId}` and read `data.items`. The backend
takes `{sourceId, targetId}` and answers the complete new order as a bare array.

Subcategory writes are the behavior change of this section. The frontend builds
a `FormData` with the image file and a `removeExampleImage` flag. The Kotlin
route takes plain JSON with an `exampleImageFilename`, and the file is uploaded
separately beforehand — see the backend-only row for
`POST /api/admin/articles/subcategories/example-images`.

## Admin: mugs

| Frontend file | Called today | Kotlin route | Status | Ticket |
| --- | --- | --- | --- | --- |
| `stores/admin/articles.ts` | `GET /api/admin/articles` (raw `fetch`) | `GET /api/admin/articles/mugs` | renamed | #97 |
| `stores/admin/articles.ts` | `GET /api/admin/articles/{id}` (raw `fetch`) | `GET /api/admin/articles/mugs/{id}` | renamed | #97 |
| `stores/admin/articles.ts` | `POST /api/admin/articles` | `POST /api/admin/articles/mugs` | renamed | #97 |
| `stores/admin/articles.ts` | `PUT /api/admin/articles/{id}` | `PUT /api/admin/articles/mugs/{id}` | renamed | #97 |
| `stores/admin/articles.ts` | `DELETE /api/admin/articles/{id}` | `DELETE /api/admin/articles/mugs/{id}` | renamed | #97 |
| `stores/admin/articles.ts` | `PUT /api/admin/articles/order` | `PUT /api/admin/articles/mugs/order` | renamed | #97 |
| `stores/admin/articles.ts` | `POST /api/admin/articles/mug-variant-example-images` | `POST /api/admin/articles/mugs/variant-example-images` | renamed | #97 |

The whole mug admin family moved one segment down. The legacy backend had one
`article` resource with an `articleType` discriminator in the body; the Kotlin
backend has a route family **per type**, and `articleType` is gone from both
directions. On top of the rename, the list is a bare array, the reorder body is
`{sourceId, targetId}`, and `priceId` is replaced by an embedded `price`
(`docs/dev/backend/article-package.md`).

## Admin: prompt categories and subcategories

| Frontend file | Called today | Kotlin route | Status | Ticket |
| --- | --- | --- | --- | --- |
| `stores/admin/promptCategories.ts` | `GET /api/admin/prompts/categories` | same | envelope mismatch | #98 |
| `stores/admin/promptCategories.ts` | `GET /api/admin/prompts/categories/{id}` | same | matches | #98 |
| `stores/admin/promptCategories.ts` | `POST /api/admin/prompts/categories` | same | matches | #98 |
| `stores/admin/promptCategories.ts` | `PUT /api/admin/prompts/categories/{id}` | same | matches | #98 |
| `stores/admin/promptCategories.ts` | `DELETE /api/admin/prompts/categories/{id}` | same | matches | #98 |
| `stores/admin/promptCategories.ts` | `PUT /api/admin/prompts/categories/order` | same | envelope mismatch | #98 |
| `stores/admin/promptCategories.ts` | `GET /api/admin/prompts/subcategories` | same | envelope mismatch | #98 |
| `stores/admin/promptCategories.ts` | `GET /api/admin/prompts/subcategories/{id}` | same | envelope mismatch | #98 |
| `stores/admin/promptCategories.ts` | `POST /api/admin/prompts/subcategories` | same | envelope mismatch | #98 |
| `stores/admin/promptCategories.ts` | `PUT /api/admin/prompts/subcategories/{id}` | same | envelope mismatch | #98 |
| `stores/admin/promptCategories.ts` | `DELETE /api/admin/prompts/subcategories/{id}` | same | matches | #98 |
| `stores/admin/promptCategories.ts` | `PUT /api/admin/prompts/subcategories/order` | same | envelope mismatch | #98 |

The subcategory rows were an envelope mismatch even where the frontend reads a
single object: `syncSubcategory` re-attached a nested category object, while the
Kotlin representation carries a flat `categoryId`. Ticket #98 closed both
columns: the store reads bare arrays, holds `AdminPromptSubcategoryDto` with a
flat `categoryId`, and resolves the display name through `categoryName(id)` from
the category list it already holds. Both reorder routes send the shared
`ReorderRequest { sourceId, targetId }` and read the dense answer — the
subcategory answer covers only the affected category, so the store merges it per
category and leaves the other categories untouched.

## Admin: prompt slots and slot variants

| Frontend file | Called today | Kotlin route | Status | Ticket |
| --- | --- | --- | --- | --- |
| `stores/admin/promptSlots.ts` | `GET /api/admin/prompts/slot-types` | `GET /api/admin/prompts/slots` | renamed | #98 |
| `stores/admin/promptSlots.ts` | `GET /api/admin/prompts/slot-types/{id}` | `GET /api/admin/prompts/slots/{id}` | renamed | #98 |
| `stores/admin/promptSlots.ts` | `POST /api/admin/prompts/slot-types` | `POST /api/admin/prompts/slots` | renamed | #98 |
| `stores/admin/promptSlots.ts` | `PUT /api/admin/prompts/slot-types/{id}` | `PUT /api/admin/prompts/slots/{id}` | renamed | #98 |
| `stores/admin/promptSlots.ts` | `DELETE /api/admin/prompts/slot-types/{id}` | `DELETE /api/admin/prompts/slots/{id}` | renamed | #98 |
| `stores/admin/promptSlots.ts` | `GET /api/admin/prompts/slot-variants` | same | envelope mismatch | #98 |
| `stores/admin/promptSlots.ts` | `GET /api/admin/prompts/slot-variants/{id}` | same | envelope mismatch | #98 |
| `stores/admin/promptSlots.ts` | `POST /api/admin/prompts/slot-variants` | same | envelope mismatch | #98 |
| `stores/admin/promptSlots.ts` | `PUT /api/admin/prompts/slot-variants/{id}` | same | envelope mismatch | #98 |
| `stores/admin/promptSlots.ts` | `DELETE /api/admin/prompts/slot-variants/{id}` | same | matches | #98 |

"Slot type" is legacy vocabulary. The Kotlin module calls the thing a **slot**,
and the route segment follows the name (`docs/dev/backend/prompt-package.md`).
A slot variant then carries flat `slotId` and `slotName` instead of a nested
slot-type summary, and the update body carries no `slotId` at all — a variant
cannot be moved to another slot.

Ticket #98 closed these rows. `stores/admin/promptSlots.ts` calls
`/api/admin/prompts/slots[/{id}]`, reads bare arrays, and renamed every
identifier with the entity (`slots`, `variantsBySlotId`, `PromptSlot*Error`).
A slot that does not exist on a variant create is a `400` field error on
`slotId`, not a `404`, so `PromptSlotVariantSlotTypeNotFoundError` is gone and
`PromptSlotValidationError` carries the field messages instead.

## Admin: prompts

| Frontend file | Called today | Kotlin route | Status | Ticket |
| --- | --- | --- | --- | --- |
| `stores/admin/prompts.ts` | `GET /api/admin/prompts` | same | envelope mismatch | #99 |
| `stores/admin/prompts.ts` | `GET /api/admin/prompts/{id}` | same | envelope mismatch | #99 |
| `stores/admin/prompts.ts` | `POST /api/admin/prompts` | same | envelope mismatch | #99 |
| `stores/admin/prompts.ts` | `PUT /api/admin/prompts/{id}` | same | envelope mismatch | #99 |
| `stores/admin/prompts.ts` | `POST /api/admin/prompts/example-images` | same | matches | #99 |
| `stores/admin/prompts.ts` | `PUT /api/admin/prompts/order` | same | envelope mismatch | #99 |

Every path in this group is already right. What changes is the payload: list row
and detail are **flat** (`categoryId`/`categoryName` instead of nested objects),
`priceId` is replaced by an embedded calculated `price`, and `position` is
response-only. Note also what is *not* in the table: there is no delete route
for a prompt. A prompt is retired with the `archived` flag.

## Backend routes with no frontend caller

The map would not be a completeness proof without the other direction. These
Kotlin routes exist and nothing in `frontend/src` calls them today.

| Kotlin route | Disposition | Ticket |
| --- | --- | --- |
| `POST /api/cart/images` | The first half of the two-step line upload — new caller. | #91 |
| `POST /api/admin/articles/subcategories/example-images` | Pre-upload that replaces the multipart subcategory write. | #96 |
| `GET /api/articles/mugs/categories` | The storefront navigation read, under its new path. | #88 |
| `GET /api/orders`, `GET /api/orders/{orderId}` | The order reads, under their new path. | #94, #93 |
| `POST /api/checkout/orders/{orderId}/payment` | The payment retry. The legacy shop had no such journey; #93 built the caller — `stores/shop/checkout.ts` (`startPayment`), offered on the order confirmation page. | #93 |
| `GET /api/admin/orders/{orderId}/production-pdfs` | Lists one `{supplierId, fileName}` per supplier of an order. Joe approved a narrow admin utility (decision 2 of #84): order-ID input, list, download. | #100 |
| `GET /api/admin/orders/{orderId}/production-pdfs/{supplierId}` | Downloads one supplier's PDF. Same utility. | #100 |
| `GET /api/admin/production/destinations` and its four CRUD siblings | **Out of scope** (#84, "Not now"): no admin UI for production destinations. | — |
| `POST /api/admin/prices`, `GET /api/admin/prices/{id}`, `PUT /api/admin/prices/{id}` | **No caller by design.** A price belongs to its article or prompt and is written inside that write; the standalone endpoints are the development-phase addition described in `docs/dev/backend/pricing-package.md`. | — |
| `GET /api/admin/countries/{id}`, `POST /api/admin/countries`, `PUT /api/admin/countries/{id}`, `DELETE /api/admin/countries/{id}` | **Out of scope.** The frontend only needs the admin *list* (for dropdowns); a country admin UI is not part of #84, and the `countries` activation flag is a backend follow-up in `docs/migration/all-post-migration.md`. | — |
| `GET /api/images/private/{size}/{filename...}` | No caller. Private images are reached through the guest resolver route instead. Nothing to build. | — |
| `POST /api/payments/webhook/{secret}` | Mollie calls this, never a browser. It must stay uncalled by the frontend. | — |

`GET /api/prompts` accepts an optional `categoryId` query parameter the
storefront store does not use yet; that is a capability, not a gap, and #88
decides whether the filter is worth adopting.

## Score

| Status | Rows |
| --- | --- |
| matches | 47 |
| envelope mismatch | 30 |
| renamed | 15 |
| behavior change | 14 |
| frontend-only | 0 |
| **Total frontend call sites** | **106** |

Plus 22 backend-only routes, of which 8 get a caller during this migration and
14 are dispositioned as out of scope or intentionally uncalled.

The single largest bucket is `envelope mismatch`, and almost all of it is the
same two defects repeated: the `data.items` unwrap and the entity-specific
reorder body. That is why the migration is sliced by module rather than by
defect — the fix is mechanical, but each store needs its own wire-faithful types
and its own tests.
