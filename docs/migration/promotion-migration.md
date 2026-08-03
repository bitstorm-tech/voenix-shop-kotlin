# Promotion module migration

This record follows [`module-migration-guide.md`](module-migration-guide.md)
and was created from [`migration-base.md`](migration-base.md).

## Status

`complete`

Implemented on 2026-07-24 to 2026-07-26 across the five issues of the spec
([issue #8](https://github.com/bitstorm-tech/voenix-shop-kotlin/issues/8)):
#9 (module skeleton, schema, admin read path), #10 (create with full
validation), #11 (update and delete with lock semantics), #12 (the
`PromotionCodes` capability, plus the corrected lock design), and #13 (docs,
roadmap, simplification review, retrospective). The simplification review and
the retrospective are recorded below. Deferred work lives in
[`promotion-post-migration.md`](promotion-post-migration.md).

Verified on 2026-07-26: `./kotlin check` reports `Check successful` with no
failing test, `./kotlin do ktfmt` afterwards changes nothing, and the focused
`./kotlin test --include-module promotion` run is 25 tests, 25 successful.

Two open points were closed on 2026-07-26 after a final review, both by Joe
deciding to keep the current state: the admin request body keeps
`discountType`/`discountValue` flat while the response nests them under
`discount` (last row of the deviation log), and the copied service-level
`databaseOperation` helper stays in each module rather than moving into
`platform` (second row of the retrospective). The same review corrected the
recommendation for the activity window in
[`promotion-post-migration.md`](promotion-post-migration.md): the locked check
belongs to the start of the checkout, not to `redeem`.

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
  redemptions. Reservation semantics were redesigned and delivered by the
  Checkout migration on 2026-08-02 as the promotion-owned
  `promotion_reservations` table with the `reserve`/`release` lifecycle
  (`V18`); see [`checkout-migration.md`](checkout-migration.md).
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

- `order_id` column and FK on `promotion_redemptions` — the orders table does
  not exist yet; the column is added by the Order migration, which also wires
  `redeem` to real orders. Per the guide, no placeholder FK is created.
- Cart-side DTOs and endpoints (`ApplyCartPromotionRequest`,
  `AppliedPromotionDto`, cart routes) — owner: Cart migration.
- The customer-facing wire format of the `PromotionCodeResult` failure reasons,
  replacing the legacy `PROMOTION_*` codes — owner: Cart migration.

Delivered by later migrations (moved out of the deferred list, per the guide):

- Reservation of promotion capacity by in-flight checkouts — delivered
  2026-08-02 by the Checkout migration: `promotion_reservations` (`V18`) and
  the `reserve`/`release`/`redeem` lifecycle on `PromotionCodes`; see
  [`checkout-migration.md`](checkout-migration.md).
- Re-checking the active flag and the activity window at checkout time —
  delivered 2026-08-02 by the Checkout migration as `PromotionCodes.reserve`
  (own transaction, promotion row lock, window **and** limit checks); `redeem`
  stays limits-only, exactly as decided.

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
| `Promotion.kt` | `Promotion` | public | Single representation for list/get/create/update responses, incl. `redemptionCount` and `isLocked`; carries the discount as a nested `discount` object (see the last deviation row — the request keeps the pair flat) |
| `Discount.kt` | sealed `Discount` (`Percentage`, `FixedAmount`) | public | Approved domain model; invariants live in the type |
| `PromotionCodes.kt` | `PromotionCodes` capability interface (`validate`, `redeem`) | public | Exported capability for Cart/Checkout/Order |
| `PromotionCodeResult.kt` | sealed `PromotionCodeResult` (`Applicable(id, name, code, discount)` + the seven failure reasons) | public | Typed replacement for `PromotionApplicationFailure`/exceptions; shared by `validate` and `redeem` |
| `PromotionWriteResult.kt` | internal sealed (`Stored`, `NotFound`, `CodeConflict`, `Locked`) | internal | One write produces several meaningful persistence outcomes |
| `PromotionDeleteResult.kt` | internal sealed (`Deleted`, `NotFound`, `Redeemed`) | internal | Added during implementation, see the note below |

14 types is above the 12-type review signal; the excess comes from the second
table, the exported capability with its typed result, and the delete result
below, each of which passes the deletion test. Deliberately not created: a
`StoredPromotion` projection (the representation is the projection), a
redemption domain class (`redeem` returns success/failure; nothing reads
redemption rows yet), a list wrapper, a module result type.

The planned type map deliberately omitted a delete result because "row count
+ FK restrict suffice". Implementing the delete showed that they do not: the
two signals produce three distinct outcomes (`Deleted`, `NotFound`,
`Redeemed`), and `executePostgresWrite` needs a value to map SQL state `23503`
to. `PromotionDeleteResult` therefore exists, exactly as `VatDeleteResult` and
`SupplierDeleteResult` do, and as
[`operation-results.md`](../dev/backend/operation-results.md) prescribes for a
delete with a second expected outcome.

The shared `OperationResult.Conflict` carries no reason, so the update route
cannot distinguish a coupon-code conflict from a locked promotion in its
message and answers with one message naming both causes. The alternative —
giving the shared result a reason — would change a platform type used by eight
modules for one module's need, so it is deferred until a second module wants
it.

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
| `PromotionCodesIntegrationTest` | Testcontainers | `validate`: success value, unknown/inactive/not-started/expired code, login-required precedes limit counting, total and per-user exhaustion; `redeem`: success incl. guest (null user), exhaustion failures, two concurrent redeems against `usage_limit_total = 1` produce exactly one redemption; both activity-window boundaries; an admin update racing a redemption is rejected as locked |
| Flyway on empty PostgreSQL | Testcontainers base | `migratedDataSource` migrates V1–V12 |

## Decision log

### 2026-07-24 — Brainstorming decisions (Joe)

- Reservation logic (pending orders reserve capacity for 15 minutes) is not
  migrated; only real redemptions count. Owner of the follow-up: Order and
  Checkout migrations. *Delivered 2026-08-02 by the Checkout migration
  (`promotion_reservations`, no TTL by Joe's decision — see
  [`checkout-migration.md`](checkout-migration.md)).*
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

### 2026-07-24 — Lock semantics and the redeem row lock (issue #11)

The full update runs as
`UPDATE promotions SET … WHERE id = ? AND NOT EXISTS (redemption)` as planned,
and the repository distinguishes the two meanings of zero affected rows by
looking the promotion up once in the same transaction. That statement is not a
constraint: under `READ COMMITTED`, `NOT EXISTS` takes no lock on
`promotion_redemptions`, so a redemption committing between the subquery and
the update would not be seen. The window is empty today because no operation
writes redemptions.

**Requirement for issue #12 (`PromotionCodes.redeem`)**: `redeem` must take the
promotion row lock (`SELECT … FOR UPDATE` on `promotions`) *before* counting
redemptions and inserting, which is already the planned design. That lock and
the row lock the admin update takes serialize the two writers, so the lock
semantics hold once a redemption writer exists. A concurrency test covering an
admin update racing a redemption belongs to that issue, where a redemption
writer exists to race against.

> Superseded on 2026-07-26 by the entry below: the assumption that the two row
> locks alone serialize the writers turned out to be wrong.

### 2026-07-26 — The capability, and a corrected lock design (issue #12)

`PromotionCodes` is exported as planned: `PromotionCodes.kt` (the interface)
and `PromotionCodeResult.kt` (`Applicable` plus the seven failure reasons).
`PromotionService` implements it next to `PromotionOperations`, so the type map
needs no separate capability service. `PromotionCodeResult.kt` additionally
owns `Promotion.usageFailure`, the single implementation of the usage-limit
rules that the advisory `validate` and the atomic `redeem` share — a top-level
extension function accompanying the type it returns.

Two design points worth recording:

- **`validate` needs a clock.** The activity-window check compares against
  "now", so `PromotionService` takes a `java.time.Clock` and
  `installPromotionModule(database, clock = Clock.systemUTC())` defaults it,
  following the account module. Tests can then place "now" exactly on a window
  boundary; both boundaries belong to the window.
- **Unexpected database failures are not mapped.** The admin operations return
  `OperationResult.UnexpectedFailure`, but the capability lets `SQLException`
  propagate. A consuming module must not receive a business-looking failure
  reason for an infrastructure problem, and it owns the customer-facing error
  payload anyway.

**Correction to the issue #11 entry.** That entry claimed the redeem row lock
plus "the row lock the admin update takes" would serialize the two writers. It
does not. Under `READ COMMITTED` the `UPDATE … WHERE id = ? AND NOT EXISTS
(redemption)` statement evaluates its subquery against its own snapshot; when
the concurrent transaction merely holds `SELECT … FOR UPDATE` on the promotion
row, no new row version is created and PostgreSQL therefore performs no
re-check of the qualification at all. Even where a re-check happens, it
re-reads the locked row, not `promotion_redemptions`. The update would
reconfigure a promotion that had just been redeemed.

Evidence: the new test `an admin update that races a redemption sees the
redemption and locks` was written first and failed against the `NOT EXISTS`
implementation with
`Success(… redemptionCount=1, isLocked=true)` — a full reconfiguration of an
already redeemed promotion.

The admin update was therefore changed from "the writing statement decides" to
**lock-then-decide**: `SELECT … FOR UPDATE` on the promotion row, then count
the redemptions in the following statement (which takes a fresh snapshot and
so contains everything committed while the transaction waited for the lock),
then choose between full update, activation-only update, `NotFound`, and
`Locked`. `redeem` uses the same lock, so both writers queue on one row.
Delete is unchanged: its guard is a real foreign key.

This touches the design approved in issue #11. It is done here because the
issue #11 entry above deferred exactly this race test to issue #12 ("where a
redemption writer exists to race against"), and writing the test showed the
approved design does not hold. Issue #12 itself lists no admin-update
criterion; the change is a correction of #11, carried out in #12.

### 2026-07-26 — Migration completed and simplification review run (issue #13)

The final module has exactly the 14 production types the type map above now
lists — the 13 planned before implementation plus `PromotionDeleteResult`,
which issue #11 added and which is reasoned out under the table. Nothing else
was added and nothing was dropped. The package stays flat (14 files is far
below the ~30 at which Account split into sub-packages), and the five planned
test classes exist with 25 tests.

Simplification review (guide step 4), check by check:

- **List and delete wrappers.** A repository-wide grep for `ListResponse`,
  `ListResult`, `ListItem`, `DeleteResult`, and `PromotionResult` finds no
  Promotion list type at all: `PromotionOperations.list()` returns
  `OperationResult<List<Promotion>>` and the route answers a direct array.
- **`PromotionDeleteResult` justified.** Delete has three outcomes, not two:
  the affected-row count separates `Deleted` from `NotFound`, and the
  restricting foreign key produces `Redeemed` through
  `executePostgresWrite(foreignKeyViolation = …)`, which needs a value to map
  SQL state `23503` to. Deleting the type would either lose the conflict
  outcome or force the service to inspect the exception. `VatDeleteResult`,
  `SupplierDeleteResult`, and `ProductionDestinationDeleteResult` have the
  identical shape for the identical reason.
- **`PromotionWriteResult` justified.** One write produces four meaningful
  outcomes (`Stored`, `NotFound`, `CodeConflict`, `Locked`); the shared
  `OperationResult` cannot carry the difference between the two conflicts.
- **Representations merged.** One `Promotion` serves list, get, create, and
  update. One `PromotionInput` serves create and update, because the legacy
  create and update requests are field- and rule-identical.
- **Shared results and collections.** Both operation seams use the shared
  `OperationResult` and standard Kotlin collections; no module result type
  exists.
- **No copied shared setup.** The routes install no plugin. They use
  `AuthRouting.PROVIDER` plus the auth-owned `installAdminRouteProtection()`,
  and `validatePromotionRequests()` is registered in the single application
  `RequestValidation` block.
- **No constraint-name or message inspection.** A grep for `constraintName`,
  `serverErrorMessage`, and the schema object names finds nothing in the
  module's production sources; conflicts come from SQL states only. The two
  names do appear in `PromotionSchemaIntegrationTest`, which is the one place
  allowed to know them — its job is asserting that Flyway created them.
- **No transaction wrapper.** The repository calls `suspendTransaction`
  directly inside `withContext(Dispatchers.IO)` eight times rather than hiding
  it behind a helper that would only forward Exposed's arguments. The one
  named policy the module does have — "both writers lock the promotion row
  first" — lives in the private `lockedPromotionInTransaction`, which is a
  rule, not a rename.
- **Runtime handle kept.** `PromotionModule` is thin but owns assembly and
  installation and exposes no part of the object graph; `PromotionRepository`,
  `PromotionService`, and both tables stay `internal`.
- **No compatibility code.** No schema adoption, repair, or validation; Flyway
  owns the schema.
- **No TODOs.** A grep finds none; every open question is in the deviation log
  or [`promotion-post-migration.md`](promotion-post-migration.md).

Nothing was removed, because the review found nothing unjustified. The one
duplicate-looking declaration it did stop at is the second, `internal`
`installPromotionModule(operations)` overload, which only
`PromotionRouteSecurityAndValidationTest` calls: it is the route-test seam the
type map and the guide both prescribe, so it stays. Deliberately still absent,
as planned: a `StoredPromotion` projection, a redemption domain class, a list
wrapper, and a module-specific *operation* result type competing with the
shared `OperationResult`.

The guide's completion checklist was then walked item by item against the code,
the tests, and this record. The bullets above already answer the items about
list wrappers, representations, shared inputs, `OperationResult`, write and
delete results, copied infrastructure, transaction helpers, the runtime handle,
compatibility code, package layout, and documentation. The remaining items are
answered by named tests rather than by inspection:

- *One implementation per validation rule, and normalization only after
  successful validation.* `PromotionInput.validate()` is the only place the
  rules exist; `PromotionService` calls it and only then trims.
  `PromotionInputValidationTest` covers the matrix, and
  `PromotionAdminCrudIntegrationTest` proves the service rejects invalid input
  even when Ktor is bypassed.
- *Routes use the shared infrastructure and reject before invoking the
  operation.* `PromotionRouteSecurityAndValidationTest` asserts
  `operationCalls == 0` for the unauthenticated, non-admin, and missing-CSRF
  cases.
- *PostgreSQL owns the concurrency-safe invariants; SQL mappings use declared
  states; undeclared failures are not converted; `CancellationException` is
  rethrown.* Two concurrent creates, two concurrent case-variant updates, two
  concurrent redeems against a limit of one, and an admin update racing a
  redemption are all covered; `PromotionService.databaseOperation` rethrows
  cancellation and maps only `SQLException`, and the hidden-failure path has
  its own test.
- *Required ordering with a stable tie-break.* Asserted on name, then id, in
  `PromotionAdminCrudIntegrationTest`.

Three items needed more than a yes:

- *"Every observable deviation has explicit approval or remains unresolved."*
  The discount body asymmetry is newly recorded as unresolved with Joe as the
  decision owner. Everything else carries a date.
- *"Flyway owns the production schema."* `V12__create_promotions.sql` was
  compared column by column against the legacy EF configurations. It matches,
  including the two absences: legacy declares no foreign key on
  `promotion_redemptions.user_id` either, so the missing one is faithful rather
  than an oversight, and `order_id` is the deferred column of the Order
  migration.
- *"Qualifying process improvements were applied or remain documented with an
  approval owner."* Three were applied to the guide; the fourth (a shared
  database-failure wrapper) is a pending proposal. See the retrospective.

Documentation: [`promotion-package.md`](../dev/backend/promotion-package.md)
gained the concrete request and response bodies, and
[`module-architecture.md`](../dev/backend/module-architecture.md) gained the
`promotion` module, which had been missing from its graph, dependency table,
layout tree, capability list, and composition steps since issue #9. While
renumbering those steps, the Account installation turned out to be missing from
the list too, although `Application.kt` has performed it since the account
migration; it was added in the same pass.
[`migration-roadmap.md`](migration-roadmap.md) moved Promotion to "Already
migrated"; because Promotion was the only blocker of Order (remainder), that
feature moved from Wave 2 into Wave 1 and the roadmap collapsed from four
waves to three.

## Deviation and uncertainty log

| Behavior or contract | Source evidence | Kotlin behavior | Classification | Approval or owner | Follow-up |
| --- | --- | --- | --- | --- | --- |
| In-flight orders reserve promotion capacity | `ActiveReservationOrders` + CartServiceTests | Only real redemptions count | Approved deviation | Joe, 2026-07-24 | Delivered 2026-08-02 by the Checkout migration (`promotion_reservations`, `reserve`/`release`; see [`checkout-migration.md`](checkout-migration.md)) |
| `promotion_redemptions.order_id` column + unique index + FK to orders | `EnforcePromotionRedemptionLimits` migration | Column deferred; no placeholder FK | Approved deviation (guide rule: no placeholder relationships) | Joe, 2026-07-24 | Order migration adds the column and passes the order id to `redeem` |
| Separate checkout validation operation (`ValidateForCheckoutAsync`) | `CheckoutService` | No equivalent operation yet. `redeem` only took over the locked *limit* check that `PaidOrderProcessor` did; the locked *activity-window* check that ran before the payment has no Kotlin counterpart | Approved deviation | Joe, 2026-07-24 | Delivered 2026-08-02: `PromotionCodes.reserve` is the locked pre-payment check (window and limits); `redeem` stays limits-only. See [`checkout-migration.md`](checkout-migration.md) |
| List wrapper `{ items: [...] }` | `AdminPromotionListResponse` | Direct JSON array | Approved deviation (free contract) | Joe, 2026-07-24 | Frontend adaptation (already open) |
| Stable `PROMOTION_*` error codes on the wire | `DomainExceptionHandler` | Typed failure reasons; wire shape decided by the consuming module | Approved deviation (free contract) | Joe, 2026-07-24 | Cart migration defines the customer-facing error payload |
| Constraint names in problem details | `ConstraintAwareProblem` | Not exposed (backend persistence rules) | Incidental | n/a | none |
| Discount value precision and magnitude are unbounded (legacy accepts any positive decimal; `numeric(12,2)` silently rounds sub-cent percentages and errors on overflow) | `PromotionRequestValidator` | Fixed amounts above 9999999999 cents and percentages with more than two decimal places are rejected as field errors, so the stored value always equals the accepted value and the column can never overflow into a 500 | Approved deviation (stricter validation) | Joe, 2026-07-24 | none |
| Flat `discountType`/`discountValue` on requests *and* responses | Legacy `PromotionRequest` and `AdminPromotionDto` are both flat | Requests stay flat; responses nest the pair in a `discount` object, because `Promotion` holds the approved sealed `Discount` value and kotlinx serialization writes a sealed property as a nested object with its discriminator | Accepted deviation (unintended asymmetry, discovered during the issue #13 review; kept for now) | Joe, 2026-07-26 | Revisit with the frontend adaptation. The asymmetry means the frontend maps the discount in one direction only. If it is removed later, nesting the request is the better of the two remaining options: a lax `DiscountInput(discountType, discountValue)` object keeps an invalid `discountType` a field error instead of a deserialization exception, unlike the sealed `Discount`; the cost is that the validation error key becomes `discount.discountValue`. Flattening the response instead needs a hand-written `Promotion` serializer and the guide's compatibility checkpoint |

## Migration retrospective

Run on 2026-07-26 after verification and simplification. The final code matches
the approved behavior matrix, the operation contract table, the runtime
composition design, and the test plan. The type map gained one type,
`PromotionDeleteResult`, which the plan had explicitly omitted and issue #11
found necessary; it lost none. Every behavior classified as `Required` has a
verification, and every `Approved deviation` has Joe's date, including the
discount body asymmetry in the last deviation row, which Joe accepted on
2026-07-26.

Four of the five findings below were applied to a durable document and one was
a proposal that Joe declined for now. The line between them is deliberate: a finding that only
sharpens migration prose or records an environmental fact is applied under
promotion rules 2 and 3, while a finding that would move production code across
a module boundary is an architecture default and waits for Joe under rule 4.

| Finding | Evidence | Scope | Earlier signal or check | Destination and action |
| --- | --- | --- | --- | --- |
| An approved design that "PostgreSQL enforces" can be a guard the database never re-evaluates. The `UPDATE … WHERE id = ? AND NOT EXISTS (redemption)` lock semantics approved in issue #11 let an admin reconfigure a promotion that had just been redeemed | The test `an admin update that races a redemption sees the redemption and locks` failed against that implementation with `Success(… redemptionCount=1, isLocked=true)`; fixed by making both writers take `SELECT … FOR UPDATE` on the promotion row and decide afterwards (decision log, 2026-07-26) | Reusable persistence and concurrency default | The issue #11 entry had already written down that `NOT EXISTS` takes no lock, and then deferred the race test to issue #12 because "no operation writes redemptions yet". The hazard was named and the design shipped anyway. The repeatable signal is the reverse rule: an unverifiable concurrency design is not an approved one | Applied to [`module-migration-guide.md`](module-migration-guide.md) — "Let PostgreSQL enforce concurrent invariants" gained the subsection "A guard inside a writing statement is not a constraint", and the completion checklist's concurrency line now says "through real constraints and row locks rather than conditions inside a writing statement". Promoted after one occurrence under promotion rule 2, which names data-integrity and concurrency gaps explicitly. Where a stronger rule was tempting — forbidding a deferred concurrency test outright, which would be a new stop condition under rule 4 — the guide only asks that such a design be recorded as unverified. Turning that into a hard stop condition is Joe's call |
| The service-level "catch `SQLException`, rethrow `CancellationException`, log, return `UnexpectedFailure`" helper is copied per module | `PromotionService.databaseOperation` is byte-identical to `SupplierService.databaseOperation`; `VatService` and `ProductionDestinationService` carry the same helper under the same name, and `PriceService.withUnexpectedFailureHandling` and `MagicCoinsService.withFailureFallback` are the same shape under different names — six modules. `CountryService` and `AccountService` inline the same `try`/`catch` per operation instead | Candidate shared `platform` infrastructure | The simplification review's "copied shared setup" check lists plugins (auth, CSRF, JSON, StatusPages, validation) but not the service-level database-failure wrapper, so six migrations passed the check while copying it | **Declined for now (Joe, 2026-07-26)** — the helper stays copied in each module; moving it into `platform` next to `OperationResult` changes an architecture default (guide promotion rule 4), so it was recorded rather than applied and can be revisited when a later migration adds the seventh copy. Note the catch is narrower than it first looks: `AccountService` returns module-specific results (`RegisterResult`, `LoginResult`, `ChangeEmailResult`) whose own `UnexpectedFailure` variants a single `OperationResult`-shaped helper cannot produce, so a shared version would have to be generic in the result type or would only serve part of the repository |
| The contract table names required success values but never pins a body shape, so the request/response discount asymmetry survived the analysis and all four implementation issues | The type map says "the JSON contract keeps `discountType`/`discountValue`", which is true of the request and of the nested object, but nobody wrote the two bodies next to each other; the route test asserts `promotionJson.getValue("discount")` without anyone noticing it is not where the request put it | Analysis artifact for every migration | Writing one concrete example request body and response body during the contract step would have made the mismatch visible before the first line of Kotlin | Split across the two smallest authoritative sources (promotion rule 5). The missing *artifact* went to [`migration-base.md`](migration-base.md), whose "Analysis deliverable" item 2 now requires an example request and response body next to the contract table; the reusable *reason* — kotlinx serialization nesting a sealed domain value, so a flat input pair silently becomes a nested output object — went to the guide's contract section. Both are low-risk additions under rule 3 |
| A `./kotlin check` run inside a restricted sandbox hangs silently instead of failing, so a green-looking migration can stall indefinitely at the last step | All 13 modules launched their test JVMs and then logged nothing for 23 minutes; `docker ps` showed zero running containers although `postgres:17-alpine` and `testcontainers/ryuk` were present locally. Re-running with sandbox access produced a container within 30 seconds and then `Check successful` | Always-on backend fact, outside the migration workflow | Nothing in the gate's output says "waiting for Docker". The cheap check is `docker ps` the moment a check produces no verdict, rather than re-reading the code | Applied to [`backend/AGENTS.md`](../../backend/AGENTS.md) — the Quality Gates section now states that the gate needs the Docker socket and describes the silent-hang symptom. Routed there per the guide's "always-on backend invariant outside the migration workflow" row; a factual addition under promotion rule 3 |
| `promotion` was missing from `module-architecture.md` although its own package guide existed; the module had been installed at the composition root since issue #9 | The graph, dependency table, layout tree, capability list, and composition steps all lacked it; found only by grepping for cross-references while closing the migration out. `account` did update the file, so the step is known but easy to skip | Missing completion-checklist item | Guide step 4 and the completion checklist both say "module documentation", which reads as the package guide alone | Applied to [`module-migration-guide.md`](module-migration-guide.md) — step 4 and the completion checklist now name the package guide, `module-architecture.md`, and the roadmap as separate items instead of one "module documentation". A missing reference made explicit, which promotion rule 3 allows directly because it changes no semantics. A grep-based gate was considered and rejected as more machinery than the problem deserves |
