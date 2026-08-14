# Backend Pricing package

This guide explains the Kotlin code in
[`backend/modules/pricing/src/shop/voenix/pricing`](../../../backend/modules/pricing/src/shop/voenix/pricing).

## What this package does

Pricing calculates purchase and sales amounts from integer cents, percentage
inputs, and two VAT entries. It can calculate without saving, build a default
input, create a Price, read a Price, and update a Price.

Only calculation inputs and VAT IDs are stored. Net, tax, gross, and calculated
percentages are derived every time a Price is read. A later VAT name or percent
change therefore changes the next Pricing response. Prices are not historical
snapshots.

Pricing also owns the prices of other modules. It exports the `PriceCatalog`
capability so a module such as Article can write itself and its Price in one
transaction; see [The PriceCatalog capability](#the-pricecatalog-capability).

The standalone module deliberately has no list or delete endpoint. Article
already owns its prices through `PriceCatalog`; the Prompt and Cart
relationships are added only when those modules are migrated.

## The important types

The package contains nine production files. A file groups the declarations that
belong to one concern, as described in
[Kotlin source file organization](source-file-organization.md), so a small value
type lives in the file of the component that owns it. The files fall into four
groups:

- [`CalculatedPrice.kt`](../../../backend/modules/pricing/src/shop/voenix/pricing/CalculatedPrice.kt)
  and [`PriceInput.kt`](../../../backend/modules/pricing/src/shop/voenix/pricing/PriceInput.kt)
  define the HTTP response and request. They are public, because a module that
  owns prices submits and receives exactly these values through `PriceCatalog`.
  `CalculatedPrice.kt` also holds the small value types a price is made of:
  `PriceAmount`, the monetary amount with `net`, `tax`, and `gross`, and the
  three enums `PriceCalculationMode`, `PurchaseActiveRow`, and `SalesActiveRow`
  that select which inputs drive a calculation. Pricing uses the complete
  [`Vat`](../../../backend/modules/vat/src/shop/voenix/vat/Vat.kt) type from the VAT package
  instead of defining a second VAT representation. `PriceInput.kt` also holds
  `CalculatedPrice.toPriceInput()`, the narrowing that keeps one column mapping
  for every write.
- [`PriceCalculator.kt`](../../../backend/modules/pricing/src/shop/voenix/pricing/PriceCalculator.kt)
  is the pure calculation code, while `PriceInput.validate()` owns the
  field rules.
  [`PricePercentagePolicy.kt`](../../../backend/modules/pricing/src/shop/voenix/pricing/PricePercentagePolicy.kt)
  keeps the shared precision, scale, range, and normalization policy in one
  place. It keeps a file of its own because the input rules, the calculator, and
  the table column all read the same policy, so no single one of them owns it.
- [`PriceService.kt`](../../../backend/modules/pricing/src/shop/voenix/pricing/PriceService.kt)
  and [`PriceRoutes.kt`](../../../backend/modules/pricing/src/shop/voenix/pricing/PriceRoutes.kt)
  form the internal application and HTTP seams. `PriceService.kt` holds the
  service together with `PriceOperations`, the internal seam it implements for
  the routes, and `PriceRoutes.kt` holds the route installation together with the
  private helpers that turn an `OperationResult` into a response. The internal
  `PricingModule` is the runtime handle that owns and installs this
  implementation for `app`.
  [`PriceCatalog.kt`](../../../backend/modules/pricing/src/shop/voenix/pricing/PriceCatalog.kt)
  is the one public capability and keeps a file of its own, because other modules
  look it up by name. `PriceService` implements both interfaces, so an admin
  request and an owning module run the same rules.
- [`PriceRepository.kt`](../../../backend/modules/pricing/src/shop/voenix/pricing/PriceRepository.kt)
  owns Price persistence together with the `Prices` table object, which no other
  component touches. VAT persistence remains in the VAT package.
  The shared
  [`BigDecimalJsonNumberSerializer.kt`](../../../backend/modules/platform/src/shop/voenix/json/BigDecimalJsonNumberSerializer.kt)
  in `platform` keeps decimal percentages compatible with JSON numbers.

## How a calculation works

Money uses integer cents. `PriceAmount` contains `net`, `tax`, and `gross`.
`PriceCalculationMode.NET` means the submitted value is net; `GROSS` means it
already includes VAT.

The calculator works in this order:

1. calculate the purchase price with the purchase VAT;
2. calculate purchase cost from either a fixed amount (`COST`) or a percentage
   of the purchase price (`COST_PERCENT`);
3. add the net, tax, and gross components separately to form the purchase total;
4. calculate sales from a fixed margin (`MARGIN`), percentage margin
   (`MARGIN_PERCENT`), or final total (`TOTAL`); and
5. return all normalized inputs and calculated values.

`CalculatedPrice` contains the complete current `Vat` values for purchase and
sales. Its JSON therefore includes each VAT's `description` and `isDefault`
fields in addition to its ID, name, and percentage.

Cent values and calculated percentages use `RoundingMode.HALF_UP`, which rounds
midpoints away from zero. Calculated percentages have two decimal places. A
zero base produces `0` instead of dividing by zero.

Negative margins are allowed when the resulting sales total is still
non-negative. Integer operations are checked, so an overflow never silently
wraps into a different price.

## Validation and normalization

Both `purchaseVatId` and `salesVatId` must be positive and must reference an
existing VAT entry. The purchase price must not be negative. The active
purchase-cost input and an active sales total must also be non-negative.

Active percentage inputs may have at most two relevant decimal places and must
fit into four integer digits. Purchase cost percentages range from `0` through
`9999.99`; sales margin percentages range from `-9999.99` through `9999.99`
because a negative margin can be valid. A value such as `12.340` is accepted
because its trailing zero does not add precision.

Inactive fields do not participate in validation. After validation, the
service replaces them with zero before calculation and persistence. For
example, selecting `COST_PERCENT` stores `purchaseCostInputCents` as `0`.

HTTP validation and direct service calls use the same
`PriceInput.validate()` interface, which implements the field rules
directly. Field errors use the shared shape:

```json
{
  "message": "Validation failed",
  "errors": {
    "purchaseVatId": ["Purchase VAT not found"]
  }
}
```

## Decimal percentages and JSON

Percentages use `BigDecimal`; they never pass through `Double`. This avoids
binary floating-point surprises in price calculations.

The frontend contract still uses JSON numbers such as `12.5`, not strings such
as `"12.5"`. `BigDecimalJsonNumberSerializer` reads and writes numeric JSON
tokens while preserving the exact plain decimal representation. PostgreSQL
stores percentage inputs as `numeric(6, 2)`, and Exposed maps them with its
standard `decimal(6, 2)` column type. Validation rejects excessive precision
or magnitude before persistence. The service then normalizes accepted values to
scale two without rounding, so responses stay identical before and after a
database round trip.

## HTTP API

Every route requires an authenticated admin. Both POST routes and PUT also
require the shared `X-XSRF-TOKEN` header.

| Method and path | Success | Persistence |
| --- | --- | --- |
| `POST /api/admin/prices/calculate` | `200` with `id: null` | none |
| `POST /api/admin/prices` | `201`, body, and `Location` header | inserts one Price |
| `GET /api/admin/prices/default` | `200` with `id: null` | none |
| `GET /api/admin/prices/{id}` | `200` with a recomputed Price | read only |
| `PUT /api/admin/prices/{id}` | `200` with the updated Price | replaces all inputs |

The create endpoint is an approved development-phase addition. The original
.NET application creates Price rows only inside Article workflows. Keeping the
endpoint now lets us develop and test Pricing independently. Article reuses the
same application operation through the `PriceCatalog` capability described
below.

The default endpoint prefers the VAT marked as default. If none is marked, it
uses the VAT with the smallest ID. If no VAT exists, it returns
`400 No VAT is configured`. Missing Prices return `404 Price not found`.

## The PriceCatalog capability

Most prices do not belong to the price admin UI: they belong to an Article. An
Article and its Price must be created, changed, and deleted together, so a
failed Article write must not leave a stray price row behind. `PriceCatalog` is
the seam that makes that possible. `installPricingModule(database, vats)`
returns it, and the composition root passes it straight into the Article
module: `installArticleModule(database, images, prices, suppliers)` in
[`Application.kt`](../../../backend/app/src/shop/voenix/Application.kt).

The capability is split in an unusual way, and the split is the point:

| Operation | Suspending | Transaction |
| --- | --- | --- |
| `prepare(input)` | yes | none — never touches `prices` |
| `storeInTransaction(price)` | no | the caller's open transaction |
| `replaceInTransaction(id, price)` | no | the caller's open transaction |
| `deleteInTransaction(id)` | no | the caller's open transaction |
| `find(ids)` | yes | its own short read-only transaction |

`prepare` does the slow part: it validates the input, resolves both VAT entries
through `VatReader`, and calculates every derived amount. It returns the shared
`OperationResult`, so an invalid input or an unknown VAT reaches the caller as
field errors instead of an exception. Because it stores nothing, the caller can
run it *before* opening its own transaction and keep that transaction as short
as the writes.

The three write operations are deliberately not `suspend`. A suspending
function would invite an inner `suspendTransaction` and a second, independent
database transaction; a plain function can only run statements in the
transaction the caller has already opened. They therefore commit and roll back
with the caller. Called without a transaction they fail with an
`IllegalStateException` rather than writing on their own — the same guard the
Email and Production outboxes use.

`find(ids)` is the read side for list projections: one query for the prices,
one batched `VatReader.find` for every referenced VAT, and unknown ids simply
missing from the returned map. This is the shape both reader capabilities in
this backend already use.

Price ownership needs no `owner_kind` column: a price id only exists after
`storeInTransaction` handed it to its owner, no Article contract accepts a
price id from a client, and an update rewrites the same row in place so the id
never churns (decision K2 in
[`article-migration.md`](../../migration/article-migration.md)).

## Persistence and transaction composition

[`V4__create_prices.sql`](../../../backend/modules/platform/resources/db/migration/V4__create_prices.sql)
creates the `prices` table. It contains only input fields and required purchase
and sales VAT IDs. PostgreSQL adds:

- restricted foreign keys and an index for each VAT relationship;
- checks for all persisted enum strings; and
- checks for the four non-negative persisted inputs.

`PriceRepository` accesses only the `prices` table. `PriceService` asks the
public `VatReader` capability for both referenced VAT entries in one batch and
then performs the calculation with the returned `Vat` values. The default
selection rule is also application logic in `PriceService`: it chooses the
configured default or, if none exists, the VAT with the smallest ID.

`VatRepository` and `ValueAddedTaxes` are internal to the VAT compilation
module. The compiler therefore prevents Pricing from querying VAT persistence
directly. `Prices`, `PriceRepository`, `PriceService`, `PriceRoutes`, and
`PricingModule` stay internal; only `PriceCatalog` and the four value types it
exchanges are public. The Pricing manifest exports its VAT dependency because
the public `installPricingModule` accepts a `VatReader` and `CalculatedPrice`
carries both `Vat` values.

`PriceRepository` has two write paths that share one column mapping. The
standalone admin operations wrap the write in `withContext(Dispatchers.IO)` and
`suspendTransaction`, because JDBC blocks even behind a suspending API. The
`...InCurrentTransaction` functions contain only the statement and first assert
that a transaction is open. Since the admin path delegates to them, a price row
written through the REST API and a price row written by an owning module go
through exactly the same code.

`PriceCatalogIntegrationTest` proves the composition against real PostgreSQL: a
caller opens a transaction, stores a price, and throws; afterwards `prices` is
empty. The same test rolls back a `deleteInTransaction` and finds the row still
there. Without that proof a silently nested second transaction would look
correct in every unit test and lose atomicity in production.

Deleting a VAT that is referenced by a Price is rejected by PostgreSQL. The VAT
API exposes this expected domain outcome as `409 VAT is in use`.

## Tests and verification

The focused tests cover the pure formulas and rounding, active-field
validation, service behavior against PostgreSQL, one batched VAT lookup for
purchase and sales IDs, auth and CSRF ordering, exact JSON responses, the
complete admin flow, Flyway constraints, outer-transaction rollback, VAT
deletion, and recalculation after a VAT change.

`PriceCatalogIntegrationTest` covers the capability itself: rollback and commit
of the in-transaction writes, the refusal to write without a transaction, the
existed-or-not answers of replace and delete, `prepare` leaving the table
untouched, and one batched `find` that resolves two prices and both VAT entries
in one lookup each.

Run the final backend gate from `backend/`:

```sh
./kotlin do ktfmt
./kotlin check
```
