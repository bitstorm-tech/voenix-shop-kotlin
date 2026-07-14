# Pricing feature migration

This file is the feature-specific task brief for migrating Pricing from the
.NET backend. It supplements the canonical
[`module-migration-guide.md`](module-migration-guide.md); it does not replace
the guide.

## Task parameters

Feature:

`Pricing`

Source project:

`/Users/joe/projects/joto-ai/voenix-shop`

Source feature:

`/Users/joe/projects/joto-ai/voenix-shop/backend/Voenix.Api/Features/Pricing`

Target project:

`/Users/joe/projects/joto-ai/voenix-shop-kotlin`

Target package:

`/Users/joe/projects/joto-ai/voenix-shop-kotlin/backend/src/shop/voenix/pricing`

Analysis checkpoint:

`approved-for-implementation`

Known consumers:

- the Vue admin article price editor in
  `frontend/src/stores/admin/prices.ts`,
  `frontend/src/composables/useAdminArticlePrice.ts`, and
  `frontend/src/components/admin/article/pricing` in the source project;
- the .NET Article module, which embeds price input and calculated price output
  in its admin contract, creates, updates, reads, and deletes price rows, and
  exposes the current gross sales price in the public Mug list;
- the .NET Prompt module, which holds an optional price reference and exposes a
  smaller calculated sales-price projection;
- the .NET Cart module, which calculates the current gross Article and Prompt
  prices before storing cart price snapshots; and
- the migrated Kotlin VAT module, whose rows are required for every calculation
  and whose default selection is required by the default-price endpoint.

Approved deviations from current behavior:

- expose `POST /api/admin/prices` during development. The source creates Price
  rows only through Article workflows, but the Kotlin module already exposes
  the same transaction-composable create operation that Article will reuse;
- return `409 Conflict` with `VAT is in use` when deleting a referenced VAT
  entry instead of hiding the foreign-key conflict as an internal error.

Explicitly deferred work:

- Article, Prompt, and Cart schema relationships and feature composition are
  owned by those module migrations; see
  [`pricing-post-migration.md`](pricing-post-migration.md).

## Required instructions and sources

Before analyzing or changing code:

1. Read every applicable `AGENTS.md`, including the file nearest to the target
   package.
2. Read
   [`module-migration-guide.md`](module-migration-guide.md)
   completely. It is the canonical migration workflow and the source of the
   project's Kotlin, Ktor, persistence, testing, and completion rules.
3. Inspect the source production code, meaningful source tests, migrations,
   documentation, and known consumers for this feature. At minimum, include:
   - all files below `Voenix.Api/Features/Pricing`;
   - all files below `Voenix.Api.Tests/Features/Pricing`;
   - `20260618105727_RedesignPricesForAdminCalculation.cs` as schema-history
     evidence, while deriving the Kotlin Flyway migration from the final schema
     rather than replaying legacy conversion SQL;
   - the Article, Prompt, Cart, and frontend consumers listed above; and
   - the source VAT model and the target Kotlin VAT package.
4. Inspect the target application's shared operation result, HTTP, auth,
   validation, database, Flyway, and PostgreSQL write-error infrastructure.

Repository instructions and explicit decisions in this task override defaults
in the guide. Do not copy general rules from the guide into this file. Add only
feature-specific facts, decisions, and deviations.

## Migration boundary

The standalone Pricing migration includes:

- the pure price calculation and its exact rounding behavior;
- `POST /api/admin/prices/calculate`;
- `GET /api/admin/prices/default`;
- `GET /api/admin/prices/{id}`;
- `PUT /api/admin/prices/{id}`;
- persistence of price inputs in a `prices` table;
- the purchase-VAT and sales-VAT relationships to the existing
  `value_added_taxes` table; and
- an application-owned calculation seam that later modules can reuse without
  depending on Ktor or Exposed types.

Do not add Article, Prompt, or Cart tables, columns, foreign keys, routes, or
placeholder models in this migration. The approved development-phase deviation
adds a public price creation route even though the source creates price rows as
part of Article workflows. Its application operation must remain composable
inside the future Article transaction.

Do not add standalone list or delete endpoints. Conversion of data from the
historical Entity Framework price schema is outside the clean-database Kotlin
deployment path.

## Feature-specific behavior to classify

The analysis must verify and classify at least the following evidence. It may
find additional behavior.

### External contract

- The existing frontend consumes the current camel-case input and output field
  names without an adapter. The three enums are JSON strings using `NET`,
  `GROSS`, `COST`, `COST_PERCENT`, `MARGIN`, `MARGIN_PERCENT`, and `TOTAL`.
- The calculated response contains the normalized inputs, nullable `id`, both
  compact VAT projections, and the calculated purchase price, purchase cost,
  purchase total, sales margin, sales total, and calculated percentages.
- `calculate` does not persist a row and returns `id: null`.
- `default` returns an unpersisted calculation using the configured default VAT
  for purchase and sales. If no VAT is marked as default, it uses the VAT with
  the smallest ID. If no VAT exists, it is an invalid request.
- `get` and `update` return not found for an unknown price ID. Invalid input or
  an unknown VAT is a bad request. Unexpected persistence failures are hidden
  behind the shared internal-server-error response.
- Admin auth, role, and CSRF policy comes from the shared Kotlin application.
  Rejected write requests must not invoke Pricing operations.

### Calculation

- Money is represented as integer cents. A calculated amount contains net, tax,
  and gross cents.
- Purchase mode determines whether purchase price and the active purchase-cost
  value are interpreted as net or gross. Purchase total is the component-wise
  sum of the separately calculated purchase price and purchase cost.
- Sales mode determines which purchase-total amount becomes the sales base and
  whether the active sales value is interpreted as net or gross.
- Purchase cost is driven by either a fixed amount or a percentage. Sales is
  driven by either a fixed margin, a percentage margin, or a total.
- Cent values and calculated percentages use midpoint rounding away from zero.
  Calculated percentages are rounded to two decimal places; a zero percentage
  base produces zero instead of division by zero. Percentage inputs have at
  most four integer digits and two relevant decimal places.
- A negative sales margin is allowed when the calculated sales total remains
  non-negative. A negative purchase price, active purchase cost, active
  purchase-cost percentage, or active sales total is rejected.
- Inactive purchase and sales input fields are returned and stored as zero. The
  source tests establish this normalization as observable behavior. To follow
  the Kotlin validation rule without changing behavior, validate only the
  active fields and normalize inactive fields after successful validation.

### Persistence and VAT

- Persist only calculation inputs and VAT IDs. Derived amounts and calculated
  percentages are recomputed when a price is read.
- Because derived values are recomputed, changing the name or percentage of a
  referenced VAT changes later price responses. Treat this as current behavior,
  not as historical price snapshotting.
- Both VAT relationships are required and use restricted deletion. Index both
  foreign-key columns.
- The Pricing migration owns the resulting cross-module VAT behavior. Test that
  a referenced VAT cannot be deleted and that updating its percentage changes
  later calculated Price responses.
- PostgreSQL check constraints protect the non-negative persisted purchase
  price, purchase cost, purchase-cost percentage, and sales total inputs. Also
  constrain persisted enum strings to the supported values unless analysis
  finds a reason not to.
- Use a clean Flyway migration. Do not port the source migration's old Article
  relationship reversal or VAT-percent-to-ID data conversion.

## Decisions required at the analysis checkpoint

Stop after the analysis deliverable and obtain approval for these material
decisions together with any others discovered:

1. Select the Kotlin and JSON representation for percentage values. Calculation
   and PostgreSQL persistence need decimal semantics, while the existing
   frontend contract sends and receives JSON numbers. Do not silently use
   binary floating-point arithmetic or add a custom serializer without the
   compatibility analysis required by the migration guide.
2. Confirm the exact public error payload for field-specific validation and
   missing purchase or sales VAT IDs. Reuse `OperationResult.Invalid` and the
   shared `ApiError` shape unless the known frontend proves that a different
   shape is required.
3. Confirm whether the standalone Pricing slice should expose a transaction-
   neutral internal price-creation operation now or defer creation entirely to
   the Article migration. Do not let a Pricing-owned transaction prevent the
   future Article-plus-Price write from being atomic.
4. Review the planned type map even if the module exceeds the CRUD calibration
   count. `PriceAmount`, the compact VAT projection, three persisted enums, and
   the input/output distinction each require an explicit meaning; source DTO
   class names alone are not justification.
5. Decide how a blocked VAT deletion is exposed. The source and current Kotlin
   failure mapping produce an internal-server-error response, while
   `409 Conflict` is the clearer domain outcome. Treat `409` as an observable
   proposed deviation that requires approval, and implement the approved
   behavior and its PostgreSQL integration test together with the Price-to-VAT
   foreign keys.

## Outcome

Migrate the feature's intentional capabilities, business rules, and required
client contract into the Kotlin backend. The source implementation is evidence
about behavior, not a type or architecture template.

Keep the work scoped to this feature and the smallest required changes at
existing application-composition seams. Do not migrate unrelated features or
create placeholder infrastructure for them.

## Analysis deliverable

Before implementation, produce the analysis artifacts required by the migration
guide, including:

1. the behavior-evidence-classification-verification matrix;
2. the operation contract table;
3. material ambiguities and proposed deviations;
4. the Kotlin operation interface and production type map;
5. application-composition and Flyway changes;
6. the test plan; and
7. deferred work and its owner.

Every required behavior must have a planned verification. Do not repeat the
guide's explanations; report only the feature-specific findings and design.

Stop after sharing this analysis. Continue with implementation only after the
material decisions have been approved.

## Implementation

Implement the approved design according to the migration guide. Keep the
feature's behavior matrix and deviation log current while working rather than
reconstructing them at the end.

Create or update:

- the feature implementation and Flyway migration;
- focused calculator, validator, Ktor, PostgreSQL, and cross-module VAT tests;
- the beginner-oriented Pricing documentation in `docs/dev/backend`; and
- [`pricing-post-migration.md`](pricing-post-migration.md) when consumer
  ownership, approved deviations, or deferred work changes.

Do not create a Git commit unless explicitly requested.

## Completion report

After all applicable checks in the migration guide pass, report only the
sections that contain useful information:

- preserved required behavior and its verification;
- incidental source behavior not preserved;
- approved deviations and deferred work;
- final Kotlin type map and shared infrastructure used;
- validation, decimal arithmetic, rounding, security, persistence, and
  transaction decisions;
- tests, Flyway verification, and quality-gate results;
- environmental limitations; and
- unresolved ambiguities.

Do not claim completion when a required verification could not run. State the
attempted command and what remains unverified.

## Analysis checkpoint — 2026-07-14

Status: `approved-for-implementation`. Joe approved the proposed decimal,
validation, type-map, and VAT-conflict decisions on 2026-07-14. He also
approved one additional development-phase deviation: Pricing exposes a public
create endpoint now, backed by the same transaction-composable operation that
Article will reuse later.

The unchanged Kotlin backend compiles with
`./kotlin task :backend:compileJvm`. The first sandboxed attempt could not write
the Kotlin Toolchain telemetry cache; the approved unrestricted retry passed.

### Behavior, evidence, classification, and verification

| Behavior | Evidence | Classification | Planned Kotlin approach | Verification |
| --- | --- | --- | --- | --- |
| The source admin API exposes calculate, default, get, and update only. | `AdminPriceController`; frontend `prices.ts`; migration boundary above | Proposed deviation, approved 2026-07-14 | Also expose `POST /api/admin/prices` during development. It uses the same validation, normalization, calculation, persistence, auth, and CSRF policy as future Article composition. Do not add list or delete routes. | Protected route contract tests, transaction-composition test, and full Ktor/PostgreSQL tests |
| JSON uses the existing camel-case field names and string enum values. | `AdminPriceInputDto`, `AdminPriceDto`, the three source enums, and frontend TypeScript types | Required | Use the shared Kotlin JSON runtime, camel-case Kotlin property names, and enum constants named exactly `NET`, `GROSS`, `COST`, `COST_PERCENT`, `MARGIN`, `MARGIN_PERCENT`, and `TOTAL`. | Exact request/response JSON tests, including rejection of unknown enum values |
| Percentage fields are JSON numbers, not strings. | Frontend fields are TypeScript `number`; `JSON.stringify` sends numbers. | Required | Use `BigDecimal` plus a small JSON-number serializer; do not pass percentage arithmetic through `Double`. | Serializer contract tests with fractional values and JSON token-kind assertions |
| Active percentage inputs have at most four integer digits and two relevant decimal places. | Product decision on 2026-07-14; percentages beyond `9999.99` indicate an invalid price configuration. | Approved deviation | Validate before calculation and persistence; store with PostgreSQL `numeric(6, 2)` and Exposed `decimal(6, 2)`. Do not silently round requests. | Validator, HTTP validation, service, and Flyway metadata tests |
| `calculate` returns a complete calculated response with `id: null` and does not persist. | `PriceService.CalculateAsync`; `CalculateAsync_UsesVatIdsAndDoesNotSave`; frontend calculation flow | Required | Validate, normalize, load both VAT projections, calculate in memory, and return without a write. | Calculator test, service PostgreSQL test, and full route test that checks row count |
| `default` uses the configured default VAT for both sides, otherwise the smallest VAT ID, and returns an unpersisted result. | `BuildDefaultAsync` and its three source tests | Required | Query by `is_default DESC, id ASC`, build the source defaults, and return `id: null`. | PostgreSQL service tests for default, fallback, no VAT, and no inserted Price |
| A missing VAT configuration makes `default` invalid. | `BuildDefaultAsync_ThrowsInvalidPriceRequestException_WhenNoVatExists` | Required | Return `OperationResult.Invalid(emptyMap())`; the default route returns `400` with `ApiError("No VAT is configured")`. | Service and route tests |
| `get` and `update` report a missing Price. | Source service and controller tests | Required | Return `OperationResult.NotFound`; map it to `404` and `ApiError("Price not found")`. | Service and route tests |
| Invalid active input and unknown VAT IDs are bad requests. | `ValidateInput`, `LoadVatsAsync`, source service tests, and task brief | Required | Reuse `OperationResult.Invalid` and `ApiError`; use the field map proposed below. | Complete validator matrix, service VAT lookup tests, and route result tests |
| Inactive purchase-cost and sales input fields become zero in responses and persistence. | `NormalizeInput`; two normalization source tests | Required | Validate only active fields, then normalize inactive fields to `BigDecimal.ZERO` or `0` before calculation and update. | Validator tests prove inactive negatives are ignored; service tests prove returned and stored zeroes |
| Purchase price and cost use the purchase NET/GROSS mode and purchase total is a component-wise sum. | `PriceCalculator`; purchase calculator tests | Required | Pure `PriceCalculator` using integer cents and `BigDecimal`. Add net, tax, and gross components separately with checked integer conversion. | Worked-example calculator tests for both modes and both purchase active rows |
| Sales uses the selected purchase-total component as its base and supports fixed margin, percentage margin, or total. | `PriceCalculator`; sales calculator tests; frontend active-row controls | Required | Preserve the three calculation branches and the selected NET/GROSS base. | Calculator tests covering every mode/active-row combination |
| Cent and calculated-percentage midpoint rounding is away from zero; percentages have two fractional digits. | `RoundToCents`, `RoundPercent`, and cent-rounding source test | Required | Use `BigDecimal.setScale(..., RoundingMode.HALF_UP)`; never use binary floating point. | Positive and negative midpoint tests plus percentage boundary examples |
| A zero percentage base returns zero. | `CalculatePercent` and source test | Required | Return decimal zero before division. | Calculator test for purchase and sales percentages |
| Negative sales margin is valid while the resulting sales total is non-negative. | Two calculator/service source tests and task brief | Required | Do not reject active margin or margin-percent merely for being negative; reject the active sales field only when calculated net or gross total is negative. | Validator/service tests for allowed and rejected negative margins |
| Derived values are recomputed with current VAT master data. | `BuildPriceQuery` includes VAT rows; Article, Prompt, and Cart calculate on read | Required | Persist only inputs and VAT IDs; join both VAT rows for every stored-price read. | PostgreSQL test updates VAT percent/name and verifies a later Price response changes |
| Both VAT references are required, indexed, and deletion-restricted. | `PriceConfiguration`, final EF model, and task brief | Required | V4 creates two non-null foreign keys with `ON DELETE RESTRICT` and two indexes. | Flyway metadata assertions and PostgreSQL deletion test |
| Deleting a referenced VAT currently surfaces as an internal server error. | Source `VatService.DeleteAsync`; current Kotlin `VatService.delete`; both hide FK failures | Proposed deviation | Recommended: map the real PostgreSQL `23503` outcome to `OperationResult.Conflict` and return `409` with `ApiError("VAT is in use")`. | PostgreSQL plus protected VAT-route integration test; requires approval below |
| Persisted non-negative inputs and enum strings are database-protected. | Source checks plus explicit requirements in this task | Required | Add four non-negative checks and four enum-domain checks in V4. | Direct invalid-SQL integration tests for every constraint family |
| Article creates, updates, reads, and deletes Price rows in its own workflow. | `AdminArticleService` and Article DTOs | Required, deferred | Do not add Article schema. Article migration composes the existing create/update operations into its atomic transaction and owns the relationship lifecycle. | Article migration tests listed in `pricing-post-migration.md` |
| Prompt and Cart reuse calculated current sales values. | `PromptService.MapPromptPriceDto`; `CartService.GetArticlePriceAsync` and `GetPromptPriceAsync` | Required, deferred | Keep the pure calculator and calculated output independent of Ktor and Exposed so later modules can compose them. | Prompt and Cart migration tests listed in `pricing-post-migration.md` |
| Article without Price exposes public Mug price `0`; Cart snapshots the current gross amount. | `ArticleService`; `CartService` and cart item fields | Required, deferred | Owned by the Article and Cart migrations respectively. | Deferred consumer integration tests |
| Malformed path IDs follow the existing Kotlin module convention rather than ASP.NET route-matcher details. | Current Country, VAT, and Supplier Kotlin routes; no known Pricing client sends malformed IDs | Incidental | Return the standard Kotlin `400` `ApiError("Invalid price id")`; do not add a custom ASP.NET-compatible matcher. | Route test |
| Parser diagnostics, exception type names, trace IDs, and provider messages are not preserved. | Shared `HttpRuntime`; migration guide | Incidental | Reuse shared invalid-body and internal-error responses. | Route tests ensure no implementation detail is exposed |
| Intermediate integer overflow behavior is not an intentional source contract. | No source test or client dependency; C# mixes checked decimal-to-int conversion with unchecked integer addition | Incidental | Use checked conversion/addition so Kotlin never silently wraps cents; unexpected overflow remains hidden as an internal failure. | Calculator boundary test and route failure-hiding test |

### Operation contract

| Operation | Required input | Success | Required errors | Persistence |
| --- | --- | --- | --- | --- |
| `POST /api/admin/prices/calculate` | `PriceInput` JSON body | `200` with `CalculatedPrice`, `id: null` | `400` field errors or unknown VAT; `500` unexpected failure | No write |
| `POST /api/admin/prices` | `PriceInput` JSON body | `201` with persisted `CalculatedPrice` and `Location: /api/admin/prices/{id}` | `400` field errors or unknown VAT; `500` unexpected failure | Insert one normalized Price |
| `GET /api/admin/prices/default` | none | `200` with source defaults, selected VAT on both sides, and `id: null` | `400 No VAT is configured`; `500` unexpected failure | No write |
| `GET /api/admin/prices/{id}` | numeric Price ID | `200` with recomputed `CalculatedPrice` | `400 Invalid price id`; `404 Price not found`; `500` unexpected failure | Read only |
| `PUT /api/admin/prices/{id}` | numeric Price ID and complete `PriceInput` JSON body | `200` with normalized, stored, and recomputed `CalculatedPrice` | `400` invalid ID/input/unknown VAT; `404 Price not found`; `500` unexpected failure | Replace all persisted calculation inputs |

All five operations require an authenticated administrator. Both `POST`
operations and `PUT` also require the shared CSRF check. Authentication, role,
CSRF, and request validation must reject the call before invoking
`PriceOperations`.

There is deliberately no ordering contract because Pricing has no collection
operation.

### Proposed validation and error payloads

The normal invalid-input response remains the shared shape:

```json
{
  "message": "Validation failed",
  "errors": {
    "purchaseVatId": ["Purchase VAT id is required"]
  }
}
```

The proposed exact field map is:

| Field | Condition | Message |
| --- | --- | --- |
| `purchaseVatId` | missing or not positive | `Purchase VAT id is required` |
| `purchaseVatId` | positive but unknown | `Purchase VAT not found` |
| `salesVatId` | missing or not positive | `Sales VAT id is required` |
| `salesVatId` | positive but unknown | `Sales VAT not found` |
| `purchasePriceInputCents` | negative | `Purchase price input must not be negative` |
| `purchaseCostInputCents` | active and negative | `Purchase cost input must not be negative` |
| `purchaseCostPercent` | active and negative | `Purchase cost percent must not be negative` |
| `purchaseCostPercent` | active and more than two relevant decimal places | `Purchase cost percent must have at most two decimal places` |
| `purchaseCostPercent` | active and greater than `9999.99` | `Purchase cost percent must not exceed 9999.99` |
| `salesTotalInputCents` | active and negative | `Sales total input must not be negative` |
| `salesMarginInputCents` | active and produces a negative sales total | `Sales total must not be negative` |
| `salesMarginPercent` | active and more than two relevant decimal places | `Sales margin percent must have at most two decimal places` |
| `salesMarginPercent` | active and outside `-9999.99` through `9999.99` | `Sales margin percent must be between -9999.99 and 9999.99` |
| `salesMarginPercent` | active and produces a negative sales total | `Sales total must not be negative` |

Missing non-VAT numeric fields retain the source DTO defaults of zero. Missing
mode/active-row fields retain `NET`, `COST`, `GROSS`, and `TOTAL`. Unknown VATs
are looked up before a write and reported independently, so both errors can be
returned when both IDs are unknown. A foreign-key race after that lookup is an
unexpected persistence failure because SQL state `23503` cannot identify which
of the two submitted VAT fields disappeared without inspecting a constraint
name.

### Decimal and rounding design

`purchaseCostPercent`, `salesMarginPercent`, and both calculated percentage
fields use `java.math.BigDecimal` in Kotlin. PostgreSQL stores the two inputs as
`numeric(6, 2)`: four integer digits and two decimal places. Exposed maps those
columns with its standard `decimal(6, 2)` type. The shared validator rejects
active inputs with excessive magnitude or more than two relevant decimal
places before calculation and persistence, so neither layer silently rounds an
accepted request.

`kotlinx.serialization` has no built-in `BigDecimal` serializer. The proposed
`BigDecimalJsonNumberSerializer` emits a raw JSON numeric token with the exact
plain decimal representation and accepts numeric tokens only. This preserves
the frontend contract. Encoding decimals as strings would break the existing
TypeScript client; using `Double` would introduce binary floating-point into
the calculation. The serializer is the only custom compatibility component
proposed for Pricing and is approved below.

Calculations use exact decimal multiplication and division followed by:

- `setScale(0, HALF_UP)` for integer cents; and
- `setScale(2, HALF_UP)` for calculated percentages.

For both positive and negative values, `HALF_UP` implements the required
midpoint rounding away from zero. Percentage inputs are validated rather than
rounded. The service normalizes accepted values to the declared two-digit scale
before calculation and persistence, keeping responses stable across a database
round trip.

### Kotlin production type map

| Type | Kind and visibility | Independent meaning |
| --- | --- | --- |
| `PriceCalculationMode` | public serializable enum | Selects the NET or GROSS amount used by a calculation branch and is persisted. |
| `PurchaseActiveRow` | public serializable enum | Selects fixed purchase cost or percentage purchase cost and is persisted. |
| `SalesActiveRow` | public serializable enum | Selects fixed margin, percentage margin, or total and is persisted. |
| `PriceAmount` | public serializable data class | One monetary amount represented by net, tax, and gross integer cents. |
| `PriceVat` | public serializable data class | Compact current VAT projection required by the Pricing response; full `Vat` has unrelated fields. |
| `PriceInput` | public serializable data class | Existing external input contract and application calculation input; also delegates to the pure validator. |
| `CalculatedPrice` | public serializable data class | Normalized inputs plus all derived output; its nullable ID distinguishes persisted and unpersisted calculations. |
| `BigDecimalJsonNumberSerializer` | public serializer object | Preserves decimal arithmetic while retaining JSON-number compatibility. |
| `PriceCalculator` | public pure object | Application-owned calculation seam with no Ktor, Exposed, or database dependency. |
| `PriceInputValidator` | public pure object | Single implementation of active-field validation. |
| `PricePercentagePolicy` | internal policy object | Keeps percentage precision, scale, range, and exact normalization consistent across validation, calculation, service responses, and Exposed mapping. |
| `PriceOperations` | public interface | Application use cases returning shared `OperationResult`; route and future consumers depend on this seam. |
| `PriceService` | public class | Coordinates validation, normalization, current VAT lookup, calculation, persistence, and failure hiding. |
| `PriceRepository` | public class with internal write details | Owns Exposed queries, current VAT loading, default selection, and writes. |
| `PriceRepository.StoredPrice` | internal nested data class | Keeps one persisted input together with both current VAT projections after a read. |
| `PriceRepository.VatLookup` | internal nested data class | Keeps purchase and sales VAT independently nullable so validation can report both missing references. |
| `Prices` | public Exposed table object | Maps persisted Price inputs and VAT IDs only. |
| `PriceRoutes` | internal route object | Maps the five protected HTTP operations to shared result and error types. |
| `VatDeleteResult` | internal sealed type in the VAT package | Distinguishes deleted, missing, and referenced VAT outcomes without leaking SQL for the approved `409` behavior. |

The count exceeds the CRUD calibration because the calculation has real domain
values and one unavoidable JSON decimal adapter. There are no request subclasses,
response wrappers, feature-specific operation result, list item, delete result
inside Pricing, or duplicated create/update inputs.

The planned operation interface is:

```kotlin
interface PriceOperations {
    suspend fun calculate(input: PriceInput): OperationResult<CalculatedPrice>
    suspend fun create(input: PriceInput): OperationResult<CalculatedPrice>
    suspend fun default(): OperationResult<CalculatedPrice>
    suspend fun get(id: Long): OperationResult<CalculatedPrice>
    suspend fun update(id: Long, input: PriceInput): OperationResult<CalculatedPrice>
}
```

`PriceCalculator` accepts `PriceInput` plus the two compact VAT projections and
returns `CalculatedPrice`. It assumes the input has passed the shared validator
and normalization, while remaining independently testable and reusable by
future application modules. `PriceRepository.insert` uses Exposed's normal
`suspendTransaction`: standalone HTTP creation opens a top-level transaction,
while a future Article-owned outer transaction on the same database is reused
without an independent nested commit. A PostgreSQL rollback test locks this
composition behavior down before Article exists.

### Application composition and Flyway

`Application.installHttpRuntime()` will register `PriceInput` with the one
shared `RequestValidation` plugin. `Application.module()` will compose
`PriceRepository`, `PriceService`, and `PriceRoutes` through two overloads of
`priceModule`, following the existing Country, VAT, and Supplier seams.

`V4__create_prices.sql` will create only `prices` with:

- an identity `bigint` primary key;
- both required VAT IDs and restricted foreign keys;
- both foreign-key indexes;
- four persisted enum columns from the three enum domains;
- all integer-cent and decimal input columns;
- four non-negative input checks; and
- four enum-domain checks.

It will not create Article, Prompt, or Cart objects, will not migrate historical
EF data, and will not copy the old relationship reversal or VAT-percent-to-ID
conversion. Derived amounts and calculated percentages will not be stored.

The clean migration will not add database defaults that existed only to
backfill new non-null columns in the historical EF migration. Application
inputs and all test fixtures write every persisted field explicitly.

### Test plan and agreed seams requested

Implementation will use vertical red/green slices at these public seams:

1. `PriceCalculator`: exact domain calculation and rounding.
2. `PriceInputValidator` and `PriceOperations`: field rules, normalization,
   VAT resolution, create/update persistence outcomes, transaction composition,
   and failure hiding.
3. `/api/admin/prices`: JSON contract, result mapping, admin/role/CSRF policy,
   and rejection before operations.
4. Flyway/PostgreSQL: clean schema, checks, enum domains, foreign keys, update,
   recomputation with changed VAT, and no persistence from calculate/default.
5. `/api/admin/vat/{id}` delete: approved referenced-VAT `409` behavior.

Planned focused test files:

| Test | Coverage |
| --- | --- |
| `PriceCalculatorTest` | Every purchase/sales mode and active row; component sums; positive and negative midpoint rounding; zero bases; allowed negative margins; checked cent boundaries |
| `PriceInputValidatorTest` | Complete field matrix, active-only validation, percentage precision/range, defaults, and normalization preconditions |
| `PriceRouteSecurityAndValidationTest` | Authentication, role, CSRF, invalid body/enums/ID, exact `ApiError`, result mapping, operation call counts, and numeric decimal JSON |
| `PriceServiceIntegrationTest` | Calculate/create/default/get/update, no writes for unpersisted operations, normalized creation, outer-transaction rollback, not found, independent unknown VAT fields, rollback, recomputation after VAT changes, and hidden database failures |
| `PriceAdminIntegrationTest` | Full Ktor + auth + Flyway + Exposed + PostgreSQL contract for all five routes |
| `PriceVatIntegrationTest` | Restricted VAT deletion and the approved `409` response; VAT percentage/name updates affect later Price reads |
| `PriceSchemaIntegrationTest` | V4 on an empty database plus decimal precision/scale, enum/non-negative checks, foreign keys, and VAT indexes |

Targeted tests and `./kotlin task :backend:compileJvm` will run during the
slices. Completion still requires `./kotlin do ktfmt`, `./kotlin check`, a
second stable formatter run, and the post-migration two-axis code review.

### Approved decisions

1. **Decimal representation — revised and approved 2026-07-14.** Use
   `BigDecimal`, PostgreSQL `numeric(6, 2)`, Exposed `decimal(6, 2)`, and the
   numeric-token `BigDecimalJsonNumberSerializer` described above. Validate at
   most four integer digits and two relevant decimal places without silently
   rounding. This keeps the existing frontend JSON-number contract without
   binary floating-point arithmetic and removes the custom Exposed column type.
2. **Validation payload — approved.** Use the exact shared `ApiError`
   and field map above. Use `No VAT is configured` with an empty error map for
   the default endpoint.
3. **Creation — approved deviation.** Implement both the transaction-composable
   application/repository creation seam and `POST /api/admin/prices` now. This
   development deployment may create unattached Price rows; that is acceptable
   before launch. Article still owns its relationship and outer atomic
   transaction when migrated.
4. **Type map — revised and approved 2026-07-14.** Keep the explicit domain
   values and the narrow JSON decimal adapter; use Exposed's standard decimal
   mapping and do not copy the source request subclasses,
   exception hierarchy, or service/entity DTO split.
5. **Referenced VAT deletion — approved observable deviation to `409
   Conflict`.** Return `ApiError("VAT is in use")` instead of the current hidden
   `500`. PostgreSQL remains authoritative and the VAT package maps SQL state
   `23503` without inspecting a constraint name.

### Deferred work and owner

Article owns composition of the existing Price creation operation into the
Article/Price atomic transaction, the relationship and deletion lifecycle, and
the public Mug projection. Prompt owns its relationship and smaller price
projection. Cart owns eligibility lookup and immutable cart price snapshots.
The corresponding wording in
[`pricing-post-migration.md`](pricing-post-migration.md) is updated during
implementation.

## Implementation result — 2026-07-14

The standalone Pricing slice is implemented with all five approved routes,
the clean V4 Flyway schema, exact decimal calculation, normalized persistence,
current-VAT recomputation, transaction-composable create/update operations,
and the referenced-VAT `409` behavior. No Article, Prompt, or Cart schema or
route was added.

The post-migration simplification review found no unjustified Pricing result,
list, or delete wrappers and removed local helpers that merely forwarded
Exposed transaction arguments. The 16 top-level production types in the
approved map remain because each represents a domain value, application
boundary, persistence boundary, or required decimal adapter. The two internal
repository read bundles are also listed explicitly above and are not exposed
from the package.

Verification completed from `backend/`:

- `./kotlin check` passed with 99 tests plus ktlint and Detekt;
- Flyway V4 was applied repeatedly to empty PostgreSQL databases through
  Testcontainers;
- the focused schema, admin HTTP, service, calculator, validator, route, and VAT
  integration tests passed; and
- the final `./kotlin do ktfmt` run made no changes.
