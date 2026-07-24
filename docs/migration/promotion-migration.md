# Promotion module migration

This record follows [`module-migration-guide.md`](module-migration-guide.md)
and was created from [`migration-base.md`](migration-base.md).

## Status

`approved-for-implementation` — implementation in progress; issue #9 (module
skeleton, schema, admin read path) and issue #10 (create with full
validation) are done.

## Task parameters

Target module:

`promotion`

Source feature:

`../voenix-shop/backend/Voenix.Api/Features/Promotion`, plus the redemption
creation and duplicated limit check in
`../voenix-shop/backend/Voenix.Api/Features/Order/Services/PaidOrderProcessor.cs`
(lines 40–78) and the promotion branch of
`../voenix-shop/backend/Voenix.Api/ErrorHandling/DomainExceptionHandler.cs`.

Target package:

`backend/modules/promotion/src/shop/voenix/promotion`

Analysis checkpoint:

`wait-for-approval`

Known consumers:

- No migrated Kotlin consumer yet. Future consumers per the roadmap: Cart
  (validate a code for the active cart), Checkout (validate under
  concurrency), Order remainder (record a redemption when an order is paid).
- The legacy frontend administers promotions under `/api/admin/promotions`;
  frontend adaptation is already an open cross-cutting task (see the account
  migration record).

Approved deviations from current behavior:

- (2026-07-24, Joe) Pending-order capacity reservation is not migrated. The
  legacy `PromotionApplicationService.ActiveReservationOrders` counts pending
  orders (younger than 15 minutes without payment, or with an
  open/pending/authorized/paid payment) against the usage limits by querying
  the Order and Payment tables. The Kotlin module counts only real
  redemptions. Reservation semantics are redesigned when Order/Checkout are
  migrated; see [`promotion-post-migration.md`](promotion-post-migration.md).
- (2026-07-24, Joe) The promotion module owns `promotion_redemptions` and
  exposes an atomic `redeem` operation on its runtime handle. The legacy
  duplication of the limit check in `PaidOrderProcessor` is not reproduced;
  Order/Checkout will call the module capability instead.
- (2026-07-24, Joe) The discount is modeled as a sealed Kotlin type
  (`Percentage` / `FixedAmount`) with the invariants in the type, not as the
  legacy enum-plus-decimal pair. The JSON contract keeps
  `discountType`/`discountValue`.
- (2026-07-24, Joe) The HTTP contract may deviate from the legacy shape where
  the Kotlin backend's conventions are better (paths, field names, error
  format). The frontend will be adapted anyway.

Explicitly deferred work:

- Reservation of promotion capacity by in-flight orders — owner: Order and
  Checkout migrations (see
  [`promotion-post-migration.md`](promotion-post-migration.md)).
- `order_id` column and FK on `promotion_redemptions` — the orders table does
  not exist yet; the column is added by the Order migration, which also wires
  `redeem` to real orders. Per the guide, no placeholder FK is created.
- Cart-side DTOs and endpoints (`ApplyCartPromotionRequest`,
  `AppliedPromotionDto`, cart routes) — owner: Cart migration.

## Analysis deliverable

### Behavior matrix

| Behavior | Evidence | Classification | Kotlin approach | Verification |
| --- | --- | --- | --- | --- |
| Admin CRUD under `/api/admin/promotions`, admin-only | `AdminPromotionController`, controller tests (`ListPromotions_RequiresAdminPolicy`, CSRF test) | Required | Route subtree under the shared admin auth policy | Route tests: success flows plus HTTP rejection before operation invocation |
| List ordered by name, then id | `PromotionDtoQuery()` | Required | `ORDER BY name, id` in the repository | Repository/route test with ordering assertion |
| List/Get expose redemption count and locked state | `AdminPromotionDto` (`RedemptionCount`, `IsLocked`), service tests | Required | `Promotion` representation with `redemptionCount` and `isLocked` computed from redemptions | Route/repository test with seeded redemptions |
| List response is a wrapper object `{ items: [...] }` | `AdminPromotionListResponse` | Incidental | Direct `List<Promotion>` per guide default | Route test asserts direct array |
| Name: required, trimmed, max 255 | `PromotionRequestValidator`, `NormalizeRequiredText` | Required | Pure `validate()` on the input; trim in service normalization | Validator unit tests |
| Coupon code: required, trimmed, max 64 | Same | Required | Same | Validator unit tests |
| Coupon code uniqueness is case-insensitive; conflict → 409 | `coupon_code_normalized` + unique index, `CreateAsync_RejectsCaseInsensitiveDuplicateCouponCodes` | Required | Normalized column + unique index; `executePostgresWrite(uniqueViolation = Conflict)` | Integration tests: duplicate write and concurrent duplicate write |
| Discount: PERCENTAGE (0 < value ≤ 100) or FIXED_AMOUNT (positive whole cents) | Validator, `ValidateDiscountValue` | Required | Sealed `Discount` type; rules in `validate()` (single implementation, see decision log) | Validator unit tests, full field-rule matrix |
| Optional activity window; `startsAt ≤ endsAt` | Validator, `ValidateDateWindow` | Required | Same rule in `validate()` | Validator unit tests |
| Usage limits total/per-user: optional, positive | Validator | Required | Same rule in `validate()` | Validator unit tests |
| Update of a redeemed ("locked") promotion is rejected unless only `isActive` changes | `UpdateAsync` (`ConfigurationMatches`), tests (`UpdateAsync_RejectsRedeemedPromotion`, `AllowsActiveStateChange`) | Required | Full update runs `UPDATE … WHERE id = ? AND NOT EXISTS (redemption)`; 0 rows with existing promotion → locked conflict. `isActive`-only change always allowed | Integration tests: locked reject, isActive toggle on locked, unlocked full update |
| Delete of a redeemed promotion is rejected | `DeleteAsync`, FK `ON DELETE RESTRICT`, tests | Required | Delete relies on the FK restrict; SQL state 23503 → conflict | Integration test with seeded redemption |
| Delete of an unredeemed promotion → 204, not found → 404 | `DeleteAsync`, controller tests | Required | Row count from delete; 0 → NotFound | Route/integration tests |
| Validation of a customer-entered code: trim + uppercase, then unknown / inactive / not started / expired / login required / total exhausted / per-user exhausted | `PromotionApplicationService.ValidateCoreAsync`, `PromotionUsageLimitGuard`, CartServiceTests | Required | `validate(code, userId)` capability on the module handle returning a typed failure reason | Service tests covering every failure and the success value |
| Login-required check precedes usage counting (guest with per-user-limited code sees LOGIN_REQUIRED, not TOTAL_EXHAUSTED) | `ValidateCoreAsync` order of checks; `ApplyPromotionAsync_PrioritizesGuestLoginHintOverTotalExhaustion` | Required | Same check order | Service test |
| Usage limit counting includes in-flight order reservations | `ActiveReservationOrders`, CartServiceTests reservation tests | Approved deviation | Not migrated; only real redemptions count | Recorded here and in the post-migration file |
| Checkout-time validation locks the promotion row (`FOR UPDATE`) and requires a transaction | `ValidateForCheckoutAsync` | Approved deviation (moved) | Not exposed as a separate operation; the atomic `redeem` operation locks the row and re-checks limits in one transaction | `redeem` concurrency integration test |
| Redemption is recorded when an order is paid, with promotion id, user id, timestamp; limits re-checked under `FOR UPDATE` | `PaidOrderProcessor` lines 40–78, `PaidOrderProcessorTests` | Required (relocated) | `redeem(promotionId, userId)` on the module handle: lock row, count redemptions, insert or fail with the exhausted reason | Integration tests incl. two concurrent redeems against a limit of 1 |
| Redemption failure codes map to stable API error codes (`PROMOTION_*`) and HTTP 400/403/409 | `DomainExceptionHandler`, `PromotionApplicationException.Code` | Required in substance, shape free | Typed failure reason serialized by the future consumer; exact wire shape decided by the consuming module (no HTTP surface for `validate`/`redeem` yet) | Type-level exhaustiveness; consumer decides wire format later |
| SQLite-specific constraint detection | `IsSqliteConstraintViolation` | Incidental (test-infrastructure of the legacy repo) | Not migrated; Testcontainers PostgreSQL | n/a |
| Constraint names surfaced in problem details (`ConstraintAwareProblem`) | `DomainExceptionHandler.PromotionProblem` | Incidental, contradicts backend rules | Not migrated; generic conflict result | Persistence-error tests |
| `redemption.user_id` is nullable (guest orders can redeem total-limited promotions) | Schema, `PaidOrderProcessor` | Required | Nullable `user_id` column | Redeem test with null user |

### Operation contract (admin HTTP)

| Operation | Required input | Required success value | Required errors | Ordering |
| --- | --- | --- | --- | --- |
| List `GET /api/admin/promotions` | none | direct array of promotion representations | — | name, then id |
| Get `GET /api/admin/promotions/{id}` | id | promotion representation | 404 | n/a |
| Create `POST /api/admin/promotions` | input | created representation (201) | 400 invalid, 409 code conflict | n/a |
| Update `PUT /api/admin/promotions/{id}` | id + input | updated representation | 400 invalid, 404, 409 code conflict, 409 locked | n/a |
| Delete `DELETE /api/admin/promotions/{id}` | id | 204 no body | 404, 409 locked | n/a |

Evidence: `AdminPromotionController` + controller tests for each row. The
direct list array replaces the legacy `{ items: [...] }` wrapper (approved
free-contract decision; same deviation Supplier made).

Module capabilities for future consumers (no HTTP surface in this migration):

| Capability | Input | Success | Failures |
| --- | --- | --- | --- |
| `validate` | raw code, optional user id | applicable promotion (id, name, code, discount) | InvalidCode, Inactive, NotStarted, Expired, LoginRequired, TotalExhausted, PerUserExhausted |
| `redeem` | promotion id, optional user id | recorded redemption | LoginRequired, TotalExhausted, PerUserExhausted, promotion missing |

### Material ambiguities

None blocking. The reservation question was the one material ambiguity and
was decided by Joe on 2026-07-24 (not migrated, deferred to Order/Checkout).

### Kotlin type map (planned production files)

Package `backend/modules/promotion/src/shop/voenix/promotion`, one top-level
type per file, following the supplier/pricing shape:

| File | Type | Visibility | Justification |
| --- | --- | --- | --- |
| `PromotionModule.kt` | `PromotionModule` + `createPromotionModule`, `installPromotionModule` (internal stub seam and public prod seam), `validatePromotionRequests` | internal handle, public install | Required runtime-handle convention |
| `PromotionRoutes.kt` | `PromotionRoutes` | internal | Admin CRUD routes |
| `PromotionOperations.kt` | `PromotionOperations` | internal | Route/service seam and route-test stub, as in supplier |
| `PromotionService.kt` | `PromotionService` | internal | Validation re-check, normalization, result mapping |
| `PromotionRepository.kt` | `PromotionRepository` | internal | Exposed queries, `executePostgresWrite`, `FOR UPDATE` redeem transaction |
| `Promotions.kt` | `Promotions` table | internal | Exposed table |
| `PromotionRedemptions.kt` | `PromotionRedemptions` table | internal | Exposed table (no `order_id` yet, see deviation log) |
| `PromotionInput.kt` | `PromotionInput : Validatable` | internal | Shared create/update input; identical fields and rules in the source |
| `Promotion.kt` | `Promotion` | public | Single representation for list/get/create/update responses, incl. `redemptionCount` and `isLocked`; serializes the discount as `discountType` + `discountValue` |
| `Discount.kt` | sealed `Discount` (`Percentage`, `FixedAmount`) | public | Approved domain model; invariants live in the type |
| `PromotionCodes.kt` | `PromotionCodes` capability interface (`validate`, `redeem`) | public | Exported capability for Cart/Checkout/Order |
| `PromotionCodeResult.kt` | sealed `PromotionCodeResult` (`Applicable(id, name, code, discount)` + the seven failure reasons) | public | Typed replacement for `PromotionApplicationFailure`/exceptions; shared by `validate` and `redeem` |
| `PromotionWriteResult.kt` | internal sealed (`Stored`, `NotFound`, `CodeConflict`, `Locked`) | internal | One write produces several meaningful persistence outcomes |

13 types is above the 12-type review signal; the excess comes from the second
table and the exported capability with its typed result, each of which passes
the deletion test. Deliberately not created: a `StoredPromotion` projection
(the representation is the projection), a redemption domain class (`redeem`
returns success/failure; nothing reads redemption rows yet), a list wrapper,
a module result type, and a delete result (row count + FK restrict suffice).

No admin HTTP surface exists for `validate`/`redeem`; they are
capability-only until Cart/Order consume them.

### Runtime composition

- `PromotionModule` is `internal`; it owns the object graph and installs the
  routes.
- `createPromotionModule(database)` builds repository → service → module.
- `public fun Application.installPromotionModule(database): PromotionCodes`
  installs the module and returns the capability (pattern:
  `installCountryModule` returning `CountryReader`). The composition root
  does not bind the return value yet; Cart/Order migrations will.
- `internal fun Application.installPromotionModule(operations)` remains the
  route-test seam, as in supplier.
- `public fun RequestValidationConfig.validatePromotionRequests()` registers
  `PromotionInput`.

### Application composition and Flyway changes

- `backend/app/src/shop/voenix/Application.kt`: add
  `validatePromotionRequests()` to the `RequestValidation` block and
  `installPromotionModule(database)` to the installation sequence.
- New module `backend/modules/promotion/module.yaml` (platform dependency,
  ktor auth/core/request-validation, exposed, serialization) per the
  supplier template.
- Flyway `V12__create_promotions.sql` in
  `backend/modules/platform/resources/db/migration/`, schema `voenix`:
  - `promotions`: identity PK; `name varchar(255)`; `discount_type text`
    with `CHECK (discount_type IN ('PERCENTAGE','FIXED_AMOUNT'))`;
    `discount_value numeric(12,2)` with `CHECK (discount_value > 0)`;
    `coupon_code varchar(64)`; `coupon_code_normalized varchar(64)` with
    unique constraint `ux_promotions_coupon_code_normalized`;
    `starts_at`/`ends_at timestamptz NULL`; `usage_limit_total`/
    `usage_limit_per_user integer NULL` with positive checks;
    `is_active boolean`; index `ix_promotions_name`.
  - `promotion_redemptions`: identity PK; `promotion_id bigint` FK
    `ON DELETE RESTRICT`; `user_id bigint NULL` (guests may redeem);
    `redeemed_at timestamptz NOT NULL`; indexes on `promotion_id` and
    `(promotion_id, user_id)`. No `order_id` yet (deviation log).
- Kotlin time type: `Instant` mapped to `timestamptz`; money/percent input
  uses `BigDecimalJsonNumberSerializer` as in pricing.

### Test plan

| Test | Kind | Covers |
| --- | --- | --- |
| `PromotionInputValidationTest` | unit | Full field-rule matrix once: required fields, lengths, discount rules per type, date window, positive limits |
| `PromotionRouteSecurityAndValidationTest` | Ktor `testApplication` + stub operations | 401/403/CSRF rejection before operation invocation; 400 with field errors; 201 + Location; 204 delete |
| `PromotionAdminCrudIntegrationTest` | Testcontainers | List ordering (name, id); get/create/update/delete flows; redemption count and locked flag; locked update rejected; `isActive`-only update on locked promotion allowed; locked delete rejected via FK; not-found outcomes; duplicate coupon code (case-insensitive) on create and update; concurrent duplicate create |
| `PromotionCodesIntegrationTest` | Testcontainers | `validate`: success value, unknown/inactive/not-started/expired code, login-required precedes limit counting, total and per-user exhaustion; `redeem`: success incl. guest (null user), exhaustion failures, two concurrent redeems against `usage_limit_total = 1` produce exactly one redemption |
| Flyway on empty PostgreSQL | Testcontainers base | `migratedDataSource` migrates V1–V12 |

## Decision log

### 2026-07-24 — Brainstorming decisions (Joe)

- Reservation logic (pending orders reserve capacity for 15 minutes) is not
  migrated; only real redemptions count. Owner of the follow-up: Order and
  Checkout migrations.
- The promotion module owns the redemptions table and an atomic `redeem`
  operation; the limit check exists exactly once in Kotlin.
- Discount is a sealed type `Percentage`/`FixedAmount`; JSON keeps
  `discountType`/`discountValue`.
- The HTTP contract may deviate from legacy where the Kotlin conventions are
  better.

### 2026-07-24 — Analysis checkpoint

Analysis completed (behavior matrix, contract tables, type map, runtime
composition, Flyway plan, test plan) and presented to Joe. Implementation is
blocked until Joe approves this analysis. The analysis was published as the
spec in GitHub issue #8 (`ready-for-agent`); test seams (admin HTTP routes,
`PromotionCodes` capability, pure input validator) confirmed by Joe.

### 2026-07-24 — Discount value bounds (issue #10)

`PromotionInput.validate()` rejects fixed amounts above 9999999999 cents and
percentages with more than two decimal places. Both values pass the legacy
rules but cannot round-trip through the `numeric(12,2)` column: the first
overflows into an undeclared SQL state and therefore a `500`, the second is
silently rounded so the created representation would differ from the accepted
input. Rejecting them as field errors keeps the invariant "stored equals
accepted" and keeps every valid body away from a `500`. No legacy value that
round-trips losslessly is rejected. Approved by Joe on 2026-07-24.

## Deviation and uncertainty log

| Behavior or contract | Source evidence | Kotlin behavior | Classification | Approval or owner | Follow-up |
| --- | --- | --- | --- | --- | --- |
| In-flight orders reserve promotion capacity | `ActiveReservationOrders` + CartServiceTests | Only real redemptions count | Approved deviation | Joe, 2026-07-24 | Order/Checkout migrations; [`promotion-post-migration.md`](promotion-post-migration.md) |
| `promotion_redemptions.order_id` column + unique index + FK to orders | `EnforcePromotionRedemptionLimits` migration | Column deferred; no placeholder FK | Approved deviation (guide rule: no placeholder relationships) | Joe, 2026-07-24 | Order migration adds the column and passes the order id to `redeem` |
| Separate checkout validation operation (`ValidateForCheckoutAsync`) | `CheckoutService` | Folded into atomic `redeem`; checkout revalidation design belongs to the Checkout migration | Approved deviation | Joe, 2026-07-24 | Checkout migration validates against the module handle |
| List wrapper `{ items: [...] }` | `AdminPromotionListResponse` | Direct JSON array | Approved deviation (free contract) | Joe, 2026-07-24 | Frontend adaptation (already open) |
| Stable `PROMOTION_*` error codes on the wire | `DomainExceptionHandler` | Typed failure reasons; wire shape decided by the consuming module | Approved deviation (free contract) | Joe, 2026-07-24 | Cart migration defines the customer-facing error payload |
| Constraint names in problem details | `ConstraintAwareProblem` | Not exposed (backend persistence rules) | Incidental | n/a | none |
| Discount value precision and magnitude are unbounded (legacy accepts any positive decimal; `numeric(12,2)` silently rounds sub-cent percentages and errors on overflow) | `PromotionRequestValidator` | Fixed amounts above 9999999999 cents and percentages with more than two decimal places are rejected as field errors, so the stored value always equals the accepted value and the column can never overflow into a 500 | Approved deviation (stricter validation) | Joe, 2026-07-24 | none |

## Migration retrospective

Completed after implementation.

| Finding | Evidence | Scope | Earlier signal or check | Destination and action |
| --- | --- | --- | --- | --- |
| — | — | — | — | — |
