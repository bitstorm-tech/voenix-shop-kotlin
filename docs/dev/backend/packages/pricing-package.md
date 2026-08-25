# The Pricing package

This guide explains the Kotlin code in
[`backend/modules/pricing/src/shop/voenix/pricing`](../../../../backend/modules/pricing/src/shop/voenix/pricing).

## What this package does

Pricing calculates purchase and sales amounts from integer cents, percentage
inputs, and two VAT entries. It can calculate without saving, build a default
input, create a Price, read a Price, and update a Price.

A Price can also carry a **discount**: a percentage or a fixed number of cents
that reduces the sales total. See
[The discount](#the-discount).

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

## The five-minute mental model

- Money is integer cents; percentages are `BigDecimal` and never pass through
  `Double`.
- Only the calculation inputs and the two VAT IDs are stored. Net, tax, gross,
  and calculated percentages are derived every time a Price is read, so a later
  VAT change changes the next response.
- `PriceCalculator` is pure: purchase price, then purchase cost, then the
  purchase total, then sales, then the discount, with `HALF_UP` rounding and
  `0` for a zero base.
- On the sales side an unqualified name is the **effective** value, what the
  customer pays, and a `regular*` name is the value **before** the discount.
  `salesTotal` is therefore always the amount to charge.
- `PriceInput.validate()` owns the field rules. Inactive fields are replaced
  with zero after validation, and HTTP and direct service calls share the same
  rules.
- `PriceService` implements both the internal `PriceOperations` seam for the
  admin routes and the public `PriceCatalog` capability, so an admin request
  and an owning module run the same rules.
- `PriceCatalog` splits the suspending `prepare` from the non-suspending
  `...InTransaction` writes that run in the caller's transaction, so an Article
  and its Price commit or roll back together.
- `PriceRepository` is the only component that touches the `prices` table. VAT
  entries are read through the VAT module's `VatReader`, never its tables.

## Production file map

```text
pricing/
|- CalculatedPrice.kt
|- PriceCalculator.kt
|- PriceCatalog.kt
|- PriceInput.kt
|- PricePercentagePolicy.kt
|- PriceRepository.kt
|- PriceRoutes.kt
|- PriceService.kt
`- PricingModule.kt
```

- `CalculatedPrice.kt` is the HTTP response together with `PriceAmount`, the
  discount types `PriceDiscount` and `PriceDiscountType`, and the three enums
  `PriceCalculationMode`, `PurchaseActiveRow`, and `SalesActiveRow`.
- `PriceInput.kt` is the request with its `validate()` field rules and
  `CalculatedPrice.toPriceInput()`.
- `PriceCalculator.kt` is the pure calculation.
- `PricePercentagePolicy.kt` is the shared precision, scale, range, and
  normalization policy for percentages.
- `PriceService.kt` holds the service and the internal `PriceOperations` seam
  it implements for the routes.
- `PriceCatalog.kt` is the public capability other modules look up by name.
- `PriceRepository.kt` holds the repository and the `Prices` table object.
- `PriceRoutes.kt` holds `installPriceRoutes` and the private helpers that
  turn an `OperationResult` into a response.
- `PricingModule.kt` is wiring only: the internal runtime handle,
  `createPricingModule`, the public `installPricingModule`, and
  `validatePricingRequests`.

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

## The important types

The package contains nine production files. A file groups the declarations that
belong to one concern, as described in
[Kotlin source file organization](../conventions/source-file-organization.md), so a small value
type lives in the file of the component that owns it. The files fall into four
groups:

- [`CalculatedPrice.kt`](../../../../backend/modules/pricing/src/shop/voenix/pricing/CalculatedPrice.kt)
  and [`PriceInput.kt`](../../../../backend/modules/pricing/src/shop/voenix/pricing/PriceInput.kt)
  define the HTTP response and request. They are public, because a module that
  owns prices submits and receives exactly these values through `PriceCatalog`.
  `CalculatedPrice.kt` also holds the small value types a price is made of:
  `PriceAmount`, the monetary amount with `net`, `tax`, and `gross`,
  `PriceDiscount` with its `PriceDiscountType`, and the three enums
  `PriceCalculationMode`, `PurchaseActiveRow`, and `SalesActiveRow` that
  select which inputs drive a calculation. Pricing uses the complete
  [`Vat`](../../../../backend/modules/vat/src/shop/voenix/vat/Vat.kt) type from the VAT package
  instead of defining a second VAT representation. `PriceInput.kt` also holds
  `CalculatedPrice.toPriceInput()`, the narrowing that keeps one column mapping
  for every write.
- [`PriceCalculator.kt`](../../../../backend/modules/pricing/src/shop/voenix/pricing/PriceCalculator.kt)
  is the pure calculation code, while `PriceInput.validate()` owns the
  field rules.
  [`PricePercentagePolicy.kt`](../../../../backend/modules/pricing/src/shop/voenix/pricing/PricePercentagePolicy.kt)
  keeps the shared precision, scale, range, and normalization policy in one
  place. It keeps a file of its own because the input rules, the calculator, and
  the table column all read the same policy, so no single one of them owns it.
- [`PriceService.kt`](../../../../backend/modules/pricing/src/shop/voenix/pricing/PriceService.kt)
  and [`PriceRoutes.kt`](../../../../backend/modules/pricing/src/shop/voenix/pricing/PriceRoutes.kt)
  form the internal application and HTTP seams. `PriceService.kt` holds the
  service together with `PriceOperations`, the internal seam it implements for
  the routes, and `PriceRoutes.kt` holds `installPriceRoutes` together with the
  private helpers that turn an `OperationResult` into a response. The internal
  `PricingModule` is the runtime handle that owns this implementation;
  `installPricingModule` installs the routes and returns the `PriceCatalog` for
  `app`.
  [`PriceCatalog.kt`](../../../../backend/modules/pricing/src/shop/voenix/pricing/PriceCatalog.kt)
  is the one public capability and keeps a file of its own, because other modules
  look it up by name. `PriceService` implements both interfaces, so an admin
  request and an owning module run the same rules.
- [`PriceRepository.kt`](../../../../backend/modules/pricing/src/shop/voenix/pricing/PriceRepository.kt)
  owns Price persistence together with the `Prices` table object, which no other
  component touches. VAT persistence remains in the VAT package.
  The shared
  [`BigDecimalJsonNumberSerializer.kt`](../../../../backend/modules/platform/src/shop/voenix/json/BigDecimalJsonNumberSerializer.kt)
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
   (`MARGIN_PERCENT`), or final total (`TOTAL`);
5. apply the discount, if the price has one; and
6. return all normalized inputs and calculated values.

`CalculatedPrice` contains the complete current `Vat` values for purchase and
sales. Its JSON therefore includes each VAT's `description` and `isDefault`
fields in addition to its ID, name, and percentage.

Cent values and calculated percentages use `RoundingMode.HALF_UP`, which rounds
midpoints away from zero. Calculated percentages have two decimal places. A
zero base produces `0` instead of dividing by zero.

Negative margins are allowed when the resulting sales total is still
non-negative. Integer operations are checked, so an overflow never silently
wraps into a different price.

## The discount

A shop owner puts a single article or prompt on sale by giving its Price a
discount. A discount is the pair of `discountType` (`PERCENTAGE` or
`FIXED_AMOUNT`) and a positive `discountValue`. Both fields absent means "no
discount"; `0` is not a discount, it is an invalid value.

A discount is **not** a coupon. A coupon (see [the Promotion
package](promotion-package.md)) is a cart-level campaign with a code, a time
window, and usage limits, and it is capped when it exceeds the cart. A price
discount has none of that: it is switched on by setting the pair and off by
clearing it, and it is *rejected* instead of capped when it is larger than the
price it reduces. Pricing therefore owns its own `PriceDiscountType`; it does
not import the Promotion module's type.

### The naming rule

The discount changes what the existing names mean:

| Field | Meaning |
| --- | --- |
| `regularSalesTotal` | the configured price, before the discount |
| `salesDiscount` | what the customer saves |
| `salesTotal` | what the customer pays |
| `regularSalesMargin`, `calculatedRegularSalesMarginPercent` | margin before the discount |
| `salesMargin`, `calculatedSalesMarginPercent` | margin after the discount |

So: an unqualified name is the effective value, a `regular*` name is the value
before the discount. Without a discount both are equal, `discount` is `null`,
and `salesDiscount` is `{ "net": 0, "tax": 0, "gross": 0 }`.

This is deliberate: every consumer that charges money already reads
`salesTotal`, so it charges the discounted price without any change. Only a
caller that wants to *show* the crossed-out price has to learn the new
`regular*` fields. A forgotten caller can undercharge, never overcharge.

### How the saving is calculated

The discount applies to the **gross** amount:

1. `saving` is `regularSalesTotal.gross × percent / 100`, rounded to whole
   cents with `HALF_UP`, or the fixed number of cents;
2. the effective gross is `regularSalesTotal.gross − saving`, and its net and
   tax come from the same gross-mode arithmetic every other amount uses, so
   `net + tax == gross` still holds exactly;
3. `salesDiscount` is the component-wise difference of the two totals, so
   `salesDiscount + salesTotal == regularSalesTotal` holds for net, tax, and
   gross; and
4. the effective margin is derived from the effective total against the
   purchase total, exactly as the `TOTAL` row derives the regular margin.

A discount of `100` is allowed. The effective price is then `0`, which is a
legitimate price: the checkout confirms such an order without a payment.

### The reference price is the operator's responsibility

The crossed-out price the shop shows is the configured regular price. The
backend does not track the lowest price of the previous 30 days and therefore
does not enforce the German Preisangabenverordnung (§ 11 PAngV). The operator
decides which regular price is lawful to advertise, and the admin discount card
says so. An automated price history is a follow-up (issue #239), not part of
this feature.

Why it was decided that way, and what has to exist before discounts are used at
scale, is recorded in
[ADR 0004](../../../adr/0004-price-discount-reference-price.md).

## Validation and normalization

Both `purchaseVatId` and `salesVatId` must be positive and must reference an
existing VAT entry. The purchase price must not be negative. The active
purchase-cost input and an active sales total must also be non-negative.

Active percentage inputs may have at most two relevant decimal places and must
fit into four integer digits. Purchase cost percentages range from `0` through
`9999.99`; sales margin percentages range from `-9999.99` through `9999.99`
because a negative margin can be valid. A value such as `12.340` is accepted
because its trailing zero does not add precision.

The discount has its own rules. `PriceInput` carries `discountType` as a
`String`, not as the enum, so an unknown value becomes a field error instead of
a deserialization failure; `PromotionInput` uses the same trick.

| Input | Field | Message |
| --- | --- | --- |
| both fields absent | – | no error, no discount |
| type without value | `discountValue` | `Discount value is required` |
| value without type | `discountType` | `Discount type is required` |
| unknown type | `discountType` | `Discount type must be PERCENTAGE or FIXED_AMOUNT` |
| value `<= 0` | `discountValue` | `Discount value must be positive` |
| percentage `> 100` | `discountValue` | `Discount value must be at most 100 for a percentage discount` |
| percentage with three decimals | `discountValue` | `Discount value must have at most two decimal places` |
| fixed amount with a fractional part | `discountValue` | `Discount value must be whole cents for a fixed amount discount` |

Two more discount rules cannot be checked on the input alone, because they
depend on the sales VAT and on the active sales row. `PriceService` checks them
*after* the calculation and reports them on `discountValue` just like the rules
above:

- a saving larger than the regular gross total, which would produce a negative
  price: `Discount must not exceed the sales total`; and
- a saving that rounds down to `0` cents — for example `0.01 %` of `4.99 €`, or
  any discount on a price of `0` — which would be a discount that discounts
  nothing: `Discount must reduce the sales total`.

The guard against a negative sales total watches `regularSalesTotal`, because a
discount can legitimately take the effective total down to `0`.

Inactive fields do not participate in validation. After validation, the
service replaces them with zero before calculation and persistence. For
example, selecting `COST_PERCENT` stores `purchaseCostInputCents` as `0`. An
accepted `discountValue` is normalized to scale two, so a response is identical
before and after a database round trip.

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

## The PriceCatalog capability

Most prices do not belong to the price admin UI. They belong to an Article. An
Article and its Price must be created, changed, and deleted together, so a
failed Article write must not leave a stray price row behind. `PriceCatalog` is
the seam that makes that possible. `installPricingModule(database, vats)`
returns it, and the composition root passes it straight into the Article
module: `installArticleModule(database, images, prices, suppliers)` in
[`Application.kt`](../../../../backend/app/src/shop/voenix/Application.kt).

The capability is split in an unusual way, and the split is the point:

| Operation | Suspending | Transaction |
| --- | --- | --- |
| `prepare(input)` | yes | none; it never touches `prices` |
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
function would invite an inner `database.write { … }` and a second, independent
database transaction; a plain function can only run statements in the
transaction the caller has already opened. They therefore commit and roll back
with the caller. Called without a transaction they fail with an
`IllegalStateException` rather than writing on their own. The Email and
Production outboxes use the same guard.

`find(ids)` is the read side for list projections. It runs one query for the
prices and one batched `VatReader.find` for every referenced VAT; unknown ids
are simply missing from the returned map. Both reader capabilities in this
backend already use this shape.

Price ownership needs no `owner_kind` column. A price id only exists after
`storeInTransaction` handed it to its owner, no Article contract accepts a
price id from a client, and an update rewrites the same row in place so the id
never churns (decision K2 in
[`article-migration.md`](../../../migration/article-migration.md)).

## Persistence and transaction composition

[`V4__create_prices.sql`](../../../../backend/modules/platform/resources/db/migration/V4__create_prices.sql)
creates the `prices` table. It contains only input fields and required purchase
and sales VAT IDs. PostgreSQL adds:

- restricted foreign keys and an index for each VAT relationship;
- checks for all persisted enum strings; and
- checks for the four non-negative persisted inputs.

[`V28__price_discounts.sql`](../../../../backend/modules/platform/resources/db/migration/V28__price_discounts.sql)
adds the two nullable discount columns, `discount_type text` and
`discount_value numeric(12, 2)` — the same vocabulary and precision the
`promotions` table uses — with five named checks that repeat the input rules the
database can express on its own:

| Constraint | Rule |
| --- | --- |
| `ck_prices_discount_pair` | both columns are set or both are null |
| `ck_prices_discount_type` | `PERCENTAGE` or `FIXED_AMOUNT` |
| `ck_prices_discount_value_positive` | the value is greater than `0` |
| `ck_prices_discount_percentage_max` | a percentage is at most `100` |
| `ck_prices_discount_fixed_whole_cents` | a fixed amount has no fractional part |

`PriceRepository` accesses only the `prices` table. `PriceService` asks the
public `VatReader` capability for both referenced VAT entries in one batch and
then performs the calculation with the returned `Vat` values. The default
selection rule is also application logic in `PriceService`. It chooses the
configured default or, if none exists, the VAT with the smallest ID.

`VatRepository` and `ValueAddedTaxes` are internal to the VAT compilation
module. The compiler therefore prevents Pricing from querying VAT persistence
directly. `Prices`, `PriceRepository`, `PriceService`, `installPriceRoutes`, and
`PricingModule` stay internal; only `PriceCatalog` and the four value types it
exchanges are public. The Pricing manifest exports its VAT dependency because
the public `installPricingModule` accepts a `VatReader` and `CalculatedPrice`
carries both `Vat` values.

`PriceRepository` has two write paths that share one column mapping. The
standalone admin operations open their own transaction with the shared
`database.write { … }` helper from
[`Transactions.kt`](../../../../backend/modules/platform/src/shop/voenix/db/Transactions.kt),
which moves the blocking JDBC call to `Dispatchers.IO` and disables retries. The
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

## Tests

The focused tests cover the pure formulas and rounding, active-field
validation, the discount arithmetic and its two post-calculation rejections,
service behavior against PostgreSQL, one batched VAT lookup for
purchase and sales IDs, auth and CSRF ordering, exact JSON responses, the
complete admin flow, Flyway constraints, outer-transaction rollback, and
recalculation after a VAT change. That a referenced VAT entry cannot be deleted
is a VAT rule, so it is covered by `VatDeleteInUseIntegrationTest` in the VAT
module; see [the VAT package](vat-package.md). The domain statement still
holds: deleting a VAT that a Price references answers `409 VAT is in use`.

The Pricing tests build their VAT reader with the VAT module's
`createVatReader(database)`, which returns the real reader without installing
the VAT admin routes.

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
