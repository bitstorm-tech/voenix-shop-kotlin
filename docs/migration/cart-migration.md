# Cart module migration

This record follows [`migration-base.md`](migration-base.md); the rules live in
[`module-migration-guide.md`](module-migration-guide.md). The plan was decided
by the migration council (orchestrator, Opus, Codex) on 2026-07-29; Joe decided
the contested points the same day (see decision log).

## Status

`complete`

Phase 1 (council brainstorming, rebuttals, Joe's decisions) is complete and
recorded here. Phase 2 is complete as well: the six sub-tickets T1–T5 plus T2b
are implemented on the `cart-migration` branch. The phase-3 council
verification has passed, the post-migration simplification review has run
(2026-07-30) and its three findings are applied, the retrospective below is
complete, and `./kotlin check` is green.

What `complete` does **not** mean: PR #44 is still open and waits for Joe, and
the Vue frontend has not been adapted to the approved deviations. The items this
record handed to the Order migration — the reorder endpoint, the order claims,
and the original-image read path — were delivered on 2026-07-31; the items it
handed to Checkout — the `CHECKED_OUT` write path and the pre-payment promotion
re-check — were delivered on 2026-08-02. What is still deferred is the MagicCoins
guest-balance claim in
[`all-post-migration.md`](all-post-migration.md).

## Task parameters

Target module:

`cart`

Source feature:

`../voenix-shop/backend/Voenix.Api/Features/Cart` plus
`Features/Auth/Services/GuestDataClaimService.cs` (deferred to this migration
by the Account migration), `Features/Image/Services/GuestImageService.cs` and
the guest route in `Features/Image/Controllers/ImageController.cs`, and the
Cart/Promotion mappings in `ErrorHandling/DomainExceptionHandler.cs`.

Target package:

`backend/modules/cart/src/shop/voenix/cart`

Analysis checkpoint:

`wait-for-approval` — satisfied: Joe approved the council plan on 2026-07-29.

Known consumers:

- Vue frontend: `../voenix-shop/frontend/src/stores/shop/cart.ts` calls every
  `/api/cart/*` endpoint; `CartView.vue`, `CartItemPreviewDialog.vue`,
  `OrderDetails.vue`, `OrderView.vue` load `/api/images/guest/{size}/{id}`.
  The frontend must be adapted for the approved deviations (pre-upload,
  removed fields, error format); tracked as deferred work.
- `order` module (migrated 2026-07-31): `order_items.print_image_id`
  references the print-image entity this migration creates, and the PDF
  pipeline reads the originals through `PrivateImageStorage.originalPaths`.
  The cart consumes the order module in return, for the reorder route.
- `checkout` module (migrated 2026-08-02): reads the active cart and writes
  `status = 'CHECKED_OUT'`, both through the `CheckoutCarts` capability this
  module exports.
- `account` module: calls the guest-data claim after login and registration.

Approved deviations from current behavior (Joe, 2026-07-29, as one package):

1. Pre-upload instead of the multipart add-to-cart: `POST /api/cart/images`
   (multipart) stores the print image and returns its id;
   `POST /api/cart/items` is pure JSON with `imageId`.
2. GIF is rejected; uploads accept PNG/JPEG/WebP and are normalized to WebP by
   the shared image pipeline (resolves the "material contradiction" row in
   [`image-post-migration.md`](image-post-migration.md) in line with Joe's
   one-pipeline decision of 2026-07-27). No `content_type` column.
   Image-pipeline limits (10 MiB compressed, 40 MP decoded) apply.
3. `originalPrice` and `promptOriginalPrice` are dropped (wire and columns) —
   legacy always writes them equal to the snapshot price and nothing reads
   them.
4. `customData` is dropped entirely (column, input, response, merge key) — no
   legacy path ever writes anything but `{}`, no consumer reads it, and as
   customer-controlled JSON on an anonymous endpoint it has no specifiable
   validation rule. A future personalization feature introduces a named,
   typed value instead.
5. Purchasability uses `CatalogVariant.purchasable` (article active + variant
   active + price exists) — stricter than legacy, which ignored the article's
   active flag.
6. Read paths set no guest cookie: `GET /api/cart` and the guest-image route
   use `GuestTokens.tryGet`; the cookie is created by the first mutation.
   Legacy's cookie-on-read is an implementation artifact (`TryGetGuestToken`
   existed unused).
7. The guest-data claim is best effort: a claim failure is logged and never
   fails login or registration; it is retried on the next login.
   `CancellationException` is still rethrown.
8. PostgreSQL enforces "one active cart per guest token" via a partial unique
   index; `guest_session_token` becomes `NOT NULL`. Legacy has this race
   unprotected.
9. Promotion-code failures use the shared `ApiError` with a new optional
   `code` field (stable `PROMOTION_*` codes, statuses as legacy) instead of
   ASP.NET ProblemDetails.
10. CSRF protection is extended to anonymous cart mutations through a
    platform-owned guest-capable CSRF route protection (legacy protects cart
    routes via antiforgery; the current platform check requires a
    `UserPrincipal` and would reject or bypass anonymous callers).
11. Reorder (`POST /api/cart/order-items/{id}`) is deferred to the Order
    migration (reads the `orders` table, which does not exist; the guide
    forbids placeholder tables).
12. Status values are `ACTIVE`/`CHECKED_OUT` with a CHECK constraint (legacy:
    lowercase, unconstrained). Percentage discounts round `HALF_UP` to whole
    cents (equivalent to legacy `AwayFromZero` for non-negative amounts).
13. The image registry table is named `print_images` (column
    `print_image_id`) instead of `generated_edited_images` — the image is
    neither always generated nor always edited; it is the print image of a
    cart line. Add "Druckbild / print image" to the glossary in `CONTEXT.md`.
14. ~~Cart adoption on login keeps legacy semantics: the guest cart's `user_id`
    is set, no merge with an existing user cart (duplicates accepted as in
    legacy).~~ **Superseded on 2026-08-04 by issue #77** (Joe's decision). The
    guest token is no longer the identity of a cart: a signed-in request finds
    and creates its cart by `user_id`, the token identifies anonymous carts
    only, and a cart never carries both. The reason is the guest-token rotation
    on login decided in the same issue — with the token as the only identity,
    the rotation would orphan the cart the login had just claimed. The claim
    therefore became claim-**or**-merge: the visitor's lines are merged into the
    cart the customer already had (same variant + same print image ⇒ quantities
    add up, capped at 99; the customer's coupon wins, an empty one adopts the
    visitor's), and the emptied guest cart is retired with the new status
    `MERGED`. "At most one active cart per user" is a partial unique index, not
    a read. See `V19__revise_cart_identity.sql` and the
    [cart package guide](../dev/backend/cart-package.md#who-a-cart-belongs-to).

Explicitly deferred work:

- Reorder endpoint and order claims (by guest token and by case-insensitive
  e-mail match). Owner: Order migration — **delivered 2026-07-31** (see
  [`order-migration.md`](order-migration.md)). The route stayed in `cart`:
  `POST /api/cart/order-items/{orderItemId}` consumes the order module's
  exported `OrderItemReader`, and the new line is an ordinary add-to-cart —
  quantity 1, today's catalog price — so no second write path exists. A line
  whose print image cannot be printed any more answers `409` with
  `ORDER_IMAGE_UNAVAILABLE`, the code deviation 11 had let lapse. The order
  claims live in the order module and are bound next to the cart claim.
- Original-image read path for order PDFs. Owner: Order migration —
  **delivered 2026-07-31** as `PrivateImageStorage.originalPaths` in the image
  module (see [`image-post-migration.md`](image-post-migration.md)).
- `CHECKED_OUT` write path and pre-payment promotion re-check. Owner: Checkout
  migration — **delivered 2026-08-02** as the `CheckoutCarts` capability
  (`activeCart`, `markCheckedOut`) and the promotion module's `reserve` (see
  [`checkout-migration.md`](checkout-migration.md) and
  [`promotion-post-migration.md`](promotion-post-migration.md)).
- MagicCoins guest-balance claim: deliberately deferred until a rule exists
  for "guest and user balance both exist" (add vs. discard, abuse
  protection). Recorded in [`all-post-migration.md`](all-post-migration.md);
  owner: Joe.
- Orphaned print images (successful pre-upload never referenced by an add):
  accepted like Article/Prompt orphans; a sweep is a separate future feature
  in the same category as the deferred public-image sweep
  ([`article-post-migration.md`](article-post-migration.md)).
- Frontend adaptation (pre-upload flow, removed `originalPrice`/`customData`
  and color-fallback handling, `ApiError` + `code` reading). Owner: Joe /
  frontend follow-up. This includes the antiforgery token: after a successful
  login, registration, or logout the client must re-fetch
  `/api/antiforgery/token` before its next cart mutation. A token minted while
  the caller was anonymous stops validating once the caller is signed in (and
  the other way round) — that is CSRF token rotation across the authentication
  boundary and therefore intended. The council decided explicitly not to change
  the backend for it.

## Analysis deliverable

### 1. Behavior matrix

Source references are relative to `../voenix-shop/backend/Voenix.Api`.
`CartService` = `Features/Cart/Services/CartService.cs`, `CartController` =
`Features/Cart/Controllers/CartController.cs`, `Tests` =
`Voenix.Api.Tests/Features/Cart/CartServiceTests.cs`.

| Behavior | Evidence | Classification | Kotlin approach | Verification |
| --- | --- | --- | --- | --- |
| One active cart per guest token; find-or-create on mutation | `CartService:257-291` | Required (race fixed, deviation 8) | Partial unique index + `INSERT … ON CONFLICT DO NOTHING` + re-select in one transaction | Concurrency integration test: two parallel adds, one cart |
| Authenticated calls adopt an anonymous cart (`user_id` set when null) | `CartService:271-272,205-206` | Required | Same update inside the mutation transaction | Integration test |
| Add merges identical lines (article, variant, price snapshot, image, prompt, prompt price) and caps quantity at 99 | `CartService:305-324`; `Tests: AddToCartAsync_CapsQuantityAt99_WhenDuplicateWouldExceed` | Required (merge key loses `customData` per deviation 4) | Merge under `FOR UPDATE` on the cart row | Merge + cap integration tests |
| New lines get `position = max + 1`; response ordered by position | `CartService:326-349,362` | Required | Same; `UNIQUE (cart_id, position)` | Ordering test with fixtures whose order differs from id order |
| Article price is snapshotted at add time (gross sales cents) | `CartService:104-108,431-443` | Required | `ArticleCatalog.find` → `grossSalesPriceCents` stored on the line | Integration test: later price change does not alter the line |
| Prompt price is snapshotted; unknown/inactive/archived prompt rejects the add | `CartService:109-117,445-462`; `Tests: AddToCartAsync_SnapshotsPersistedPriceOfActiveNonArchivedPrompt` | Required | `PromptCatalog.findSalesGrossPriceCents`; absent id → invalid | Integration tests |
| Variant must belong to the article and be purchasable | `CartService:464-469` (variant active only) | Required, strictness is deviation 5 | `CatalogVariant.purchasable` plus composite FK `(variant_id, article_id)` | Rejected-add tests; FK schema test |
| Uploaded image or existing image id, never both | `CartService:52-53` | Obsolete under deviation 1 | Add takes only `imageId` | n/a |
| Image upload: PNG/JPEG/WebP(/GIF) accepted, stored under a generated name, row records guest token + optional user id; file deleted when the transaction fails | `CartService:68-99,131-146` | Required (GIF removed per deviation 2) | `POST /api/cart/images` via the image module's private storage; compensating file delete on a failed row insert | Upload + compensation integration tests |
| Image use requires ownership: stored guest token matches or authenticated user owns it | `CartService:471-481` | Required | Same predicate in the repository | Ownership matrix test |
| Quantity update 1–99; unknown item or cart → not found | `CartService:224-241`; `UpdateCartItemRequest` | Required | `PATCH /api/cart/items/{id}` with validated input | Route + service tests |
| Remove line; remove promotion; both return the recalculated cart | `CartService:214-255` | Required | Same | Integration tests |
| Apply promotion validates the code and stores only `promotion_id` (provisional; re-validated at checkout) | `CartService:195-211` | Required | `PromotionCodes.validate`; store id on success | Integration test |
| A rejected code does not replace an already applied promotion | `Tests: ApplyPromotionAsync_RejectsUnknownCodeWithoutReplacingAppliedPromotion` | Required | Reject before write | Integration test |
| Apply on a missing cart → not found (legacy `CartNotFoundException` → 404) | `CartService:201`; `DomainExceptionHandler` | Required | `OperationResult.NotFound` → 404 | Route test |
| Reservation counting by in-flight orders during validate | `PromotionApplicationService`; five `Tests: …InFlightOrder…` | Approved deviation (Joe, 2026-07-24) | Not ported; `validate` counts real redemptions only | Recorded here; tests deliberately not ported |
| Cart response: items with live article/variant names and colors, price snapshots, subtotal, shipping, discount, total, totalItems, applied promotion | `CartService:351-429`; `Dtos/*` | Required (shape changes are deviations 3, 4, 9) | One `CartView`; names/colors resolved live via `ArticleCatalog.find`; promotion via new `PromotionCodes.find` | Route + serialization tests |
| Unresolvable lines render with empty names | `CartService:367-373` | Proposed deviation → approved with package | `articleName`/`variantName` null plus `available: false` | Integration test with deactivated article |
| Shipping: 0 when subtotal ≤ 0 or ≥ 5000, else 490 cents | `Domain/CartTotalsCalculator.cs` | Required | Pure `CartTotals` | Unit test matrix |
| Discount: base subtotal+shipping, percentage capped at 100, rounded away from zero, capped at base; fixed value in cents | `CartTotalsCalculator.CalculateDiscountAmount`; `Tests: ApplyPromotionAsync_CapsPercentageDiscountAtOneHundredPercent` | Required | Same rules, `HALF_UP` (deviation 12) | Unit test matrix incl. rounding edges |
| Promotion error codes `PROMOTION_*` with 400/403/409 | `DomainExceptionHandler`; frontend `cart.ts` reads the code | Required | Route maps `PromotionCodeResult` → `ApiError(code=…)` per the table in section 2 | Route test per code |
| Cart mutations require antiforgery | `CartControllerTests: ApplyPromotion_RequiresAntiforgeryToken` (400) | Required (mechanism is deviation 10) | Platform guest-capable CSRF subtree; rejection before the operation runs | Route security tests |
| Guest token: encrypted `voenix.guest` cookie, HttpOnly, Lax, `/api`, 30 days | legacy `GuestTokenService`; platform `GuestTokens` | Required, already migrated | Reuse platform `GuestTokens`; add `tryGet` for read paths | Existing platform tests + new route tests |
| Guest image route `GET /api/images/guest/{size}/{id}`: owner sees the image, anything else → 404 (never 403) | `ImageController:33-42`; `GuestImageService`; `image-post-migration.md` | Required | Image owns the route and defines a resolver port; cart implements it; composed by the app after both installs | Ownership matrix route test |
| Claim on login/registration: carts and print images move to the user by guest token; orders by token and e-mail | `GuestDataClaimService.cs` | Required; order part deferred | `account` defines a `GuestDataClaims` port called best-effort after login and registration; cart implements carts + print images | Claim integration tests over login and registration |
| Reorder from an order item | `CartService:151-192` | Deferred (deviation 11) | Not implemented; `ORDER_IMAGE_UNAVAILABLE` code lapses with it | Recorded here; owner Order |
| Cart status `checked_out` written at checkout | `Domain/Cart.cs` | Required column, deferred write path | Column + CHECK now; write path delivered by the Checkout migration on 2026-08-02 (`CheckoutCarts.markCheckedOut`, idempotent `ACTIVE → CHECKED_OUT`) | Schema test; `CartCheckoutIntegrationTest` |
| Multiple `SaveChangesAsync` per operation | `CartService` throughout | Incidental (EF mechanics) | One transaction per mutation | Rollback integration test |

### 2. Operation contract table

All success responses except upload and image delivery are the full
recalculated `CartView` (status 200). `GET /api/cart` sets
`Cache-Control: no-store` (MagicCoins precedent). Mutations require the CSRF
header (guest-capable, deviation 10) and create the guest cookie via
`getOrCreate`; reads use `tryGet` and never set a cookie.

| Operation | Endpoint | Required input | Success | Required errors |
| --- | --- | --- | --- | --- |
| Read cart | `GET /api/cart` | guest cookie (optional) | 200 `CartView`; no cart → empty view (`id: null`, zeros) | 500 |
| Upload print image | `POST /api/cart/images` (multipart part `file`) | PNG/JPEG/WebP ≤ 10 MiB | 201 `{ "id": 42 }`; sets guest cookie | 400 missing part / undecodable / unsupported format / too large, all as `Validation failed` on the `file` field (Joe, 2026-07-30 — was `image`); 400 CSRF; 500 |
| Add line | `POST /api/cart/items` (JSON) | `articleId`, `variantId`, `quantity` 1–99, `promptId?`, `imageId?` | 200 `CartView` | 400 field rules; 400 not purchasable / prompt unusable / image not owned; 400 CSRF; 500 |
| Update quantity | `PATCH /api/cart/items/{itemId}` | `quantity` 1–99 | 200 `CartView` | 400; 404 no active cart or foreign line; 400 CSRF; 500 |
| Remove line | `DELETE /api/cart/items/{itemId}` | — | 200 `CartView` | 404; 400 CSRF; 500 |
| Apply promotion code | `POST /api/cart/promotion` | `promotionCode` (non-blank, ≤ 64) | 200 `CartView` | 400/403/409 with `code` (table below); 404 no active cart; 400 CSRF; 500 |
| Remove promotion | `DELETE /api/cart/promotion` | — | 200 `CartView` | 404; 400 CSRF; 500 |
| Deliver print image | `GET /api/images/guest/{size}/{id}` | guest cookie or session (optional) | 200 image (`Cache-Control: private`, ETag/Last-Modified) | 400 invalid size; 404 unknown or not owned; 500 |

Example add request (`POST /api/cart/items`):

```json
{ "articleId": 10, "variantId": 20, "quantity": 2, "promptId": 5, "imageId": 77 }
```

Example response (`GET /api/cart` and every cart mutation):

```json
{
  "id": 12,
  "items": [
    { "id": 34, "articleId": 10, "variantId": 20, "articleName": "Classic",
      "variantName": "Weiß", "outsideColorCode": "#ffffff",
      "insideColorCode": "#ff0000", "available": true, "price": 1490,
      "quantity": 2, "imageId": 77, "promptId": 5, "promptPrice": 500 }
  ],
  "subtotal": 3980,
  "shippingCost": 490,
  "discountAmount": 447,
  "total": 4023,
  "totalItems": 2,
  "appliedPromotion": { "id": 3, "name": "Sommer", "promotionCode": "SAVE10",
                        "discountType": "PERCENTAGE", "discountValue": 10 }
}
```

`appliedPromotion` is deliberately flat (`discountType`/`discountValue`) on
both sides — the nested-sealed-discount trap from the Promotion migration is
avoided by design. Request and response shapes for the pair are identical.

Promotion failure wire format — the contract this migration owes
[`promotion-post-migration.md`](promotion-post-migration.md):

```json
{ "message": "Promotion code has expired", "code": "PROMOTION_EXPIRED" }
```

| `PromotionCodeResult` | `code` | Status |
| --- | --- | --- |
| `InvalidCode` | `PROMOTION_INVALID_CODE` | 400 |
| `Inactive` | `PROMOTION_INACTIVE` | 400 |
| `NotStarted` | `PROMOTION_NOT_STARTED` | 400 |
| `Expired` | `PROMOTION_EXPIRED` | 400 |
| `LoginRequired` | `PROMOTION_LOGIN_REQUIRED` | 403 |
| `TotalExhausted` | `PROMOTION_TOTAL_EXHAUSTED` | 409 |
| `PerUserExhausted` | `PROMOTION_PER_USER_EXHAUSTED` | 409 |

### 3. Material ambiguities and proposed deviations

None open. All observable deviations were approved by Joe on 2026-07-29 as
the package listed under task parameters. The council's three contested
points (color codes, `customData`, route owner) were resolved in one rebuttal
round; the route owner was decided by Joe (image owns the route).

### 4. Kotlin operation interface and production type map

```kotlin
internal interface CartOperations {
    suspend fun cart(owner: CartOwner): OperationResult<CartView>
    suspend fun uploadPrintImage(owner: CartOwner, upload: UploadedImage): OperationResult<PrintImageId>
    suspend fun addItem(owner: CartOwner, input: AddCartItemInput): OperationResult<CartView>
    suspend fun updateQuantity(owner: CartOwner, itemId: Long, input: CartQuantityInput): OperationResult<CartView>
    suspend fun removeItem(owner: CartOwner, itemId: Long): OperationResult<CartView>
    suspend fun applyPromotion(owner: CartOwner, input: PromotionCodeInput): CartPromotionResult
    suspend fun removePromotion(owner: CartOwner): OperationResult<CartView>
}
```

| Type | Kind | Justification (deletion test) |
| --- | --- | --- |
| `CartModule` (+ `createCartModule`, `installCartModule`, `validateCartRequests`) | handle | required runtime-handle convention |
| `CartRoutes` | internal object | thin route → HTTP mapping incl. the `PROMOTION_*` table |
| `CartOperations` | internal interface | operation boundary |
| `CartService` | internal class | validation, orchestration, totals, catalog/prompt/promotion resolution |
| `CartRepository` | internal class | Exposed access, row lock, merge, positions, claim |
| `Carts`, `CartItems`, `PrintImages` | Exposed tables | schema ownership |
| `CartOwner` | internal data class | guest token + optional user id — the identity every operation works with |
| `CartView`, `CartLine`, `AppliedPromotion` | serializable | the one response aggregate; the wrapper carries required aggregates (totals) |
| `AddCartItemInput`, `CartQuantityInput`, `PromotionCodeInput` | serializable `Validatable` inputs | three distinct external contracts; quantity rules differ from add rules |
| `CartPromotionResult` | internal sealed | `Applied(CartView)` / `NoCart` / `Rejected(PromotionCodeResult)` / `UnexpectedFailure` — outcomes `OperationResult` cannot carry without misusing `Conflict` (LoginResult precedent) |
| `CartTotals` | internal object | pure shipping/discount calculator |
| `CartWriteResult` | internal sealed (only if needed) | expected persistence outcomes of add (conflict on concurrent create, FK failures); drop if the row-lock design leaves a single outcome |
| `CartGuestImages` | public class | implements the image module's guest-image resolver port |
| `CartGuestData` | public class | implements the account module's `GuestDataClaims` port |

15 production types — above the 12-type review signal, driven by the two
exported port implementations and the mandated three inputs; the simplification
review applies the deletion test to each, `CartWriteResult` first. The phase-3
fixes already removed one: `StoredPrintImage` failed the deletion test, because
the only thing its caller needed was the stored file name, so
`CartRepository.findPrintImage` now returns `String?`.

Neighbor-module changes (each verified against current code on 2026-07-29):

- `platform`: `GuestTokens.tryGet(call): String?`; a guest-capable CSRF route
  protection (current `AuthModule.hasValidCsrfToken` requires a
  `UserPrincipal` — `AuthModule.kt:137` — so anonymous mutations cannot be
  protected today); `ApiError` gains an optional `code: String?` that is
  omitted from serialization when null (existing error bodies unchanged).
- `image`: private print-image storage (store/exists/delete, WebP
  normalization via the existing codec) alongside `PublicImageStorage`; the
  guest delivery route `GET /api/images/guest/{size}/{id}` with a minimal
  path-free resolver port (id + caller → stored filename or null; cart never
  learns a filesystem path); the route must not sit inside an
  `authenticate` block (it must serve guests; the logged-in owner is read via
  the session, not `principal<UserPrincipal>()`); rename
  `ExampleImageUpload`/`receiveExampleImageUpload` to a neutral
  `UploadedImage`/`receiveUploadedImage` (name is fixed on "example" while
  cart becomes the third consumer).
- `article`: `CatalogVariant` gains nullable `outsideColorCode`/
  `insideColorCode`; the KDoc gains the fourth question ("what does it look
  like — what a consumer needs to render a stored reference, even one no
  longer purchasable") as the explicit boundary against browsing copy.
- `promotion`: `PromotionCodes.find(promotionIds: Set<Long>):
  Map<Long, PromotionCodeResult.Applicable>` (set-in/map-out like every
  reader) so the cart can render its stored `promotion_id`.
- `account`: defines the `GuestDataClaims` port, calls it best-effort after
  successful login **and** registration with the guest token from `tryGet`;
  `RegisterResult.Registered` carries the new `userId` (internal change);
  no account→cart compilation dependency — the app composes the port.

Implementation note (T3, 2026-07-30): the two color codes are on
`CatalogVariant` as `String?`, but for mugs they can never actually be null.
`article_mug_variants.inside_color_code` and `outside_color_code` are
`NOT NULL` in `V13__create_articles.sql` (lines 226–227), and a mug variant row
is the only thing that carries them today. The ticket's acceptance criterion
"null colors where details are missing" is therefore unreachable for mugs: the
five layout measurements come from the *details* row, which may be absent, but
the colors come from the variant row itself. The fields are nullable anyway,
and deliberately so — a future article type without colors answers `null` here
instead of being forced to invent an empty string. `CatalogVariant`'s KDoc
records exactly that boundary, so the nullability reads as a decision and not
as an accident.

Implementation note (T5, 2026-07-30): the account half is implemented as
planned. The port is `GuestDataClaims.claim(userId, guestToken)` (a `fun
interface`, mirroring `GuestImageResolver`); the composition root binds it to
`CartGuestData` with a lambda, because the cart's own method takes its two
arguments the other way round. `installAccountModule` gained the two
capabilities `guestTokens` and `guestDataClaims` — like the cart's, they are
required parameters, so the only change to the existing account tests is their
composition line. The claim runs before the response is written and is proven
twice: by `AccountGuestClaimIntegrationTest` against a recording port (claim
after every successful login and registration, never without a guest cookie,
never after a rejected login, swallowed failure) and by the app composition
test end to end (guest uploads a print image and owns a cart → registers →
both rows carry the user id → repeated login stays idempotent → the signed-in
customer sees the cart). The order branch of the claim stays deferred to the
Order migration, which extends the bound implementation and not the port.

### 5. Runtime composition design

```kotlin
public fun Application.installCartModule(
    database: Database,
    articles: ArticleCatalog,
    prompts: PromptCatalog,
    promotions: PromotionCodes,
    printImageStorage: PrivateImageStorage,
    guestTokens: GuestTokens,
): CartModule
```

Implementation note (T4, 2026-07-30): `guestTokens` was added to the planned
signature. The routes must read and issue the `voenix.guest` cookie and
`AuthModule` is platform-internal, so the capability has to be passed in; the
app shares the single `GuestTokens` instance it already builds.

- The handle is `public` because the composition root needs the exported
  capabilities (`CartGuestImages` for the image guest route, `CartGuestData`
  for the account claim); factory and internals stay `internal`. An
  `internal` `installCartModule(operations)` overload is the route test seam.
- App composition order: image (storage capabilities) → article/prompt/
  promotion → cart → image guest route (`installGuestImageRoute(resolver)`)
  → account (with the claim port bound to `CartGuestData`). The three
  previously discarded capabilities (`ArticleCatalog`, `PromptCatalog`,
  `PromotionCodes`) are bound for the first time — the "the composition root
  discards this" sentences in `module-architecture.md`,
  `article-package.md`, `prompt-package.md`, and `promotion-package.md`
  become false and must be updated (the Article migration left five such
  passages stale; do not repeat that).
- A composition integration test (Email precedent) proves the wiring.

### 6. Application composition and Flyway changes

Flyway `V15__create_carts.sql` (platform migration folder, like V13/V14):

- `print_images`: identity id, `filename varchar(64)` unique (UUID with
  dashes + `.webp`), `guest_session_token text NULL`,
  `user_id bigint NULL` FK → `users` `ON DELETE SET NULL`, `created_at`;
  CHECK owner present (`guest_session_token IS NOT NULL OR user_id IS NOT
  NULL`); indexes on token and user.
- `carts`: identity id, `guest_session_token text NOT NULL`,
  `user_id bigint NULL` FK → `users` `ON DELETE SET NULL`,
  `status text NOT NULL` CHECK (`ACTIVE`/`CHECKED_OUT`),
  `promotion_id bigint NULL` FK → `promotions` `ON DELETE SET NULL`
  (**required**: `PromotionRepository.delete` maps SQL state 23503
  wholesale to `Redeemed` — `PromotionRepository.kt:159` — a second
  restricting FK would corrupt that mapping), timestamps; partial unique
  index on `(guest_session_token) WHERE status = 'ACTIVE'`; index on
  `user_id`.
- `cart_items`: identity id, `cart_id` FK → `carts` `ON DELETE CASCADE`,
  composite FK `(variant_id, article_id)` →
  `article_variant_identities (id, article_id)` `ON DELETE CASCADE`
  (verified: `ak_article_variant_identities_id_article_id UNIQUE (id,
  article_id)` exists in `V13__create_articles.sql`), `quantity integer`
  CHECK 1–99, `price_cents integer` CHECK ≥ 0, `prompt_id bigint NULL` FK →
  `prompts` `ON DELETE SET NULL` (price snapshot survives),
  `prompt_price_cents integer` CHECK ≥ 0 default 0, `print_image_id bigint
  NULL` FK → `print_images` `ON DELETE RESTRICT`, `position integer` CHECK
  > 0, timestamps; `UNIQUE (cart_id, position)`; indexes on `cart_id` and
  `print_image_id`.

User deletion semantics are `SET NULL` on both `carts.user_id` and
`print_images.user_id`: the data reverts to guest-owned via the stored token
instead of cascading into cart lines that restrict on print images.

### 7. Test plan

| Test class | Level | Covers |
| --- | --- | --- |
| `CartInputValidationTest` | pure | complete field-rule matrix of the three inputs |
| `CartTotalsTest` | pure | shipping thresholds; percentage cap 100, `HALF_UP` rounding edges, cap at subtotal+shipping; fixed discounts; empty cart |
| `CartServiceIntegrationTest` | service + PostgreSQL | find-or-create incl. two-writer concurrency (one cart survives); adoption of anonymous carts; merge + 99 cap + position assignment under concurrent adds; price snapshots (article + prompt) unaffected by later changes; purchasability and prompt rejections; image-ownership matrix; claim over login and registration incl. idempotent retry and best-effort failure; rollback on failed writes; `CancellationException` rethrown; unexpected SQL failure → `UnexpectedFailure` |
| `CartRouteSecurityAndValidationTest` | route (fake operations) | CSRF rejection before operation invocation on every mutation; field-rule 400s via shared RequestValidation; reads never set a cookie; mutations set the guest cookie |
| `CartFlowIntegrationTest` | route + PostgreSQL | full journeys: upload → add → read → quantity → remove; promotion apply/remove incl. all seven `PROMOTION_*` codes and statuses; rejected code keeps the applied promotion; 404s; empty-cart shape |
| `GuestImageRouteIntegrationTest` | route + PostgreSQL (image + cart composed) | owner via guest token → 200; owner via session after claim → 200; foreign/unknown → 404; invalid size → 400; no `Set-Cookie` on the response; upload compensation on failed row insert |
| `CartSchemaIntegrationTest` | Flyway + PostgreSQL | migration on an empty database; each constraint violated by a seed that can only trip the rule under test (second active cart, quantity 100, foreign variant/article pair, position duplicate, owner-less print image, restricted image delete) |
| WebP/PDF proof test | production module | `ProductionPdfRenderer` renders a PDF from a WebP original (the registry stores only WebP after deviation 2); without this proof the Order migration inherits a blocker |
| Composition test | app | install order, guest route serves through the composed resolver, claim port bound, first-time binding of the three catalog capabilities |

### 8. Deferred work and its owner

See "Explicitly deferred work" under task parameters.

## Decision log

### 2026-07-29 — Council phase 1 (brainstorming and rebuttal)

Three independent proposals (orchestrator, Opus, Codex) from one identical
briefing. Consensus on module cut, pre-upload, GIF rejection, dead-field
removal, concurrency design, error wire format, and the platform CSRF gap
(found by Opus, confirmed by Codex). One rebuttal round resolved the three
contested points: color codes (Opus withdrew; `CatalogVariant` is extended
with the sharpened KDoc boundary), `customData` (Codex withdrew; dropped —
the anonymous-endpoint validation argument decided it), guest-image route
(positions swapped in rebuttal; decided by Joe below).

### 2026-07-29 — Joe's decisions

- Deviation package (1–12 plus 14) approved as a whole.
- Guest-image route: **image owns the route** under the legacy path;
  cart implements the minimal resolver port ("smallest interface" rule from
  `image-post-migration.md`).
- Table rename to `print_images` approved (deviation 13); glossary entry in
  `CONTEXT.md`.
- Claim scope: legacy adoption semantics, no cart merge; MagicCoins claim
  deferred with a durable entry (deviation 14 and deferred work).

## Deviation and uncertainty log

| Behavior or contract | Source evidence | Kotlin behavior | Classification | Approval or owner | Follow-up |
| --- | --- | --- | --- | --- | --- |
| Multipart add with inline file | `CartController:534-548` | Pre-upload endpoint + JSON add | Approved deviation | Joe, 2026-07-29 | Frontend adaptation |
| GIF accepted at upload | `CartService:25-33` | Rejected; PNG/JPEG/WebP normalized to WebP | Approved deviation | Joe, 2026-07-29 | Closes the image-post-migration contradiction row |
| `originalPrice`/`promptOriginalPrice` | `CartService:334-343` (always = snapshot) | Dropped | Approved deviation | Joe, 2026-07-29 | Frontend types |
| `customData` (column, wire, merge key) | `CartService:103,311`; only `{}` ever written | Dropped | Approved deviation | Joe, 2026-07-29 | A future personalization feature models a typed value |
| Variant check ignores article active flag | `CartService:464-469` | `CatalogVariant.purchasable` (stricter) | Approved deviation | Joe, 2026-07-29 | none |
| Guest cookie created on reads | `CartController:604`; `ImageController:37` | Reads use `tryGet`, no cookie | Approved deviation | Joe, 2026-07-29 | none |
| Claim failure fails login/registration | `AuthController:56-58,94-95` call order | Best effort: logged, retried next login | Approved deviation | Joe, 2026-07-29 | none |
| No uniqueness of the active cart | schema (no constraint) | Partial unique index; token `NOT NULL` | Approved deviation | Joe, 2026-07-29 | none |
| ProblemDetails promotion errors | `DomainExceptionHandler` | `ApiError` + optional `code` | Approved deviation | Joe, 2026-07-29 | Frontend reads `code` from the new shape |
| Cart antiforgery via ASP.NET convention | `MutationAntiforgeryConvention.cs` | Platform guest-capable CSRF subtree | Approved deviation | Joe, 2026-07-29 | Platform change with its own regression tests |
| Reorder endpoint | `CartService:151-192` | Not implemented | Approved deviation (deferred) | Joe, 2026-07-29 | Delivered by the Order migration on 2026-07-31: the route is `POST /api/cart/order-items/{orderItemId}` in `cart`, reading order data through the exported `OrderItemReader`; `ORDER_IMAGE_UNAVAILABLE` is back as the `409` code, and the new line carries quantity 1 at today's price |
| Status literals `active`/`checked_out`, no constraint | `Domain/Cart.cs` | `ACTIVE`/`CHECKED_OUT` + CHECK | Approved deviation | Joe, 2026-07-29 | Checkout writes the second value — delivered 2026-08-02 |
| `MidpointRounding.AwayFromZero` | `CartTotalsCalculator.cs` | `HALF_UP` (equivalent for non-negative) | Approved deviation | Joe, 2026-07-29 | none |
| Table name `generated_edited_images` | EF configuration | `print_images` / `print_image_id` | Approved deviation | Joe, 2026-07-29 | Glossary entry; `order_items.print_image_id` uses the new name since 2026-07-31 |
| Cart merge on login | `GuestDataClaimService` (no merge either) | Same: adopt, never merge; duplicates accepted | Required (matched) | Joe, 2026-07-29 | none |
| MagicCoins balances never claimed | legacy gap (account record) | Unchanged; decision deferred | Unclear → deferred | Joe (owner) | Entry in `all-post-migration.md` |
| Order claims (token + e-mail) | `GuestDataClaimService` | Not implemented here | Required, deferred | Order migration | Delivered 2026-07-31. The port itself changed after all — `claim(userId, guestToken: String?, email: String?)` — because an order is reachable by confirmed address alone; the branches are bound independently by the app-owned `IndependentGuestDataClaims` |
| `promotion_redemptions.order_id`, reservation counting, checkout window re-check | promotion record | Unchanged | Already decided | Order/Checkout | `order_id` delivered by the Order migration (`NOT NULL`, unique, `RESTRICT`) together with a transactional `redeem`; reservation counting and the window re-check delivered by the Checkout migration on 2026-08-02 as `promotion_reservations` plus `reserve`, which also made the cart's own `validate` count in-flight capacity again (deviation D5). See `promotion-post-migration.md` |
| WebP originals in order PDFs | `PdfService.cs` reads guest files | Proof test in this migration | Confirmed blocker (T2, 2026-07-30) → resolved by decision | Joe, 2026-07-30: LosslessFactory path (ticket T2b) | See "WebP production PDFs" below |
| Roadmap claims "Generator needs Cart" | `GeneratorController` returns bytes, persists no row | Refuted and closed on 2026-07-30 | Side finding → resolved | Generator migration | The migrated `generator` module is stateless: it answers raw bytes and registers no print image, so it never depended on Cart (decision log point 7 of [`generator-migration.md`](generator-migration.md)). A generation audit trail stays a possible later product feature, not print-image registration |

### WebP production PDFs — blocker resolved by decision (2026-07-30)

Ticket T2 ran the proof test the plan asked for
(`ProductionPdfWebpSourceTest` in the production module). The result:

- The `webp-imageio` reader **is** registered on the classpath, and ImageIO
  reads a WebP file without complaint.
- `ProductionPdfRenderer` still fails. It loads images through
  `PDImageXObject.createFromFileByContent`, and PDFBox 3.0.5 sniffs the file
  before it ever asks ImageIO: only JPEG, TIFF, BMP, GIF, and PNG are routed
  onward. A WebP file is a RIFF container, so PDFBox raises
  `IllegalArgumentException("Image type RIFF not supported: …")`, which the
  renderer maps to the retryable `ProductionPdfError.UNREADABLE_IMAGE`.

So the registry storing WebP only means that, as things stand today, **every
production PDF containing a print image would fail forever**. Registering a
different ImageIO reader cannot fix it; the rejection happens before ImageIO.

T2 deliberately built no workaround. The test pins the current behavior with
that documentation so the fact cannot be lost, and whoever fixes it inverts
its assertions. Candidate directions for the council (none chosen):

1. Transcode WebP to PNG inside the production renderer before handing bytes
   to PDFBox.
2. Bypass `createFromFileByContent`: read with ImageIO and use
   `LosslessFactory.createFromImage`, which is what PDFBox itself does for
   PNG.
3. Keep a second, PDF-friendly original per print image.

This is Order's blocker, but it is caused by the storage decision made here,
so the decision belongs to this migration.

**Decision (Joe, 2026-07-30, at the T2 stop point):** candidate 2. The
production renderer reads image files through ImageIO (the registered
`webp-imageio` reader handles WebP) and embeds them with
`LosslessFactory.createFromImage` instead of
`PDImageXObject.createFromFileByContent`. No second original, no separate
transcoding step. Implemented as ticket T2b (#43) in this phase;
`ProductionPdfWebpSourceTest` now proves a WebP original renders. Side
effect, accepted: JPEG originals are re-encoded losslessly (Flate) when
embedded instead of passed through as JPEG, so PDFs from JPEG sources grow —
irrelevant for the WebP-only print-image registry.

### Decided: the status code and shape for a rejected upload (Joe, 2026-07-30)

The phase-3 council could not settle this one and recorded it instead.
`UploadedImage.TooLarge` — the shared multipart reader's answer when one
uploaded part exceeds the 10 MiB limit — was mapped differently by its
consumers: `CartRoutes` answered `400` with a field-scoped `ApiError` (the
contract table approved in this record), while `PromptRoutes`,
`MugArticleRoutes`, and `ArticleSubcategoryRoutes` answered
`413 Payload Too Large`.

council-opus argued the three established routes should move to `400`: every
other rule of the same reader and the same pipeline already answers `400` with
a field-scoped `ApiError`, and the limit applies to one multipart part, not to
the request as a whole, which is what `413` is about. Codex argued the
established endpoints should keep `413` — it is the semantically specific
status, they are already tested against it, and the inconsistency only needs to
be documented.

**Joe decided `400` everywhere on 2026-07-30, with the fully unified shape.**
The reasoning that carried it was not council-opus's part-versus-request
argument, which is formally right but weak in practice — at these four
endpoints the `file` part *is* the request. It was:

1. A client cannot act differently on `413` than on `400`; it shows the
   customer what is wrong with the file they picked. `413` cost a second code
   path in the frontend for one of five rejection reasons of the same endpoint
   and bought nothing.
2. The byte limit is one rule of the image pipeline next to format, emptiness,
   decodability, and the pixel limit. That it surfaces earlier, because the
   reader aborts mid-stream, is an implementation property, not a reason for a
   different error class.
3. A genuine `413` belongs to a body limit enforced before any handler runs, by
   Ktor or a reverse proxy, where it never appears in module code at all.

Implemented as a shared `ApplicationCall.respondUploadRejection(message)` next
to the reader, so a fifth upload endpoint cannot drift. Two consequences beyond
the status code:

- **The field name is now the part name**, `FILE_PART_NAME` = `file`, one public
  constant used both by the reader and by every rejection. This changed Cart's
  approved wire contract from `image` to `file` (Joe accepted that explicitly)
  and also renamed the field in the storage's own rejections, which reported
  `image` while reading a part called `file`.
- **The three older routes' missing-part answer changed shape too**, from a bare
  message to the same field-scoped body. Otherwise the inconsistency would only
  have moved from the status code to the body.

The outcome is documented next to the reader in
[`image-package.md`](../dev/backend/image-package.md), and the affected
frontend-adaptation checkboxes in
[`article-post-migration.md`](article-post-migration.md) and
[`prompt-post-migration.md`](prompt-post-migration.md) now describe the new
shape.

### Unreproduced test flake in the production module (hypothesis, 2026-07-30)

During the phase-2 gate a single production-module test failed once and passed
on the immediate re-run. The orchestrator's full `./kotlin check` on this branch
was green and did **not** reproduce it. This is a hypothesis, not a diagnosis —
it is written down so the next occurrence is investigated instead of guessed at
again.

council-opus' hypothesis: this branch makes `production` a *second* JVM that
extracts and `System.load`s the JNI library of
`com.github.usefulness:webp-imageio`. Its `NativeLoader.cleanup()` lists
`java.io.tmpdir` and deletes extracted artifacts whose `.lck` companion file is
absent. Two test JVMs starting concurrently can therefore race: one may delete
the library the other has just extracted and not yet locked. Before this branch
only the `image` module's JVM ever touched that path, which is why the race
could not appear.

Cheap mitigation if it recurs: give each test JVM its own extraction directory
via `-Dcom.luciad.imageio.webp.tmpdir`. Do not apply it preventively — an
unreproduced flake does not justify a permanent workaround.

## Migration retrospective

Run on 2026-07-30, after the phase-3 council verification and — for the last
four rows, which were added the same day — after the post-migration
simplification review that the first pass had skipped. The analysis and the
decided design held: the module cut, the schema, the concurrency design, the
operation contract, and the seven routes are what phase 1 planned, and no
approved deviation had to be reopened. Everything below comes from what the
three reviews and the fixes actually turned up.

| Finding | Evidence | Scope | Earlier signal or check | Destination and action |
| --- | --- | --- | --- | --- |
| A fake that does not suspend where the real capability suspends makes a test pass that production fails. `FakeImageStorage.delete` returned without dispatching, so no test could ever have caught that the compensating delete never runs on a cancelled request — the bug was only provable after the fake was given the real `withContext(Dispatchers.IO)`. | `CartService.compensate`; `CartTestSupport.FakeImageStorage`; the new test `a cancellation between the stored file and its row still deletes the file`, which fails without `NonCancellable` | Reusable testing default | None. The test plan required "rollback integration test" and "`CancellationException` rethrown", and both existed — they just could not fail. | Guide, test section: **applied**. |
| Cleanup that compensates a *cancellation* must run `NonCancellable`. Every suspending step of the cleanup — the dispatch, a lock, a transaction — aborts before it does anything, so the one case the compensation exists for is the one case it never ran in. | `CartService.compensate`; found by the Codex review, initially judged correct by the Opus review and conceded in the rebuttal round | Reusable Kotlin default (data integrity) | None. The compensation was written and reviewed as correct three times before the mechanism was traced. | Guide, persistence/transaction section: **applied**. |
| An ordering assertion in id order slipped through although the guide already forbids it verbatim. The rule was added after the Article migration; Cart repeated the defect, and both independent reviews found it. | `CartServiceIntegrationTest`, old test `a line differing in its prompt stays a second line, ordered by position`; guide's test section | Process gap, not a missing rule — second independent occurrence | The rule existed. What was missing is that the plan's test-plan row said "ordering test" without naming the fixture shape, so nothing forced the implementer to build one that can fail. | Guide, planning step: **applied** — a test-plan row that promises an ordering proof must state the fixture shape. |
| The "stale sentences in other modules' docs" rule caught the package guides but not the open-work lists. `docs/dev` was updated correctly; four `*-post-migration.md` files and `promotion-package.md` still waited for Cart. | `prompt-post-migration.md` §4, `pricing-post-migration.md`, `promotion-post-migration.md` (two sections), `image-post-migration.md` | Second occurrence of the class the Article retrospective already produced a rule for | The Article rule named package guides only, so following it literally still left the post-migration files false. | Guide: **applied** — the existing bullet now covers post-migration files too. |
| `./kotlin check` inside a restricted sandbox can also fail fast with "Could not find a valid Docker environment" instead of hanging. Two full gate runs were lost to this before the cause was recognised. | Gate log: 351 occurrences, 14 failed test containers, while `docker ps` outside the sandbox worked | Always-on backend invariant | `backend/AGENTS.md` documented only the silent-hang symptom, so the fail-fast one read like a real test failure. | `backend/AGENTS.md`: **applied** — second symptom added. |
| The type-count review signal worked, but not where it pointed. The plan named `CartWriteResult` as the first deletion-test candidate; it survived all three reviews with a clear justification, while the type that failed the test was `StoredPrintImage`, which the plan never flagged. | Section 4 type map; phase-3 finding 5 | Module-specific | — | This record only. Apply the deletion test to the whole type list, not to the candidates the plan happens to suspect. |
| The simplification review was skipped and this retrospective was written in its place. Both the guide (step 4 before step 5) and `migrate-dotnet-feature` state the order; the `migration-council` skill did not, and its phase-3 bullet list went from the verification verdict straight to "**Retrospective**: after verification". | `.agents/skills/migration-council/SKILL.md`, phase-3 bullets before 2026-07-30; the review was run afterwards as a separate pass and found the three items below | Process gap in the council specialization | The canonical rule existed twice. What was missing is that the workflow actually followed — the council skill — never named the step, so nothing in phase 3 asked for it. | `migration-council` skill: **applied** — phase 3 now names the simplification review as its own bullet, before the retrospective, and says the verdict is not the end of the phase. |
| A transaction helper typed to one result class grows an inline copy for every operation that returns something else. `CartRepository.write` was `() -> CartWriteResult`, so `insertPrintImage` (returns `Long`) and `claimGuestData` (returns `Unit`) each re-wrote the same `withContext(Dispatchers.IO)` + `suspendTransaction(maxAttempts = 1)` block by hand — three wrappers for one policy. | `CartRepository` before 2026-07-30; making `write` generic in its return type removed both copies with no behavior change | Reusable persistence default (small) | The review's own transaction-wrapper check asks whether each wrapper enforces a named policy, and each of the three did — the check has no question for "the same policy, written three times". The cheap signal is the helper's signature: a transaction wrapper fixed to a result type is the smell, not the count. | Fixed in the code, and the guide's transaction-wrapper bullet now says that two wrappers enforcing the same policy are one wrapper: **both applied**. |
| Two smaller items from the same pass, both fixed: `setPromotionInTransaction` returned a literal `true` to satisfy the `(Long) -> Boolean` contract of `writeToExistingCart`, discarding the update's row count (now `> 0`); and `CartService` split its one-line `Invalid` helper into `invalid` plus a single-use `errors`, where the neighbouring `ImageService` writes the same thing as one function (now inlined). | `CartRepository.setPromotionInTransaction`, `CartService.invalid` | Module-specific | — | This record only. |
| The simplification review checks the new module, not the other consumers of the infrastructure it just joined. Cart carried no copied upload answer, so the check passed — while three sibling routes held the same rejection response as three copies, with a status and a field name that disagreed with Cart's. | `PromptRoutes`, `MugArticleRoutes`, `ArticleSubcategoryRoutes` before 2026-07-30: each built its own `413`/`400` pair inline; now one shared `respondUploadRejection` next to the reader | Reusable review default | The record's own "open decision" section had already named the disagreement, so it was known — but as a status-code question, not as duplicated code. Becoming the third consumer of shared infrastructure is the signal to look at the other two. | This record. Not promoted to the guide: one occurrence, and the guide's simplification step already says to search for copied shared setup — what it does not say is *where* to search, which is worth a rule only if it happens again. |
| `CartService.databaseOperation` and `promotionOperation` are the ninth module's copies of the shared database-failure wrapper, and the second and third *inside one file*. | Both are the same `try`/`catch(CancellationException)`/`catch(SQLException)`/log/fallback shape, differing only in the fallback value and the message | Already-open cross-module decision | Already tracked: the Promotion retrospective named "a seventh copy" as the trigger, Article was that copy, and the decision has been waiting in `all-post-migration.md` since 2026-07-28. | [`all-post-migration.md`](all-post-migration.md): **applied** — Cart added to the list of copies, with the note that it is the first module carrying two of them. Not changed in code: it is an architecture default under guide rule 4, and deduplicating it inside Cart alone would make one module differ from the other eight. |

No process finding was left pending for Joe. Two decisions were recorded for
him instead: the 400-vs-413 answer of the shared upload reader, which he decided
the same day in favour of `400` everywhere (see the decided section above, and
the retrospective row below), and the guest-token lifetime entry in
[`all-post-migration.md`](all-post-migration.md), which is still open. The
shared `databaseOperation` helper is a third open decision, but not a new one —
it has been on Joe's list in that same file since the Article migration.
