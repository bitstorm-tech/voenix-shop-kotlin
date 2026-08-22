# API contract map

Every `/api/…` literal in `frontend/src` has a row here, and every row names the
Kotlin route behind it. That makes this file the completeness proof of the
frontend migration (issue #84): if a literal exists in the code and not in this
table, something was added without checking it against the backend.

The map was written before the migration to list the disagreements between the
Vue frontend and the Kotlin backend. The migration is done, so it now lists the
agreement instead. The "Closed by" column keeps the history: it names the
sub-ticket that brought that call onto the Kotlin contract, or `—` when the call
already agreed and nothing had to change.

## How to read a row

| Column | Meaning |
| --- | --- |
| Frontend file | Where the literal lives. Line numbers are omitted on purpose — they rot; the path and the method are enough to find it. |
| Call | The method and path the frontend sends today. |
| Kotlin route | The route the Kotlin backend serves, from the HTTP table of `docs/dev/backend/<module>-package.md`. `same` means the path is identical to the call. |
| Closed by | The sub-ticket of issue #84 that migrated the call. `—` means the call needed no change. |

Every row of every table below is a match: same method, same path, same body and
response shape. There is no other status left. If you are looking for *what* was
wrong before, the per-module `docs/migration/<module>-post-migration.md` files
hold the item-by-item record; this file only proves that nothing is left over.

Two backend-wide facts are stated once here instead of in every row, because
they shaped almost every store:

- **Lists are bare JSON arrays.** The legacy backend wrapped collections in
  `{ "items": [...] }`. The Kotlin backend does not, anywhere. No store unwraps
  a `data.items` any more.
- **Reorder bodies are `{ "sourceId", "targetId" }`.** The shared
  `ReorderRequest` in `frontend/src/stores/admin/reorder.ts` is the only body any
  `PUT …/order` route sends, and the answer is the complete new order as a bare
  array.

## How this map is kept honest

1. `grep -rn "/api/" frontend/src`, ignoring the colocated `__tests__/`
   directories. Test files only repeat the literals of the code they test, so
   they add no rows.
2. Every hit is matched against the HTTP table of the owning backend package
   guide in `docs/dev/backend/`, and — where a guide leaves a body shape open —
   against the Ktor route file itself.
3. Hits inside comments and doc blocks are not rows. They name a route that is
   already a row somewhere in this file (`stores/shop/countries.ts` explaining
   why `GET /api/countries` needs no unwrap, `views/auth/LoginView.vue`
   explaining why the login status is the discriminator, and a handful more).
   The check is that each of them points at an existing row, not that each gets
   its own.

That currently yields **143 rows** and **10** Kotlin routes that no
frontend file calls, each dispositioned at the bottom of this file. A row is not
always a literal: where a URL is built once in a helper, the helper's notable
consumers get rows of their own, because what a reader looks for is the screen,
not the string. The t-shirt work (#205) is what moved the count: the storefront
gained `GET /api/articles/tshirts`, the admin surface for the eight admin
t-shirt routes landed with #220, the same ticket built the destination admin UI
that five of the formerly uncalled routes were waiting for, and the SPOD webhook
joined the routes that must stay uncalled.

`frontend/src/lib/api.ts` is the only place that calls `fetch`. Every row below
goes through `fetchJson` or `fetchForm`; the raw-`fetch` bypassers the migration
started with are gone. `docs/dev/frontend/frontend-api-conventions.md` explains
that client and the conventions the stores follow.

## Storefront: catalog reads

| Frontend file | Call | Kotlin route | Closed by |
| --- | --- | --- | --- |
| `stores/shop/articleCategories.ts` | `GET /api/articles/categories` | same | #217 |
| `stores/shop/catalog.ts` | `GET /api/articles/mugs` | same | #88 |
| `stores/shop/catalog.ts` | `GET /api/articles/tshirts` | same | #217 |
| `stores/shop/prompts.ts` | `GET /api/prompts?categoryId={id}` | same | #88 |

The categories route was the clearest rename of the migration: the legacy answer
was a map from article type to category list, so the store did
`allCategories['MUG']`. The Kotlin route answers a bare array of categories with
their subcategories nested, and the article type never appears
(`docs/dev/backend/article-package.md`, "The storefront"). With the t-shirt the
path lost its type as well: `GET /api/articles/mugs/categories` is gone and
`GET /api/articles/categories` answers the navigation over *every* visible
article, because one menu leads to mugs and shirts alike.

`stores/shop/catalog.ts` reads both article routes in parallel and merges them
into one discriminated union over `articleType` (`ShopArticle = MugDto |
TshirtDto`), which is why two rows point at the same store file. That merged
list is what the combined listing page `/products` renders — one grid for both
types, narrowed by its `category`, `subcategory`, and optional `type` query,
since #217 — and what the wizard's article step picks from. The optional
`categoryId` filter on `GET /api/prompts` was adopted with it — the prompt store
asks the backend for one category instead of filtering a full list in the
browser.

## Storefront: cart

| Frontend file | Call | Kotlin route | Closed by |
| --- | --- | --- | --- |
| `stores/shop/cart.ts` | `GET /api/cart` | same | #91 |
| `stores/shop/cart.ts` | `POST /api/cart/images` (multipart, part `file`) | same | #91 |
| `stores/shop/cart.ts` | `POST /api/cart/items` (JSON, `imageId`) | same | #91 |
| `stores/shop/cart.ts` | `POST /api/cart/order-items/{orderItemId}` | same | #91 |
| `stores/shop/cart.ts` | `PATCH /api/cart/items/{itemId}` | same | #91 |
| `stores/shop/cart.ts` | `DELETE /api/cart/items/{itemId}` | same | #91 |
| `stores/shop/cart.ts` | `POST /api/cart/promotion` | same | #91 |
| `stores/shop/cart.ts` | `DELETE /api/cart/promotion` | same | #91 |

Adding a line is two requests, not one. The Kotlin cart mints the print image
first (`POST /api/cart/images` → `201 {"id": 42}`) and only then accepts a JSON
line that names `imageId` (`docs/dev/backend/cart-package.md`). The legacy
frontend sent both halves in a single multipart request.

Every mutation answers the complete recalculated `CartView`, so the store holds
exactly one cart type and re-applies it after each call. The line fields are the
backend's: `imageId`, `price`, `promptPrice`, and the `available` flag.

A line also carries `articleType` (`MUG | TSHIRT | null`), which is what the
client renders it by: a mug falls back to a circle of its two colour codes, a
t-shirt to the mockup of its variant — and, while no mockup exists, to a
silhouette tinted with the `colorHex` the catalog store answers, since a shirt
line has no colour codes of its own. It is `null` for exactly the lines whose
names are `null`, the ones the catalog no longer resolves (issue #205).

Nothing here pins how a guest cart becomes a user cart. The store refetches after
an identity change and adopts whatever the backend answers.

## Storefront: checkout, orders, countries, coins, generation

| Frontend file | Call | Kotlin route | Closed by |
| --- | --- | --- | --- |
| `stores/shop/checkout.ts` | `POST /api/checkout` | same | #93 |
| `stores/shop/checkout.ts` | `POST /api/checkout/orders/{orderId}/payment` | same | #93 |
| `stores/shop/checkout.ts` | `GET /api/orders/{orderId}` | same | #93 |
| `stores/shop/orders.ts` | `GET /api/orders` | same | #94 |
| `stores/shop/orders.ts` | `GET /api/orders/{orderId}` | same | #94 |
| `stores/shop/orders.ts` | `GET /api/order-lookup/{token}` | same | #116 |
| `stores/shop/countries.ts` | `GET /api/countries` | same | #92 |
| `stores/shop/magicCoins.ts` | `GET /api/magic-coins/balance` | same | — |
| `stores/shop/imageGeneration.ts` | `POST /api/generator/generate` (multipart) | same | #95 |

`/api/checkout` owns exactly two routes — the submit and the payment retry. The
order **reads** belong to the order module under `/api/orders`, which is why two
stores call the same detail route: the checkout store reads it as a payment
status snapshot on the confirmation page, the orders store reads it as the order
history's detail (`docs/dev/backend/checkout-package.md`,
`order-package.md`).

`/api/order-lookup/{token}` is the third order read and the only one that needs
no session at all. It answers the same `Order` shape for whoever holds the access
token from the confirmation mail, and every miss — unknown, malformed, foreign —
is the same `404 {"message":"Order not found"}`. The `/order/{token}` page
(`views/shop/OrderLinkView.vue`) reads it exactly once and never polls (issue
#110).

An **order** line carries `articleType` too, but as a snapshot that is never
`null`: it still says what kind of thing was bought after the article has been
renamed, retyped, or deleted, which is why the order surfaces read it from the
line and only resolve the article through the catalog store when they open the
editor for a reorder or a redesign (issue #205).

`POST /api/checkout` has one field error the storefront localizes itself, next to
the unshippable country: a cart containing a t-shirt without a phone number is a
`400` keyed by the **nested** path `shippingAddress.phone` — not a bare `phone` —
and it carries no `code` either. The checkout form therefore makes the phone
field required as soon as `cartStore.hasTshirtItem` is true, and maps that path
onto the same inline message (`docs/dev/backend/checkout-package.md`).

Status strings are uppercase on the wire and the TypeScript unions repeat them
verbatim: `OrderStatus` is `PENDING | PAID | CANCELLED`, `OrderPaymentStatus` is
`OPEN | PENDING | AUTHORIZED | PAID | FAILED | CANCELED | EXPIRED` plus `null`.
The payment word has **one** L, the order word **two**; they are different facts
from different systems. Nothing lowercases a status any more, and i18n maps from
the wire value.

`POST /api/generator/generate` is a multipart request with exactly three parts:
`image`, `promptId`, and `articleId`. The article is not decoration — the route
reads the type behind the id and generates in the format that type prints in — so
`stores/shop/imageGeneration.ts` takes it as a required argument and the wizard
passes the article it selected two steps earlier (issue #205).

It refuses in six ways the UI has to tell apart, and
only the first of them carries a machine-readable `code`:

| Refusal | Answer | How the client reads it |
| --- | --- | --- |
| Out of Magic Coins | `402` with `code: INSUFFICIENT_MAGIC_COINS` | the `code`; the store refetches the balance |
| The generator's own image bound — over 10 MiB, or not JPEG/PNG/WebP | `400 Validation failed` with a field error on `image` | `errorStatus` plus an `image` key in `errorFieldErrors` |
| Unknown or unavailable prompt | `404 Prompt not found` | falls through to the generic message; the UI only offers prompts it just listed |
| Unknown article (the multipart `articleId` the image is generated for, issue #205) | `404 Article not found` | falls through to the generic message; the UI only offers articles it just listed |
| A *missing* or non-numeric `promptId` / `articleId` part | `400 Validation failed` with a field error on `promptId` or `articleId` | `errorFieldErrors`; it is a bug in the caller, never something a customer can produce |
| Per-IP rate limit / application-wide request size | `429` with `Retry-After` / `413` | `errorStatus` and `errorRetryAfterSeconds` |

`429` and `413` deliberately carry no code (decision 3 of issue #84), so the store
records `errorStatus`, `errorRetryAfterSeconds`, and `errorFieldErrors` next to
`errorCode`, and `useGenerationErrorMessage()` picks the localized message from
them.

The `400` matters because it is the *common* size refusal: the generator caps a
single image at 10 MiB (`GenerationUpload.kt`) while the `413` only fires at the
application-wide 30 MB, so every image between the two arrives as a `400`. Its two
causes — too large, wrong type — differ only in the English text of the field
error, so the client maps both onto one message rather than matching on that text.

## Auth and session

| Frontend file | Call | Kotlin route | Closed by |
| --- | --- | --- | --- |
| `lib/api.ts` | `GET /api/antiforgery/token` | same | — |
| `stores/shared/auth.ts` | `GET /api/auth/me` | same | #89 |
| `stores/shared/auth.ts` | `POST /api/auth/login` | same | #89 |
| `stores/shared/auth.ts` | `POST /api/auth/logout` | same | #89 |
| `stores/shared/auth.ts` | `POST /api/auth/register` | same | #89 |
| `stores/shared/auth.ts` | `POST /api/auth/confirm-email` | same, `400` + `code: INVALID_LINK` | #89 |
| `stores/shared/auth.ts` | `POST /api/auth/resend-confirmation` | same | #89 |
| `stores/shared/auth.ts` | `POST /api/auth/forgot-password` | same | #89 |
| `stores/shared/auth.ts` | `POST /api/auth/reset-password` | same, `400` + `code: INVALID_LINK` | #89 |
| `stores/shared/auth.ts` | `POST /api/auth/confirm-change-email` | same, `400` + `code: INVALID_LINK` | #89 |
| `stores/shared/auth.ts` | `PUT /api/auth/profile` | same | #89 |
| `stores/shared/auth.ts` | `POST /api/auth/change-email` | same | #89 |
| `stores/shared/auth.ts` | `POST /api/auth/change-password` | same | #89 |

Every auth path and every request body already agreed with the Kotlin backend —
this is the one module the migration deliberately kept on its legacy paths
(`docs/dev/backend/account-package.md`). The break was on the way back: the
legacy backend answered a `{ success, message, code }` envelope, the Kotlin
backend answers `204 No Content` on success and the shared `ApiError` shape on
failure. `postAuth` therefore treats the status as the answer and never parses an
empty body.

The status is the discriminator on most auth routes: `401` bad credentials,
`403` unconfirmed address, `429` lockout, `502` a mail that could not be
delivered. The three link flows — `confirm-email`, `reset-password`,
`confirm-change-email` — are the exception: an invalid or expired link answers
`400` with the machine-readable `"code": "INVALID_LINK"`, which is what lets the
views tell that case apart from an input validation `400` and show link-specific
localized copy instead of the backend's English message. No other `/api/auth`
route carries a code.

`POST /api/auth/reset-password` has **two** pages in front of it, which is why it
still needs only one row. `views/auth/ResetPasswordView.vue` serves the link of a
password reset the user asked for; `views/auth/SetPasswordView.vue` serves the
`/set-password` link of a supplier invitation nobody asked for (issue #119). Same
`?email=&token=` query, same call, same `INVALID_LINK` handling — only the copy
differs, because "you requested a new password" would be wrong in an invitation.

## Images

These literals are mostly not requests — they are `<img src>` URLs built from a
filename or an id. All of them match the image module's routes
(`docs/dev/backend/image-package.md`); the folder names under
`/api/images/public/` are the ones the backend writes into.

| Frontend file | Built URL | Kotlin route | Closed by |
| --- | --- | --- | --- |
| `lib/variantExampleImage.ts` | `/api/images/public/{size}/articles/{mugs\|tshirts}/variant-example-images/{filename}` | `GET /api/images/public/{size}/{filename...}` | #97, #217 |
| `lib/promptExampleImage.ts` | `/api/images/public/{size}/prompt-example-images/{filename}` | same | #99 |
| `components/shop/HeaderCategoryMenuPanel.vue` | `/api/images/public/400/articles/subcategory-example-images/{filename}` | same | — |
| `components/admin/article/subcategory/AdminArticleSubcategoryDialog.vue` | `/api/images/public/400/articles/subcategory-example-images/{filename}` | same | #96 |
| `lib/variantExampleImage.ts` (`sizeChartImageUrl`) | `/api/images/public/{size}/articles/tshirts/size-charts/{filename}` | same | #218 |
| `components/shop/wizard/steps/SelectArticleStep.vue` | `/api/images/public/400/articles/{type}/variant-example-images/{filename}` and `/api/images/public/1000/articles/tshirts/size-charts/{filename}` (via the two helpers in `lib/variantExampleImage.ts`) | same | #218 |
| `components/shop/editor/ProductEditor.vue` | `/api/images/public/1000/articles/tshirts/variant-example-images/{filename}` (shirt mockup backdrop, via `lib/variantExampleImage.ts`) | same | #218 |
| `components/shop/editor/ProductContextBar.vue` | `/api/images/public/200/articles/{type}/variant-example-images/{filename}` (via `lib/variantExampleImage.ts`) | same | #217 |
| `components/shop/CartLineItem.vue` | `/api/images/public/400/articles/{type}/variant-example-images/{filename}` (via `lib/variantExampleImage.ts`) | `GET /api/images/public/{size}/{filename...}` | — |
| `components/shop/ProductCard.vue` | `/api/images/public/{size}/articles/{type}/variant-example-images/{filename}` (the `/products` grid card, via `lib/variantExampleImage.ts`) | same | #217 |
| `components/admin/article/AdminArticleRow.vue` | `/api/images/public/{size}/articles/{type}/variant-example-images/{filename}` (via the same helper) | same | #220 |
| `components/admin/article/AdminArticleMugVariantDialog.vue` | `/api/images/public/200/articles/mugs/variant-example-images/{filename}` | same | #97 |
| `components/admin/article/AdminArticleTshirtVariantMatrix.vue` | `/api/images/public/200/articles/tshirts/variant-example-images/{filename}` | same | #220 |
| `views/admin/MugArticleEditView.vue` | `/api/images/public/200/articles/mugs/variant-example-images/{filename}` | same | #97 |
| `views/admin/TshirtArticleEditView.vue` | `/api/images/public/1000/articles/tshirts/variant-example-images/{filename}` and `/api/images/public/400/articles/tshirts/size-charts/{filename}` | same | #220 |
| `components/shop/CartLineItem.vue` | `/api/images/guest/400/{imageId}` | `GET /api/images/guest/{size}/{id}` | #91 |
| `components/shop/orders/OrderDetails.vue` | `/api/images/guest/320/{imageId}` | same | #94 |
| `stores/shop/printImages.ts` | `GET /api/images/guest/1600/{imageId}` (blob download) | same | #94 |

`stores/shop/printImages.ts` is the only *image* row that is a real request: it
downloads the print image as a blob, through `fetchJson(..., { responseType:
'blob' })` like every other call. It lives in a store rather than in
`views/shop/OrderView.vue`, which is its only caller, because the `404` means
something in domain terms — the print image is gone — and that knowledge belongs
next to the call as a named `PrintImageGoneError`, not in a view.

The id a cart or order line carries is `imageId` on both sides now; the frontend
invents no `generatedEditedImageId` any more.

## Admin: reference data

| Frontend file | Call | Kotlin route | Closed by |
| --- | --- | --- | --- |
| `stores/admin/suppliers.ts` | `GET /api/admin/suppliers` | same | #87 |
| `stores/admin/suppliers.ts` | `GET /api/admin/suppliers/{id}` | same | #87 |
| `stores/admin/suppliers.ts` | `POST /api/admin/suppliers` | same | #87 |
| `stores/admin/suppliers.ts` | `PUT /api/admin/suppliers/{id}` | same | #87 |
| `stores/admin/suppliers.ts` | `DELETE /api/admin/suppliers/{id}` | same | #87 |
| `stores/admin/vat.ts` | `GET /api/admin/vat` | same | — |
| `stores/admin/vat.ts` | `GET /api/admin/vat/{id}` | same | — |
| `stores/admin/vat.ts` | `POST /api/admin/vat` | same | — |
| `stores/admin/vat.ts` | `PUT /api/admin/vat/{id}` | same | — |
| `stores/admin/vat.ts` | `DELETE /api/admin/vat/{id}` | same | — |
| `stores/admin/promotions.ts` | `GET /api/admin/promotions` | same | #87 |
| `stores/admin/promotions.ts` | `GET /api/admin/promotions/{id}` | same | #87 |
| `stores/admin/promotions.ts` | `POST /api/admin/promotions` | same | #87 |
| `stores/admin/promotions.ts` | `PUT /api/admin/promotions/{id}` | same | #87 |
| `stores/admin/promotions.ts` | `DELETE /api/admin/promotions/{id}` | same | #87 |
| `stores/admin/prices.ts` | `GET /api/admin/prices/default` | same | #87 |
| `stores/admin/prices.ts` | `POST /api/admin/prices/calculate` | same | #87 |
| `composables/useAdminCountries.ts` | `GET /api/admin/countries` | same | #87 |

The VAT store was the only admin surface that already read a bare array, which is
why its rows carry no ticket. It is listed anyway: "already correct" is a result
this file has to show.

The promotion shape is **asymmetric** and the store models both directions
separately: a request sends flat `discountType` and `discountValue`, a response
groups them under a nested `discount` object, and validation error keys stay flat
(`docs/dev/backend/promotion-package.md`).

## Admin: article categories and subcategories

| Frontend file | Call | Kotlin route | Closed by |
| --- | --- | --- | --- |
| `stores/admin/articleCategories.ts` | `GET /api/admin/articles/categories` | same | #96 |
| `stores/admin/articleCategories.ts` | `GET /api/admin/articles/categories/{id}` | same | #96 |
| `stores/admin/articleCategories.ts` | `POST /api/admin/articles/categories` | same | #96 |
| `stores/admin/articleCategories.ts` | `PUT /api/admin/articles/categories/{id}` | same | #96 |
| `stores/admin/articleCategories.ts` | `DELETE /api/admin/articles/categories/{id}` | same | #96 |
| `stores/admin/articleCategories.ts` | `PUT /api/admin/articles/categories/order` | same | #96 |
| `stores/admin/articleSubcategories.ts` | `GET /api/admin/articles/subcategories` | same | #96 |
| `stores/admin/articleSubcategories.ts` | `GET /api/admin/articles/subcategories/{id}` | same | #96 |
| `stores/admin/articleSubcategories.ts` | `POST /api/admin/articles/subcategories/example-images` (multipart) | same | #96 |
| `stores/admin/articleSubcategories.ts` | `POST /api/admin/articles/subcategories` (JSON) | same | #96 |
| `stores/admin/articleSubcategories.ts` | `PUT /api/admin/articles/subcategories/{id}` (JSON) | same | #96 |
| `stores/admin/articleSubcategories.ts` | `DELETE /api/admin/articles/subcategories/{id}` | same | #96 |
| `stores/admin/articleSubcategories.ts` | `PUT /api/admin/articles/subcategories/order` | same | #96 |

Subcategory writes are plain JSON with an `exampleImageFilename`. The file is
uploaded first, to `…/subcategories/example-images`, which answers the stored
name; the write then names it. The legacy `FormData` with a `removeExampleImage`
flag is gone — removing the image is `exampleImageFilename: null`.

## Admin: mugs and t-shirts

| Frontend file | Call | Kotlin route | Closed by |
| --- | --- | --- | --- |
| `stores/admin/articles.ts` | `GET /api/admin/articles/mugs` | same | #97 |
| `stores/admin/articles.ts` | `GET /api/admin/articles/mugs/{id}` | same | #97 |
| `stores/admin/articles.ts` | `POST /api/admin/articles/mugs` | same | #97 |
| `stores/admin/articles.ts` | `PUT /api/admin/articles/mugs/{id}` | same | #97 |
| `stores/admin/articles.ts` | `DELETE /api/admin/articles/mugs/{id}` | same | #97 |
| `stores/admin/articles.ts` | `PUT /api/admin/articles/mugs/order` | same | #97 |
| `stores/admin/articles.ts` | `POST /api/admin/articles/mugs/variant-example-images` (multipart) | same | #97 |
| `stores/admin/articles.ts` | `GET /api/admin/articles/tshirts` | same | #220 |
| `stores/admin/articles.ts` | `GET /api/admin/articles/tshirts/{id}` | same | #220 |
| `stores/admin/articles.ts` | `POST /api/admin/articles/tshirts` | same | #220 |
| `stores/admin/articles.ts` | `PUT /api/admin/articles/tshirts/{id}` | same | #220 |
| `stores/admin/articles.ts` | `DELETE /api/admin/articles/tshirts/{id}` | same | #220 |
| `stores/admin/articles.ts` | `PUT /api/admin/articles/tshirts/order` | same | #220 |
| `stores/admin/articles.ts` | `POST /api/admin/articles/tshirts/variant-example-images` (multipart) | same | #220 |
| `stores/admin/articles.ts` | `POST /api/admin/articles/tshirts/size-charts` (multipart) | same | #220 |

The whole mug admin family sits one segment lower than it did. The legacy backend
had one `article` resource with an `articleType` discriminator in the body; the
Kotlin backend has a route family **per type**, and `articleType` exists in
neither direction. `priceId` is gone too — a mug embeds its calculated `price`
(`docs/dev/backend/article-package.md`).

The t-shirt family (#220) is that same shape a second time, which is why both
belong to one store file: fifteen rows — seven for the mug, eight for the
shirt, which has the size-chart pre-upload on top — and the type is the path.
Because `articleType` is on neither wire, the store stamps it onto everything it
returns — that tag is what makes `AdminArticleDto` a discriminated union and what
lets the overview show a Type column at all. The overview is **two** requests: a
list route is per type, so `fetchArticles()` reads both and merges them, grouping
by type before position — positions count per type, so a mug and a shirt share
every position number, and `PUT …/order` moves an article only within its own
type.

What the shirt body carries that the mug body does not is the article's print
geometry: `printAspectRatio` (`16:9` or `1:1`, defaulted to the square chest
print when omitted), the nested `printFrame` of four percentages, and
`sizeChartImageFilename`. A shirt has **two** pre-uploads instead of one, because
a variant photo and a size chart are stored in two different folders and a name
from one is not a name in the other. There is no `supplierArticleName` and no
`supplierArticleNumber` on a shirt: it is ordered from the print-on-demand
partner by the three `spod*` ids of its variant.

## Admin: prompt categories and subcategories

| Frontend file | Call | Kotlin route | Closed by |
| --- | --- | --- | --- |
| `stores/admin/promptCategories.ts` | `GET /api/admin/prompts/categories` | same | #98 |
| `stores/admin/promptCategories.ts` | `GET /api/admin/prompts/categories/{id}` | same | #98 |
| `stores/admin/promptCategories.ts` | `POST /api/admin/prompts/categories` | same | #98 |
| `stores/admin/promptCategories.ts` | `PUT /api/admin/prompts/categories/{id}` | same | #98 |
| `stores/admin/promptCategories.ts` | `DELETE /api/admin/prompts/categories/{id}` | same | #98 |
| `stores/admin/promptCategories.ts` | `PUT /api/admin/prompts/categories/order` | same | #98 |
| `stores/admin/promptCategories.ts` | `GET /api/admin/prompts/subcategories` | same | #98 |
| `stores/admin/promptCategories.ts` | `GET /api/admin/prompts/subcategories/{id}` | same | #98 |
| `stores/admin/promptCategories.ts` | `POST /api/admin/prompts/subcategories` | same | #98 |
| `stores/admin/promptCategories.ts` | `PUT /api/admin/prompts/subcategories/{id}` | same | #98 |
| `stores/admin/promptCategories.ts` | `DELETE /api/admin/prompts/subcategories/{id}` | same | #98 |
| `stores/admin/promptCategories.ts` | `PUT /api/admin/prompts/subcategories/order` | same | #98 |

The store holds `AdminPromptSubcategoryDto` with a flat `categoryId` and resolves
the display name through `categoryName(id)` from the category list it already
holds — the Kotlin representation carries no nested category object. The
subcategory reorder answer covers only the affected category, so the store merges
it per category and leaves the other categories untouched.

## Admin: prompt slots and slot variants

| Frontend file | Call | Kotlin route | Closed by |
| --- | --- | --- | --- |
| `stores/admin/promptSlots.ts` | `GET /api/admin/prompts/slots` | same | #98 |
| `stores/admin/promptSlots.ts` | `GET /api/admin/prompts/slots/{id}` | same | #98 |
| `stores/admin/promptSlots.ts` | `POST /api/admin/prompts/slots` | same | #98 |
| `stores/admin/promptSlots.ts` | `PUT /api/admin/prompts/slots/{id}` | same | #98 |
| `stores/admin/promptSlots.ts` | `DELETE /api/admin/prompts/slots/{id}` | same | #98 |
| `stores/admin/promptSlots.ts` | `GET /api/admin/prompts/slot-variants` | same | #98 |
| `stores/admin/promptSlots.ts` | `GET /api/admin/prompts/slot-variants/{id}` | same | #98 |
| `stores/admin/promptSlots.ts` | `POST /api/admin/prompts/slot-variants` | same | #98 |
| `stores/admin/promptSlots.ts` | `PUT /api/admin/prompts/slot-variants/{id}` | same | #98 |
| `stores/admin/promptSlots.ts` | `DELETE /api/admin/prompts/slot-variants/{id}` | same | #98 |

"Slot type" was legacy vocabulary. The Kotlin module calls the thing a **slot**
and the route segment follows the name (`docs/dev/backend/prompt-package.md`), so
the store renamed every identifier with the entity. A slot variant carries flat
`slotId` and `slotName`, and its update body carries no `slotId` at all — a
variant cannot be moved to another slot. A slot that does not exist on a variant
create is a `400` field error on `slotId`, not a `404`.

## Admin: prompts

| Frontend file | Call | Kotlin route | Closed by |
| --- | --- | --- | --- |
| `stores/admin/prompts.ts` | `GET /api/admin/prompts` | same | #99 |
| `stores/admin/prompts.ts` | `GET /api/admin/prompts/{id}` | same | #99 |
| `stores/admin/prompts.ts` | `POST /api/admin/prompts` | same | #99 |
| `stores/admin/prompts.ts` | `PUT /api/admin/prompts/{id}` | same | #99 |
| `stores/admin/prompts.ts` | `POST /api/admin/prompts/example-images` (multipart) | same | #99 |
| `stores/admin/prompts.ts` | `PUT /api/admin/prompts/order` | same | #99 |

Every path in this group was already right; the payload was not. List row and
detail are **flat** (`categoryId`/`categoryName` instead of nested objects),
`priceId` is replaced by an embedded calculated `price`, and `position` is
response-only. Note also what is *not* in the table: there is no delete route for
a prompt. A prompt is retired with the `archived` flag.

Two discriminators changed with the payload: a rejected price is recognised by
field errors under `price.*` rather than by the vanished code
`invalid_price_request`, and no prompt write answers `409` at all — only the
reorder does.

## Admin: order production documents

| Frontend file | Call | Kotlin route | Closed by |
| --- | --- | --- | --- |
| `stores/admin/orders.ts` | `GET /api/admin/orders/{orderId}/production-pdfs` | same | #100 |
| `stores/admin/orders.ts` | `GET /api/admin/orders/{orderId}/production-pdfs/{supplierId}` | same | #100 |

Joe's decision 2 of issue #84 kept this surface narrow on purpose: an order-ID
input, the list of one document per supplier, and a download. There is no order
search, no order table, and no status editing. Both routes generate on demand, so
a document that was listed a moment ago can still fail with one of the `409` data
codes on download.

## Admin: production jobs and supplier logins

| Frontend file | Call | Kotlin route | Closed by |
| --- | --- | --- | --- |
| `stores/admin/fulfillment.ts` | `GET /api/admin/production/jobs?status=OPEN\|SHIPPED&supplierId=` | same | #125 |
| `stores/admin/fulfillment.ts` | `GET /api/admin/production/jobs/{jobId}/pdf` | same | #125 |
| `stores/admin/fulfillment.ts` | `POST /api/admin/production/jobs/{jobId}/ship` | same | #125 |
| `stores/admin/supplierLogins.ts` | `POST /api/admin/supplier-logins` | same | #125 |
| `stores/admin/supplierLogins.ts` | `GET /api/admin/supplier-logins?supplierId=` | same | #125 |
| `stores/admin/supplierLogins.ts` | `DELETE /api/admin/supplier-logins/{userId}` | same | #125 |
| `stores/admin/productionDestinations.ts` | `GET /api/admin/production/destinations` | same | #220 |
| `stores/admin/productionDestinations.ts` | `GET /api/admin/production/destinations/{id}` | same | #220 |
| `stores/admin/productionDestinations.ts` | `POST /api/admin/production/destinations` | same | #220 |
| `stores/admin/productionDestinations.ts` | `PUT /api/admin/production/destinations/{id}` | same | #220 |
| `stores/admin/productionDestinations.ts` | `DELETE /api/admin/production/destinations/{id}` | same | #220 |

The admin side of issue #119. The job routes are the supplier ones with the scope
turned into a *filter*: `supplierId` is left out entirely when the Logistics page
shows every supplier, because a present but unusable id answers `400` — which is
the right answer for a typo and the wrong one for "no filter". The answers carry
two fields the supplier's own view does not (`supplier`, and the generation state
`generationAttemptCount`/`lastGenerationErrorCode`), so the ship and download
error mappings of `lib/fulfillment.ts` are imported rather than copied: the
routes refuse for exactly the same reasons. That module holds everything the two
ship surfaces share — the wire types, the carrier list, the error mapping and the
wording helpers — because the dialog they share lives in `components/shared/` and
may not depend on either area's store.

The destination routes are the five that were dispositioned as "no admin UI" until
the t-shirt work needed one (#205): a SPOD destination is how a shirt order
reaches the print-on-demand partner, so an operator has to be able to enter and
rotate that account. Their bodies are **asymmetric in one direction only**: a
request carries the secret of its channel — the SFTP password, the SPOD access
token — and no response ever carries either one back, which is why the store's
response types have no field for them at all. Which detail block belongs to a
body is decided by `channel`, and every violation of that rule, including the
second enabled SPOD destination of one supplier, comes back as a field error on
`channel`. The `409` belongs to the delete alone and means the destination is
still referenced; disabling it is the way out, not a retry.

The admin job rows grew with the same feature: `fulfillmentChannel` is what makes
a missing PDF readable — an SFTP job without one is late, a SPOD job without one
is normal — and `externalReference`, `remoteState`, `shippedByChannel` and
`shippingCarrierReported` are the partner's order id, its last reported state,
and who reported the shipment with which carrier name.

The supplier-login routes are the one place where a `502` is **not** a failure to
undo: the login was written, only its invitation mail did not go out. There is no
resend endpoint and re-posting the address answers `409`, so the dialog says so
and names the two ways out — "Forgot password" by the invited person, or delete
and create again. `DELETE` is the revocation itself and takes effect on the
login's next request.

## Supplier: production jobs

| Frontend file | Call | Kotlin route | Closed by |
| --- | --- | --- | --- |
| `stores/supplier/jobs.ts` | `GET /api/supplier/me` | same | #124 |
| `stores/supplier/jobs.ts` | `GET /api/supplier/production-jobs?status=OPEN\|SHIPPED` | same | #124 |
| `stores/supplier/jobs.ts` | `GET /api/supplier/production-jobs/{jobId}/pdf` | same | #124 |
| `stores/supplier/jobs.ts` | `POST /api/supplier/production-jobs/{jobId}/ship` | same | #124 |

The supplier surface of issue #119. Its scope is not a query parameter: the
backend resolves the caller's supplier from the account on every request, so
none of these calls says *which* supplier it is asking about. The list answers a
bare array, the ship route needs a JSON body even when the supplier states
nothing (`{}`), and a job of another supplier answers the same `404` as one that
never existed.

## Backend routes with no frontend caller

The map would not be a completeness proof without the other direction. These
Kotlin routes exist and nothing in `frontend/src` calls them. Each one is a
decision, not an oversight.

| Kotlin route | Disposition |
| --- | --- |
| `POST /api/admin/prices`, `GET /api/admin/prices/{id}`, `PUT /api/admin/prices/{id}` | **No caller by design.** A price belongs to its article or prompt and is written inside that write; the standalone endpoints are the development-phase addition described in `docs/dev/backend/pricing-package.md`. |
| `GET /api/admin/countries/{id}`, `POST /api/admin/countries`, `PUT /api/admin/countries/{id}`, `DELETE /api/admin/countries/{id}` | **Out of scope.** The frontend only needs the admin *list*, for dropdowns. A country admin UI is not part of #84, and the `countries` activation flag is a backend follow-up in `docs/migration/all-post-migration.md`. |
| `GET /api/images/private/{size}/{filename...}` | **No caller.** Private images are reached through the guest resolver route instead. Nothing to build. |
| `POST /api/payments/webhook/{secret}` | **Must stay uncalled.** Mollie calls this, never a browser. |
| `POST /api/production/webhooks/spod/{secret}` | **Must stay uncalled.** The print-on-demand partner calls this, never a browser; the secret in the path is its whole authentication (`docs/dev/backend/spod-fulfillment.md`). |

## Score

| | Count |
| --- | --- |
| Frontend rows, all matching | 143 |
| Backend routes with no caller, all dispositioned | 10 |
| Call sites with an open contract gap | 0 |

The closing sweep (issue #101) re-ran the grep of "How this map is kept honest"
against the finished code and found no literal without a row and no row without a
route. The supplier fulfillment feature (issue #119) re-ran it again after adding
its ten rows — the four supplier calls and the six admin ones — with the same
result. The t-shirt admin surface (#220) added thirteen more — the eight admin
t-shirt routes and the five destination ones — and took the same five off the
uncalled list. Keep it that way: a new `/api/…` literal belongs in this file in
the same commit that introduces it.
