# The Article package

This guide explains the TypeScript code in
[`backend/src/modules/article`](../../../../backend/src/modules/article).
It is the port of the Kotlin module described in the
[Kotlin article guide](https://github.com/bitstorm-tech/voenix-shop-kotlin/blob/kotlin-backend/docs/dev/backend/packages/article-package.md); the
decisions are carried over, the file map, the composition, and the tests are
the TypeScript ones, and [What the port changed](#what-the-port-changed) lists
every difference.

## Contents

- [What this package does](#what-this-package-does)
- [The five-minute mental model](#the-five-minute-mental-model)
- [File map](#file-map)
- [Composition](#composition)
- [HTTP API](#http-api)
  - [Categories](#categories)
  - [Subcategories](#subcategories)
  - [Mugs](#mugs)
  - [SPOD articles](#spod-articles)
  - [The storefront](#the-storefront)
- [The exported capabilities](#the-exported-capabilities)
- [The SPOD catalog sync](#the-spod-catalog-sync)
  - [How one sync run works](#how-one-sync-run-works)
  - [The report and its warning codes](#the-report-and-its-warning-codes)
  - [The sync decisions](#the-sync-decisions)
- [Validation and normalization](#validation-and-normalization)
- [The database schema](#the-database-schema)
- [Concurrency: the ordering locks and the deferred unique rule](#concurrency-the-ordering-locks-and-the-deferred-unique-rule)
- [What the port changed](#what-the-port-changed)
- [Tests](#tests)

## What this package does

The Article package owns the product catalog: the shared category structure
(categories and subcategories) and one table per article type — *mugs*, and
*SPOD articles*, every product the print-on-demand partner Spreadconnect (SPOD)
prints one front motif on. The article type names the **channel** a product is
made through, not the kind of product: a t-shirt and a tote bag are both `SPOD`.

- **Admin routes** for categories, subcategories, mugs (with the variant
  example-image pre-upload), and SPOD articles (no create, no upload: a sync
  run creates them).
- **Three anonymous storefront reads**: the mugs and the SPOD articles a
  customer may buy, and the one type-agnostic navigation both sit in.
- **Two capabilities**: `articleCatalog` (`find` and `printFormats`), which
  Cart, Order, Checkout, Production, and the Generator use for the article
  references they store, and `spodCatalogSync`, which Production triggers on a
  destination.

Every level has a display order that is **dense** (1, 2, 3, … without gaps) and
**unique**: globally for categories, per category for subcategories, per
article type for mugs and SPOD articles. Category and subcategory names are
unique regardless of letter case; articles have no name rule. PostgreSQL
enforces all of these, and the module never asks it whether a rule would hold
before writing.

An article is more than a row: writing one writes its identity, its variants
and their identities, and the price row it owns, all in one transaction. A
rejected article never leaves a price behind, and a rejected price never
creates an article.

## The five-minute mental model

```mermaid
flowchart TB
    Admin["Admin client"]
    Shop["Storefront client<br/>anonymous"]
    Consumers["cart · order · checkout · production · generator"]
    Production["production<br/>the destination's sync button"]

    subgraph Article["article module"]
        Routes["category/ · mug/ · spod-article/ routes.ts<br/>Zod schemas · handlers"]
        StorefrontRoutes["routes.ts<br/>shared fragments · upload · storefront routes"]
        Services["…/service.ts<br/>example images · price · supplier names"]
        Repositories["…/repository.ts · persistence.ts<br/>Kysely · ordering locks · dense positions"]
        Storefront["storefront.ts<br/>one visibility rule"]
        Catalog["catalog.ts<br/>articleCatalog"]
        Sync["spod-article/sync.ts · calibration.ts · sync-repository.ts<br/>spodCatalogSync"]
    end

    Image["image<br/>exampleImages(folder, logger)"]
    Pricing["pricing<br/>priceCatalog"]
    Supplier["supplier<br/>find(ids)"]
    Spod["spod<br/>SpodClient"]
    Database[("PostgreSQL")]

    Admin --> Routes --> Services --> Repositories --> Database
    Shop --> StorefrontRoutes --> Storefront --> Database
    Consumers --> Catalog --> Database
    Production --> Sync
    Services --> Image & Pricing & Supplier
    Repositories -->|"writes with the article's trx"| Pricing
    Storefront & Catalog -->|"find"| Pricing
    Sync --> Spod & Image
    Sync --> Database
```

- The Zod schema of a body is its only validation; the service trusts what it
  receives (see [Request validation](../conventions/request-validation.md)).
- The services check what needs another module before a transaction opens —
  the submitted example images, the price (`priceCatalog.prepare`). The
  category repository answers `OperationResult` itself; the mug and SPOD
  article writes answer a small union (`'priceRequired'`, `'unknownVariant'`,
  `'supplierNotFound'`, …) that their services turn into field errors.
- The repositories own the transactions, the ordering locks, and the SQLSTATE
  mapping. The two article repositories write the price with
  `priceCatalog.storeInTransaction(trx, …)` and its siblings, so the price row
  commits or rolls back with the article.
- The module queries only its own tables; supplier names, prices, and pictures
  come from the owning modules' capabilities.

## File map

```text
article/
|- index.ts              createArticleModule(deps) → { routes, articleCatalog, spodCatalogSync, imageReferences }; the exported types
|- schema.ts             the thirteen article tables, merged into the platform's Tables
|- routes.ts             shared request fragments (reorderInput, articlePrice, optionalId, …),
|                        storefrontRoutes; the pre-upload routes use the image module's exampleImageUpload
|- image-references.ts   FOLDERS (the public picture folders) and createImageReferences: every picture a row
|                        refers to, for the image module's orphan list
|- persistence.ts        ordering locks, nextPosition, moveInSequence, rewritePositions, closePositionGap,
|                        lockCategoryReference, namesOf, unreferencedFilenames
|- price.ts              preparePrice (`null` keeps the stored price; `priceCatalog.prepare` already reports
|                        under `price.*`), writePrice (store, replace in place, keep)
|- catalog.ts            articleCatalog: find, printFormats; ArticleType, PrintAspectRatio, CatalogVariant,
|                        catalogKey, spodVariantName
|- storefront.ts         the three anonymous reads and their one visibility rule
|- category/
|  |- routes.ts          articleCategoryInput, articleSubcategoryInput, the category and subcategory routes
|  |- service.ts         both use cases, the subcategory example-image rule
|  `- repository.ts      both repositories' queries, the category locks, the move-retry loop
|- mug/
|  |- routes.ts          mugArticleInput, the mug routes
|  |- service.ts         MugArticleInput; images checked, price prepared, supplier names
|  `- repository.ts      MugArticle, MugArticleListItem, MugDetails; the writes and the variant diff
|- spod-article/
|  |- routes.ts          spodArticleInput, the five SPOD admin routes
|  |- service.ts         SpodArticleInput, the admin use cases, the three picture folders
|  |- repository.ts      SpodArticle, SpodArticleListItem, PrintFrame; reads and the shop-owned write
|  |- sync.ts            createSpodCatalogSync: one run; the report types
|  |- sync-repository.ts the run's two transactions: upsert one article, sweep
|  `- calibration.ts     the marker design, measurePrintFrame (sharp), createCalibration
|- test-fixtures.ts      test-only: the module on real pricing, supplier, image; SQL seeds; drawn previews
`- *.test.ts             see Tests
```

The sub-folders follow the Kotlin sub-packages (`category`, `mug`, `spod`).
The SPOD one is called `spod-article/` so it is not confused with the `spod`
module, the partner's HTTP client. The Kotlin `persistence/` package is gone:
each slice holds its own repository, and what every repository shares lives in
`persistence.ts` and `price.ts`.

## Composition

```ts
const pricing = createPricingModule({ db })
const suppliers = createSupplierModule({ db, countries })
const images = createImageModule({ settings: settings.image, guestTokens: auth.guestTokens })
const spod = createSpodClient()            // the one client of the application
const article = createArticleModule({ db, images, prices: pricing.priceCatalog, suppliers, spod })
app.route('/', article.routes)
// article.articleCatalog → cart, order, checkout, production, generator
// article.spodCatalogSync → production (the destination's sync route)
```

`createArticleModule` asks `images.exampleImages(folder, logger)` once per
picture folder, all logging under the `article` logger:

| Folder | Written by | Deleted by |
| --- | --- | --- |
| `articles/subcategory-example-images` | the subcategory pre-upload | subcategory update and delete |
| `articles/mugs/variant-example-images` | the variant pre-upload | mug update and delete |
| `articles/spod/listing-images` | the sync | the sync, the SPOD delete |
| `articles/spod/mockups` | the sync | the sync, the SPOD delete |
| `articles/spod/size-charts` | the sync | the sync, the SPOD delete |

## HTTP API

Every route under `/api/admin/articles` requires the `ADMIN` role; mutating
methods also need the CSRF header (the platform's protection). The three
`/api/articles` routes are anonymous. Lists are bare JSON arrays.

### Categories

| Method and path | Success | Errors |
| --- | --- | --- |
| `GET /api/admin/articles/categories` | `200`, every category in display order | |
| `POST /api/admin/articles/categories` | `201`, the category, `Location` | `409 Article category name already exists` |
| `PUT /api/admin/articles/categories/order` | `200`, the complete new order | `404 Article category not found`, `409 Article category order changed concurrently, please retry` |
| `GET /api/admin/articles/categories/{id}` | `200`, the category | `404 Article category not found` |
| `PUT /api/admin/articles/categories/{id}` | `200`, the category | `404`, `409 Article category name already exists` |
| `DELETE /api/admin/articles/categories/{id}` | `204` | `404`, `409 Article category is used by subcategories or articles and cannot be deleted` |

A malformed id answers `400 Invalid article category id`. The body is
`{ "name", "description", "active" }`; the answer adds `id` and `position`.
`position` is never accepted: create appends, delete closes the gap, the
reorder moves one category to the place of another. The reorder body of every
level is `{ "sourceId": 42, "targetId": 7 }` and its answer is the complete new
order, so a client never reconstructs the sequence.

### Subcategories

| Method and path | Success | Errors |
| --- | --- | --- |
| `GET /api/admin/articles/subcategories` | `200`, by category order, then own order | |
| `POST /api/admin/articles/subcategories` | `201`, `Location` | `409 Article subcategory name already exists in this article category`, `400 categoryId: Article category does not exist` |
| `POST /api/admin/articles/subcategories/example-images` | `201`, `{ "filename": "…" }` | `400 file: …` |
| `PUT /api/admin/articles/subcategories/order` | `200`, the new order of the affected category | `404 Article subcategory not found`, `409 Article subcategory order changed concurrently, please retry` |
| `GET /api/admin/articles/subcategories/{id}` | `200` | `404 Article subcategory not found` |
| `PUT /api/admin/articles/subcategories/{id}` | `200` | `404`, `409` name, `400 categoryId: Article category does not exist`, `400 categoryId: Article subcategory is used by articles and cannot be moved to another category` |
| `DELETE /api/admin/articles/subcategories/{id}` | `204` | `404`, `409 Article subcategory is used by articles and cannot be deleted` |

A malformed id answers `400 Invalid article subcategory id`. A subcategory
names its `categoryId` in body and answer. A category change appends in the
new category and compacts the old one. A reorder target from another category
is outside the reordered list and answers `404` like an unknown id.

**The example image.** Upload and save are two requests. The pre-upload takes
a `multipart/form-data` body with a `file` part (any other content type
answers `415` through the platform's `formBody`), stores it through the image
module (always WebP under a minted UUID name), and answers the name; create and
update then carry it as `exampleImageFilename` (`null` removes the image). A
missing part answers `400 file: An example image file part is required`, an
oversized one `400 file: Example image must not exceed 10 MiB`, and what the
storage rejects arrives in the same shape. While saving, a submitted name must
look minted and the file must exist (`Example image filename must be the name
of an uploaded image`, `Example image does not exist` on the field) — also a
name the row already stores, because a vanished stored file means another
writer replaced it. A file a write stops naming is deleted after the commit,
only when no other row names it (asked inside the transaction); a failed delete
is only logged. An upload nobody saves stays behind as an accepted orphan. The
rule itself is the image module's `exampleImages`.

### Mugs

| Method and path | Success | Errors |
| --- | --- | --- |
| `GET /api/admin/articles/mugs` | `200`, `MugArticleListItem` rows in display order | |
| `POST /api/admin/articles/mugs` | `201`, `MugArticle`, `Location` | field errors below |
| `PUT /api/admin/articles/mugs/order` | `200`, the complete new order as list rows | `404 Article not found`, `409 Article order changed concurrently, please retry` |
| `POST /api/admin/articles/mugs/variant-example-images` | `201`, `{ "filename": "…" }` | `400 file: …` |
| `GET /api/admin/articles/mugs/{id}` | `200`, `MugArticle` | `404 Article not found` |
| `PUT /api/admin/articles/mugs/{id}` | `200`, `MugArticle` | `404`, field errors below |
| `DELETE /api/admin/articles/mugs/{id}` | `204` | `404` |

A malformed id answers `400 Invalid article id`. The request carries the
article, `mugDetails`, `mugVariants`, an optional `printAspectRatio` (`16:9`
when absent), and an embedded `price` (the pricing module's `priceInput`).
The answer adds the ids, `position`, and the full `CalculatedPrice`; there is no
`priceId` and no `articleType` on either side, which is what makes a price
belong to exactly one article by construction.

- **The overview list** names what a mug references (`categoryName`,
  `subcategoryName`, `supplierName`), counts its variants, and shows the
  picture of the default variant or, failing that, of the oldest variant with
  one. It runs four queries plus **one** `supplier.find` for the whole page.
- **Variants** come back default first, then by name, then by id.
- **The variant array is a diff**: an entry with an id updates that variant,
  one without inserts, a stored variant the array omits is deleted with its
  image; an id of another article answers `400 mugVariants: One or more
  variants do not belong to this article`. Removals run first, then every
  remaining default flag is cleared, then the submitted flags are written, so
  the one-default index holds at every step.
- **The price**: an omitted `price` keeps the owned row, a submitted one is
  written over it (`replaceInTransaction`), and the delete removes it after the
  identity row (the reference is `ON DELETE RESTRICT`).

Apart from the reorder, a mug write has no `409`: rejections are field errors.

| Field | Message | Decided by |
| --- | --- | --- |
| `categoryId` | `Article category does not exist` | the category lock that found no row |
| `subcategoryId` | `Article subcategory does not exist in this article category` | the lookup inside the locked category |
| `supplierId` | `Supplier does not exist` | SQLSTATE `23503`, the only reference left |
| `mugVariants` | `One or more variants do not belong to this article` | the diff, under the mug's row lock |
| `price` | `An active article requires a price` | the write path, which knows the stored price |
| `price.*` | the pricing module's messages (`Purchase VAT not found`, …) | `priceCatalog.prepare`, before the transaction |
| `mugVariants[i].exampleImageFilename` | `Example image …` | the image storage, before the transaction |

The write order is the old one:

```text
schema                   → 400, nothing happened
check example images     → 400, nothing happened
priceCatalog.prepare     → 400 price.*, no article
BEGIN
  lock article_types('MUG') (create, delete, reorder)
  lock the category, look the subcategory up inside it
  lock the mug row (update, delete)
  write the price with the trx         ← outside the 23503 mapping
  identity, mug, variant identities, variants   ← inside it
COMMIT
delete orphaned image files (best effort)
```

The price write sits outside the `23503` mapping: a VAT deleted after
`prepare` must stay a `500` rather than become
`supplierId: Supplier does not exist`. The price write locks its VAT entries
and throws a plain error for a missing one (see the pricing guide), so it
raises no `23503` in the first place.

### SPOD articles

How an operator gets one into the shop is the manual
[Configuring a SPOD article](../../guides/configuring-spod-articles.md).

| Method and path | Success | Errors |
| --- | --- | --- |
| `GET /api/admin/articles/spod` | `200`, `SpodArticleListItem` rows | |
| `PUT /api/admin/articles/spod/order` | `200`, the complete new order | `404 Article not found`, `409 Article order changed concurrently, please retry` |
| `GET /api/admin/articles/spod/{id}` | `200`, `SpodArticle` | `404 Article not found` |
| `PUT /api/admin/articles/spod/{id}` | `200`, `SpodArticle` | `404`, field errors below |
| `DELETE /api/admin/articles/spod/{id}` | `204` | `404` |

There is no create and no upload (sync decision 1); the old
`/api/admin/articles/tshirts/…` paths answer `404`. A malformed id answers
`400 Invalid article id`.

**Two owners.** The sync owns the product: name, descriptions, product type
name, colours with their pictures, the variant matrix, the size chart and its
measurements, the
supplier, the measured `printFrame` with its `printMask` and `hotspot`, and the purchase price
(the highest variant `b2bPrice`, net; a new article gets a price with a sales
total of zero on the default VAT). The shop owns `active` (a run only switches
it off), the category path, the position, the sales side of the price (a run
holds the regular sales total; a margin row becomes the cent margin that keeps
it), and the default variant. Activation needs a sales price above zero. The update body is exactly the shop's half:

```json
{ "active": true, "categoryId": 7, "subcategoryId": 42, "defaultVariantId": 34,
  "price": { "purchaseVatId": 1, "salesVatId": 1, "salesTotalInputCents": 1990 } }
```

| Rule | Reported on | Where |
| --- | --- | --- |
| an active article needs a category | `active` | schema |
| an active article needs a default variant (`An active article requires an active default variant`) | `active` | schema |
| the default must be an active variant of this article (`The default variant is not an active variant of this article`) | `defaultVariantId` | write path |
| an active article needs a price | `price` | write path |
| `An article that is missing at Spreadconnect cannot be activated` | `active` | write path |
| `An article without a measured print frame cannot be activated` | `active` | write path |
| unknown category / subcategory | `categoryId` / `subcategoryId` | write path |

The detail answer adds `appearances` (each colour with `mockupImageFilename`
and its ordered `listingImageFilenames`), the flat `spodVariants` in the order
of their colour then id (`name` is the composed `"Black / M"`, plus the three
SPOD ids, `spodVariantId`, `sku`), `sizeMeasurements` (below), `printFrame`
and `hotspot` (`null` until a run measured them), `printMask` (the part of the
frame the product shows, as a PNG data URL; `null` when that is all of it, see
decision 7), the embedded `price`, and
`sync: { spodArticleId, environment, syncedAt, missingSince }`. A list row is
the mug row's twelve fields plus `syncedAt` and `missingAtSpreadconnect`; its
picture is the first listing image of the default variant's colour. The delete
retires an article for good (the next sync of that destination brings it back
if the operator was wrong) and deletes its listing images, mockups, and size
chart when no other row names them; its measurements go with the row (cascade).

**`sizeMeasurements`** is
`{ size, measurements: { name, valueMm }[] }[]`: one entry per size that has at
least one measurement, `size` the `sizeLabel` of the variants with that SPOD
size id (the variant with the lowest id when the colours disagree),
`measurements` ordered by `name` (the letter of the size chart picture),
`valueMm` a JSON number. The order of the entries is not part of the contract.
The admin answers every size of the article's variants, the storefront only the
sizes of active variants.

### The storefront

| Method and path | Success |
| --- | --- |
| `GET /api/articles/mugs` | `200`, the mugs a customer may buy, in display order |
| `GET /api/articles/spod` | `200`, the SPOD articles a customer may buy, in display order |
| `GET /api/articles/categories` | `200`, the navigation over every visible article |

**One visibility rule**: the article is active, its category is set and
active, and it has no subcategory or an active one. `storefront.ts` writes it
once per type (`visibleMugs`, `visibleSpodArticles`) and every read starts from
it, so the navigation never leads into an empty list.

- **No supplier fields and no `active` flags**; only active variants.
- **`articleType`** (`"MUG"`, `"SPOD"`) is the discriminator a grid that merges
  both lists switches on. It stays out of the admin representations.
- **`price`** is the effective gross in cents (a discount already subtracted),
  **`regularPrice`** the gross before the discount, `null` without one. Both
  come from **one** `priceCatalog.find` per page; an empty page asks nothing.
- A SPOD article answers `productTypeName`, `sizeChartImageFilename`,
  `sizeMeasurements` (the admin shape, active sizes only), the
  `printFrame` and its `printMask`, the colours that still have an active variant, and flat
  variants `{ id, name, appearanceId, size, isDefault }`. **The three SPOD ids
  and the hotspot never appear**: the printer's vocabulary stays inside.
- The navigation runs one `DISTINCT` query per article type and merges them,
  sorted by position then id on both levels.

## The exported capabilities

```ts
articleCatalog.find(references: Iterable<ArticleVariantReference>): Promise<Map<string, CatalogVariant>>
articleCatalog.printFormats(articleIds: Iterable<number>): Promise<Map<number, PrintAspectRatio>>
spodCatalogSync.sync(source: SpodCatalogSource): Promise<SpodSyncResult>
```

**`find`** answers a batch of `{ articleId, variantId }` pairs. The map is
keyed by `catalogKey(reference)` (`"12:34"`), because a JavaScript `Map`
compares object keys by identity; a caller looks its stored pair up with the
same function. Unknown pairs are **absent** — a deleted article, a variant that
never existed, and a pair whose variant belongs to another article read the
same way. One query per article type selects the variant ids, the article half
is matched in memory, and one `priceCatalog.find` resolves every price; an
empty input runs no SQL, a batch without prices asks pricing nothing.

| `CatalogVariant` field | Meaning |
| --- | --- |
| `articleType` | `'MUG' \| 'SPOD'` |
| `articleName`, `variantName` | a SPOD variant's name is `"Black / M"` |
| `purchasable` | active article ∧ active variant ∧ a price exists |
| `grossSalesPriceCents` | the effective gross, `null` without a price row — never a `0` |
| `supplierId`, `supplierArticleNumber` | a SPOD article answers no number |
| the five layout mm fields | `printTemplateWidthMm`, `printTemplateHeightMm`, `documentFormatWidthMm`, `documentFormatHeightMm`, `documentFormatMarginBottomMm`; `null` for SPOD and for a mug without details |
| `outsideColorCode`, `insideColorCode` | `null` for SPOD |
| `spodProduct` | `{ productTypeId, appearanceId, sizeId }`, `null` for a mug |
| `spodHotspot` | the hotspot the frame was measured for, `null` for a mug and an unmeasured SPOD article |

Every field is current master data, not a snapshot: an order line copies what
it needs at checkout. A database failure throws; an empty map would tell a
cart its articles are gone.

**`printFormats`** answers `'16:9' | '1:1'` per known article: a mug's stored
ratio, and `'1:1'` for every SPOD article (AI image generation stays decoupled
from the measured print frame's aspect ratio for now; the editor crops and
composes to the frame's stored `widthPct / heightPct`).

**`spodCatalogSync`** is described next.

## The SPOD catalog sync

`spodCatalogSync.sync({ supplierId, access })` runs one sync of the destination
`access.destinationId` (the `SpodAccess` of the `spod` module) and answers
`{ kind: 'reported', report }`, or `{ kind: 'busy' }` while the same
destination is already syncing. The lock is an in-process `Set` of destination
ids — exactly as far as one backend instance goes. Nothing is queued: the
caller is a human waiting for the report.

### How one sync run works

1. **Read the whole listing**, page by page with `limit=100`, until as many
   articles arrived as the partner said it has. A refusal ends the run with
   `status: 'FAILED'` and the client's `SpodError`; a page without a `count`, a
   `count` that changes, a repeated article id, an empty page while more were
   promised, and more than 100 pages end it with `PROVIDER_ANSWER_UNREADABLE`.
   A count of zero with no items is a complete, empty catalog. On a failure
   nothing is written and nothing is swept.
2. **Reconcile each article in one transaction of its own.** Variants without
   all three product ids or with an empty `appearanceName` (retired colours the
   partner still lists without name or colour value) are dropped, duplicates of
   the triple too. The article is skipped (into `failed`) for several product
   types, no printable variant, an id longer than its 64-character column, or
   two named colours of one name. Then its product type is **calibrated**, once
   per run (decision 7);
   a partner that does not answer the calibration skips the article
   (`CALIBRATION_UNAVAILABLE`) before any picture is downloaded. Then the
   colours are resolved in first-appearance order: the colour value is parsed
   (`parseColorHex`), every listing image of the colour is stored from
   `downloadUrl()` unless a stored image has the same partner image id, and the
   blank front mockup from the views is stored unless the stored one came from
   the same URL. A stored picture is reused only while its file is still in the
   storage; one whose file is gone is downloaded again under a new name, so a
   plain run heals pictures lost from the disk without changing the article id.
   The size chart is asked for and downloaded once per product
   type per run; the whole answer is cached per type (`null` when it was not
   answered). Its measurements are kept for the sizes among the article's
   variants only: names trimmed, an entry with an empty name, a name over 32
   characters, or a value that does not round to more than `0` and less than
   `100000` mm dropped, values rounded to one decimal, the first of a duplicate
   name within one size kept. There is no warning for any of it. All partner and CDN I/O happens **before** the transaction
   opens. Files a write orphans are deleted right after its commit.
3. **Sweep**: every article of this destination the listing did not contain,
   and every article of the other environment, is deactivated with its
   variants and stamped `spod_missing_since`. Nothing is deleted; an article
   already marked is left alone.

Inside the transaction colours are upserted by appearance id, a mockup is
replaced only when the run fetched a new one, listing images are rewritten only
when the ordered list of image ids or their file names differs, and variants are matched by the
product triple and never deleted. The measurements are replaced as a whole
(delete and insert) only when the answered set differs from the stored one,
which makes the article `updated`; an answer without `sizes` deletes them, an
unanswered size chart keeps them. The frame, its mask, and the hotspot are written as
measured every run — `NULL` when the measurement failed, which also takes
`active` off. **A run never activates**: `active` stays only while it was on,
a variant is still sellable, and a frame was measured. A reappearing article
clears its marker, stays inactive, and is warned about. An admin who deletes
the article between the pre-read and the write makes the write answer "gone";
the run deletes the files it minted and prepares the article once more from
nothing.

**A second identical run** lands every article in `unchanged`, downloads no
picture (one storage check per stored picture), and costs one `spod_synced_at` bump per article — plus the
re-measurement (cached complementary design pairs per run, and `2 + 2 * candidates` plus aspect-ratio probe calls
per product type).

### The report and its warning codes

The report is a diff: `destinationId`, `supplierId`, `environment`, `status`,
`failure`, `startedAt`, `finishedAt`, `fetchedArticles`, the five lists
`created`, `updated`, `unchanged`, `deactivated`, `failed` (each line
`{ articleId, spodArticleId, name, variantsCreated, variantsUpdated,
variantsDeactivated, hotspot, printFrame }`), and `warnings`
(`{ code, spodArticleId, detail }`, the detail naming ids only).

| Code | What happened |
| --- | --- |
| `MIXED_PRODUCT_TYPES` | more than one product type; nothing written |
| `ARTICLE_WITHOUT_VARIANTS` | no printable variant; nothing written |
| `SPOD_ID_UNUSABLE` | an id longer than 64 characters; nothing written |
| `COLOR_NAME_DUPLICATE` | two colours of one (cut) name; nothing written |
| `CALIBRATION_UNAVAILABLE` | the partner did not answer a calibration call; nothing written |
| `TITLE_TRUNCATED` / `DESCRIPTION_TRUNCATED` | the text was cut to its column |
| `COLOR_VALUE_UNREADABLE` | no readable colour; grey `#cccccc`, variants inactive |
| `COLOR_WITHOUT_IMAGE` | no listing image could be stored; variants inactive |
| `IMAGE_DOWNLOAD_FAILED` | one listing image left out; the next run asks again |
| `MOCKUP_UNAVAILABLE` | no storable front mockup; variants inactive, a stored one kept |
| `CALIBRATION_FAILED` | no clean measurement; no frame, no hotspot, inactive; the detail names each hotspot's reason (`FULL_FRONT: DISTORTED_PRINT, …`) or `NO_FRONT_VIEW` / `NO_PRINTABLE_HOTSPOT` |
| `SIZE_CHART_UNAVAILABLE` | no storable size chart; a stored one kept |
| `DEFAULT_VARIANT_REPLACED` | the default went inactive; the first active variant took over |
| `EXAMPLE_IMAGE_REPLACED` | the article's list picture changed |
| `ARTICLE_LEFT_WITHOUT_ACTIVE_VARIANT` | every variant went inactive, so did the article |
| `ARTICLE_REAPPEARED` | a missing article is listed again; it stays inactive |

The first five also put the article into `failed`.

### The sync decisions

The nine decisions of the Kotlin guide hold unchanged; their full reasoning is
in [The sync decisions](https://github.com/bitstorm-tech/voenix-shop-kotlin/blob/kotlin-backend/docs/dev/backend/packages/article-package.md#the-sync-decisions).

1. **One backoffice article is one shop article, synced, never typed.** No
   create route, no variant editor, no picture upload; a run is started by hand
   per destination.
2. **Two owners per article** — see [SPOD articles](#spod-articles). The short
   description is the same text truncated. Deviation from the Kotlin guide
   (2026-11-03, Joe): the purchase price moved to the sync's half, because it
   is the partner's number; the sales price stays the shop's.
3. **Orders stay `oneTimeItems`.** The customer's design is per order;
   production keeps its upload → create `NEW` → confirm protocol.
4. **Identity is (destination, environment, backoffice article id)**; variants
   are matched by the product triple, never by the partner's variant id.
   Nothing is deleted on disappearance; `DELETE` is the manual retirement.
5. **The client is shared, the modules stay layered**: `SpodClient` in `spod`,
   the sync in `article`, the trigger in `production` (`production → article`).
6. **Undocumented formats degrade, they never fail the run**: unreadable data
   makes a variant inactive with a warning; only a complete listing may sweep;
   an unanswered calibration or a duplicate colour name skips one article.
7. **The print frame and its mask are measured differentially from complementary
   SPOD preview pairs.** The API answers neither where a hotspot lies on a mockup
   nor which part of a design a product shows. The run uploads two complementary
   4000 × 4000 paletted PNGs (`calibration-1x1-a.png` and `calibration-1x1-b.png`,
   where `B = 255 - A` at every pixel) once per run — sized at the partner's
   maximum dimension so high-DPI non-apparel items (such as the 19 × 19 cm heart
   puzzle) and large posters (60 × 60 cm) accept them. Per product type it reads
   the front view, asks which front hotspots accept the design, picks an
   available colour on that view (falling back to another colour if the first has
   no front mockup), renders both `A` and `B` at 1000 × 1000 per candidate
   hotspot, downloads both as PNG (`,mediaType=png`), and compares `B - A` in
   `measurePrintFrame`:
   - **No blank mockup from `/views` is needed for calibration.** Both previews
     come from the same `/previews` renderer, so every unprinted pixel cancels to
     zero (`B - A = 0`) regardless of garment colour (even green or magenta),
     surface texture, or differing RGB bytes on transparent pixels (premultiplied
     alpha).
   - **The mask** comes from `max(max(B.r - A.r, 0), |A.g - B.g|, |A.b - B.b|) > 12`
     with isolated 1-pixel speckles dropped. Because `A.r = 0` and `B.r = 255`
     everywhere with no internal edges, additive specular highlights cancel out
     and dark folds stay fully opaque in the interior; only boundary pixels get
     anti-aliased alpha scaled to their local interior signal. Stored in
     `print_mask` as a 256 × 256 16-colour PNG data URL (~2 kB), or `NULL` when
     the whole frame lies inside the picture and at least 97 % is visible.
   - **The frame** is decoded from a 13-unit self-synchronizing alternating
     binary ruler (`[1, 1, 2, 2, 2, 1, 1, 2, 1]`) encoded in green (X) and blue
     (Y). Zero-crossings of `A.g - B.g` and `A.b - B.b` are invariant to
     shading, coloured lighting, specular glare, and gamma; matching any ≥ 2
     visible crossings and refining by OLS recovers the full 1:1 design frame
     even when cutouts (camera holes, zippers) or all-over cropping hide large
     parts of the design. A 2D verification gate rejects strongly rotated or
     warped mockups (`DISTORTED_PRINT`). Edges round half up to two decimals;
     full-bleed frames may overhang `[0 %, 100 %]` within the V39 bounds
     (`[-300 %, 400 %]`, overlapping `[0 %, 100 %]`), else `OUTSIDE_PICTURE`.
   - **The hotspot that shows the most pixels of the design wins**, ties go to
     the first hotspot.
   - **Print-area sizing and back-only products (`GET /productTypes/{id}`):**
     Before uploading calibration designs for a product type, the run reads
     `GET /productTypes/{id}` (best-effort, cached per run) for `printAreas`
     (physical width/height per view) and `printType[].dpi`. Sizing the
     calibration pair directly to the print area's aspect ratio and
     `max(4000, ceil(maxInch * dpi * 1.05))` (up to `8000 px`) satisfies both
     high-DPI / large-format items (such as the 60 × 60 cm poster at 200 DPI →
     `5000 × 5000` or the 28 × 19 cm puzzle at 400 DPI → `4750 × 3250`) and
     aspect-strict items (framed posters, bumper stickers) on the first call,
     skipping the `2:1`/`1:2` rectangular probes. When metadata is absent or
     rejected, calibration falls back to the `4000 × 4000` baseline plus
     probing. For products with no `…_FRONT` hotspot (such as a dog t-shirt
     printed only on `FULL_BACK`), calibration and mockup selection fall back
     to the first view that has any hotspot, and order submission maps
     `*_BACK` / `BACK_COLLAR` hotspots to `view: 'BACK'`.
   - **Arbitrary non-square print areas are discovered automatically** without
     a fixed aspect-ratio enum; only the measured frame's `widthPct` and
     `heightPct` (and `print_mask`) are stored. After the baseline picks the
     winning hotspot:
     - *Masked / clipped all-over (`mask !== null`)*: if the visible mask's
       bounding box differs from the measured design's ratio by more than 5 %
       (for example a `1:2` phone case), the winning hotspot is re-measured
       once at that visible aspect ratio and kept when coverage stays ≥ 95 %
       with a tighter fill or more visible pixels.
     - *Unmasked rectangular hotspot (`mask === null`)*: when the initial
       measurement came from `1:1` fallback (no `printAreas`), the run probes
       `2:1`/`3:1` or `1:2`/`1:3`, derives the exact `targetRatio`, and
       measures once at that ratio. Any refused or unmeasurable probe falls
       back cleanly to the best valid measurement so far.
     - *Frontend & AI generation*: the editor derives `printArea.aspectRatio`
       from `widthPct / heightPct` (SPOD mockups are square `1000 × 1000`) and
       `composeImage` caps the longest edge at `4000 px` (order submission
       upscales via `printImagePng` when a large/high-DPI product type requires
       more than `4096 px`). AI image generation (`printFormats`) stays
       decoupled at `1:1` for now.

   Per product type, per run, stateless; only answers are cached.
   A measurement failure writes no frame (`CALIBRATION_FAILED`); a transport
   failure skips the article (`CALIBRATION_UNAVAILABLE`). The front view is the
   view carrying a `…_FRONT` hotspot, never matched by its name: `views` names
   the sides in the account's language (`Vorne`, `Hinten`), and matching
   `FRONT` made every product type fail with `NO_FRONT_VIEW`. The preview of
   that view is the image with the view's `viewId`.

   *What this replaced, and why.* Until November 2026 the calibration uploaded a
   single 2000 × 2000 magenta square with a 50 % green centre marker and compared
   one preview against the blank mockup from `/views` using a fixed `±24` RGB
   tolerance and a 97 % fill check on the centre marker. That failed on:
   (1) high-DPI non-apparel and large posters (`Poster 60×60 cm`, `Herz-Puzzle 19×19 cm`)
   whose hotspots rejected 2000 px designs; (2) realistic product shading, folds,
   and glossy specular highlights that pushed pixels outside `±24`; (3) green or
   magenta garments that collided with the calibration colours; (4) shaped
   products with cutouts, zippers, or narrow crops that clipped the 50 % marker;
   (5) full-bleed all-over frames extending slightly past the mockup border;
   (6) articles whose first colour lacked a front mockup; and (7) non-square
   print areas (such as a 3:2 pencil case or a portrait tee/phone case) where a
   `1:1`-only measurement inscribed a square inside a wider or taller print box.
   Rejected: `printAreas` of `GET /productTypes/{id}` (millimetres without
   mockup coordinates or shape); the order's custom `position` (undocumented
   units, cannot be previewed); amplitude-only colour gradients (distorted by
   gamma and non-uniform RGB shading); a closed format enum for SPOD aspect
   ratios (SPOD print areas have arbitrary physical widths and heights).

   One thing stays open: the partner's documentation lists ten hotspot names
   for an order, and `OVERALL_FRONT` is not among them, although `views`, the
   hotspot check, and `previews` answer and accept it. No order has been placed
   with it yet.
8. **The name tripwire keeps firing on partner renames** (production's
   decision); accepted until the three ids are snapshotted.
9. **Appearances and images are tables of their own**: a colour is a row, a
   `MOCKUP` is not a `LISTING` image, and a colour is sellable only with both a
   mockup and at least one listing image.

## Validation and normalization

| Field | Rule and message |
| --- | --- |
| `name` (category, subcategory) | required after trimming (`Name is required`); at most 200 |
| `description` | optional, at most 1000 after trimming; blank → `null` |
| `active` (category, subcategory) | defaults to `true` |
| `categoryId` (subcategory) | `CategoryId is required`, `CategoryId must be positive` |
| `exampleImageFilename` | trimmed, blank → `null`; existence checked while saving |
| `sourceId`, `targetId` | `SourceId is required`, `… must be positive`, `TargetId must be different from SourceId` |
| `name`, `descriptionShort`, `descriptionLong` (mug) | required; at most 255 / 1000 / 5000 |
| `supplierArticleName`, `supplierArticleNumber` | optional, at most 255 |
| `categoryId`, `subcategoryId`, `supplierId` | optional, positive; `SubcategoryId requires CategoryId` |
| `printAspectRatio` (mug) | optional, trimmed, `16:9` when absent; `PrintAspectRatio must be one of 16:9, 1:1` |
| `mugDetails.*Mm` | `HeightMm must be greater than zero` (an omitted one is `0` and fails); the document-format ones only when present |
| `mugVariants[i]` | `name`, `insideColorCode`, `outsideColorCode` required, at most 255; `id` positive |
| `mugVariants` | `Exactly one variant must be marked as default`, `Variant ids must be unique` |
| `active` (mug) | defaults to `false`; when `true`: complete mug details, an active variant, a category |
| `mugVariants[i].active` | defaults to `false`, so a silent array cannot make a mug visible |
| `defaultVariantId` (SPOD) | optional, positive |
| `active` (SPOD) | defaults to `false`; when `true`: a default variant and a category |
| `price` | the pricing module's `priceInput`, reported under `price.*`; absent or `null` keeps the stored price |

The rules about the whole body (activation, default count, the subcategory
pair) are a `superRefine` and run once every field has the right JSON type.
The service never validates again.

## The database schema

The tables are the Kotlin ones, migrated by
[`V13`](../../../../backend/migrations/V13__create_articles.sql),
[`V19`](../../../../backend/migrations/V19__article_print_aspect_ratio.sql)
(the mug's `print_aspect_ratio`),
[`V20`](../../../../backend/migrations/V20__create_article_tshirts.sql)/
[`V26`](../../../../backend/migrations/V26__article_tshirt_variant_deferred_unique.sql)/
[`V27`](../../../../backend/migrations/V27__article_tshirts_spod_sync.sql)
(the t-shirt tables, dropped again by V29), and
[`V29`](../../../../backend/migrations/V29__article_spod_articles.sql)
(`article_spod_articles`, `_appearances`, `_variants`, `_images`), and
[`V36`](../../../../backend/migrations/V36__article_spod_size_measurements.sql)
(`article_spod_size_measurements`: one row per article, SPOD size id, and
letter, `value_mm numeric(6,1)`, cascading with the article; the size label
stays on the variants), and
[`V37`](../../../../backend/migrations/V37__article_spod_print_mask.sql)
(`article_spod_articles.print_mask`, the visible part of the frame as a data
URL, allowed only next to a frame), and
[`V39`](../../../../backend/migrations/V39__article_spod_print_frame_overhang.sql)
(relaxes `ck_article_spod_articles_print_frame` so full-bleed all-over frames
may overhang `[0 %, 100 %]` while overlapping the mockup). `schema.ts` declares thirteen: `article_types` (the per-type ordering anchor and identity
target, `MUG` and `SPOD`), `article_category_ordering` (the one-row category
anchor), the two identity registries (`article_identities`,
`article_variant_identities`, which never gain a column), the two category
tables, the two mug tables, and the five SPOD tables. `numeric(5,2)` frame
columns read as strings and are parsed once (`printFrameOf`), and so does
`value_mm` (`sizeMeasurements` in `spod-article/repository.ts`).
`article_spod_articles.spod_destination_id` references `production_destinations`,
which the production module owns; this module never queries it.

Every invariant PostgreSQL can express lives there; there are no triggers. The
cross-row rules (one default, an active article has an active variant, dense
positions) live in the single write path.

## Concurrency: the ordering locks and the deferred unique rule

**A lock, not a lookup.** Under `READ COMMITTED` two creates would read the
same `max(position)`. So every position writer first locks its sequence's
anchor and only then reads: the row of `article_category_ordering` for
categories, the category row for its subcategories, and the `article_types`
row for an article type (`lockCategoryOrdering`, `lockCategories`,
`lockArticleType` in `persistence.ts`).

**One lock order.** An article write takes the type anchor (only when it
decides a position), then the category row, then the article row. Every
writer of several category rows takes them in ascending id order, one
statement at a time — the subcategory move, the category reorder, and the
category delete compaction alike (the latter two lock all category rows after
reading the order they decide from). A subcategory write reads the subcategory
before it knows which categories to lock; if it moved in between, the
transaction is rolled back and retried (up to three times) instead of taking
one more lock. Holding the category row also means it cannot disappear, so a
missing one is the `categoryId` field error and `23503` can only mean the one
remaining relationship.

**The deferred unique rule.** `UNIQUE (position)` is `DEFERRABLE INITIALLY
DEFERRED`, so a reorder rewrites the sequence in one phase (only rows whose
place changes) and PostgreSQL checks the result at COMMIT.

**How the two `23505`s stay apart** — by where `executePostgresWrite` sits:

| Write | Placement | Declared outcome |
| --- | --- | --- |
| category / subcategory create | inside the transaction, around the insert | name conflict |
| category update | around the transaction (it touches no position) | name conflict |
| subcategory update | inside, around the update and compaction | name conflict, `23503` → `inUse` |
| mug create / update | inside, around the article statements | `23503` → `supplierNotFound` |
| every reorder | around the whole transaction | position conflict at COMMIT |
| category / subcategory delete | around the whole transaction | `23001`/`23503` → in use |

A create's commit-time `23505` is not mapped: under the anchor it cannot
happen, so it is a broken invariant and a `500`. (`executePostgresWrite`
inside a transaction catches the statement's error; the transaction is aborted
by then, and its `COMMIT` rolls back.)

**Every reorder checks for gaps first** (`moveInSequence`): a stored sequence
that is not `1..n` is refused with `409` before anything is written, because a
rewrite would silently repair it. Both `409` sources — a gap, or a position a
writer outside the anchor changed — are retryable and leave nothing behind.

## What the port changed

- **The body rules run after the field types.** The activation and
  default-count rules and `SubcategoryId requires CategoryId` run only when
  every field has its JSON type, so a body that also misses a required field
  reports the field errors only; a non-integer id is Zod's type message, where
  Kotlin failed to parse the body.
- **Unknown properties are refused** (`400 … Unknown property`). The Kotlin
  SPOD update ignored keys such as `name` or `printFrame`; the frontend sends
  only the five shop-owned fields, so nothing changes for it.
- **`POST /api/admin/articles/spod` answers `404`**, not `405`: Hono answers an
  unrouted method like an unknown path.
- **Timestamps** (`syncedAt`, `missingSince`, the report's) carry milliseconds
  (`Date.toJSON`); Kotlin printed `Instant.toString()`.
- **The print-frame measurement uses sharp** (`ensureAlpha().raw()`) instead of
  ImageIO; percentages are computed in integer hundredths, so `left + width`
  stays exact without `BigDecimal`. The rule itself was replaced after the port
  (decision 7).

## Tests

All tests run against the Testcontainer database with the real pricing,
supplier, and image modules (images in a temporary directory); the partner is a
stubbed `fetch` behind the real `createSpodClient`.

| File | What it states |
| --- | --- |
| `validation.test.ts` | every request schema once: field names, messages, defaults, normalization, unknown properties |
| `category/routes.test.ts` | one happy path per category and subcategory route; name conflicts, in-use deletes, the two `categoryId` field errors, `404`/`400` messages, the reorder answers, the example image upload and its cleanup |
| `category/service.test.ts` | concurrent case-variant creates (one conflict), concurrent creates and reorders stay dense, a gapped sequence is refused, a rotation outside the anchor loses at COMMIT, two cross-category moves under the ascending lock order |
| `mug/routes.test.ts` | one happy path per mug route; the field errors of the write path, the list with supplier names, the reorder answer and its `409`, the pre-upload |
| `mug/service.test.ts` | the price joins the transaction (a rejected article leaves no price, identity, or variant identity; a rejected price creates no article; the price is rewritten in place and deleted with the mug), the variant diff with default swap and file cleanup (a shared file survives), concurrent writers stay dense, an outsider's rotation makes the reorder `409` |
| `spod-article/routes.test.ts` | the five admin routes, the four write-path activation refusals, the measurements of every variant size with the lowest-id label, the delete with all three kinds of file and the measurements, the reorder and its `409`, no create and no t-shirt paths |
| `storefront.test.ts` | the visibility matrix over mugs, SPOD articles, and the navigation; the whole public documents; the discounted price; no partner ids in the SPOD answer; the measurements of active sizes only; the empty catalog |
| `catalog.test.ts` | `find`: purchasable, inactive, draft without price, discounted, unknown and cross-article pairs, the SPOD triple and hotspot, an unmeasured SPOD article; `printFormats` |
| `spod-article/calibration.test.ts` | `measurePrintFrame` and `dimensionsForAspectRatio` on drawn preview pairs: flat square and non-square (`3:2`, `2:1`) frames, dark shading + warm lighting + glare + gamma 2.2, neon green/magenta garments, half-up edges ending at 100, glossy round product with mask, shaped cutouts (phone case camera hole, zip hoodie, visible bounds), full-bleed overhang past the mockup edges, `DISTORTED_PRINT` (25° rotation and aspect-ratio mismatch), `NO_PRINT_AREA`, `UNREADABLE_IMAGE`, and the 4000 px calibration design pairs |
| `spod-article/sync.test.ts` | whole runs: the first run, the identical re-run without downloads, the overwrite that keeps the shop's half and re-measures the frame, the sweep and the reappearance, the other environment, five malformed or refused listings, the round all-over hotspot that beats the square inside it and stores its mask without extra probes, automatic landscape (`3:2`) and portrait (`3:4`) rectangular discovery, tall `OVERALL_FRONT` phone case refinement, `CALIBRATION_FAILED` and `CALIBRATION_UNAVAILABLE`, degraded colours and images, skipped articles, the size measurements (sold sizes only, unchanged without a rewrite, a changed value, unanswered, without `sizes`, the normalization), `busy` |

`test-fixtures.ts` holds what they share (the wired module, SQL seeds for VAT,
suppliers, destinations, categories, synced SPOD articles, and the drawn
calibration previews); production code never imports it.
