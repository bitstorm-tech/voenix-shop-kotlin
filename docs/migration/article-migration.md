# Article module migration

The workflow lives in the `migrate-dotnet-feature` skill
(`.agents/skills/migrate-dotnet-feature/SKILL.md`); the migration rules live in
[`module-migration-guide.md`](module-migration-guide.md). This record keeps
only module-specific facts, decisions, deviations, and history. Explicit
decisions recorded here override defaults in the guide.

This migration is planned and verified by the migration council
(`.agents/skills/migration-council/SKILL.md`).

## Status

`implementation`

Tickets (GitHub issues, blocked-by chained): T1 #14, T2 #15, T3 #16, T4 #17,
T5 #18, T6 #19, T7 #20, T8 #21, T9 #22, T10 #23. T1/T2/T3 have no blockers
and can run first (T1 and T2 in parallel to T3); then T4 → T5 → {T6, T7, T8}
→ T9 → T10.

## Task parameters

Target module:

`Article`

Source feature:

`../voenix-shop/backend/Voenix.Api/Features/Article` (plus the article-owned
parts of `Features/Pricing` integration and the two example-image pipelines)

Target package:

`backend/modules/article/src/shop/voenix/article`

Analysis checkpoint:

`wait-for-approval` (council workflow: Joe approves the consolidated plan)

Known consumers:

- Vue admin frontend (article/category/subcategory CRUD, reorder, pricing
  editor, image uploads)
- Vue storefront (public mug list, public categories map)
- Cart, Checkout, Order (.NET, not yet migrated — `cart_items.article_id`,
  `cart_items.variant_id`, `order_items` snapshot, PDF production data)
- Kotlin `production` module: `ProductionItem` contract needs supplier data,
  article/variant names, and mug layout measurements
- Kotlin `supplier` module: `SupplierDeleteResult.InUse` becomes reachable once
  articles reference suppliers (see `supplier-post-migration.md`)
- Kotlin `pricing` module: embedded price in the admin article contract (see
  `pricing-post-migration.md`)

Approved deviations from current behavior:

- One table per article type: `articles` + `article_mug_details` merge into
  `article_mugs`; future types get their own tables (e.g. `article_posters`);
  `article_mug_variants` is duplicated per type when needed. No generic
  article data table.
- Display position is dense and unique per article type (per table), no longer
  global across all articles.
- Reorder uses `UNIQUE ... DEFERRABLE INITIALLY DEFERRED` position constraints
  and a single-phase rewrite instead of the legacy two-phase rewrite; dense
  sequence validation and 409 conflict semantics stay.
- An active article must have a price. This removes the legacy inconsistency
  where the storefront showed `price: 0` while the cart rejected the article.
- One image pipeline for both variant example images and subcategory example
  images, via the existing image module infrastructure, always converted to
  WebP, filenames UUID with dashes.
- Categories/subcategories stay type-agnostic shared tables. The public
  categories endpoint may be simplified; the Vue frontend adaptation is
  recorded as deferred work.
- Every invariant that Postgres can enforce moves into the database.

Explicitly deferred work:

- Adapt the Vue frontend to the changed contracts (owner: Joe / frontend
  follow-up). Complete itemized list lives in
  `docs/migration/article-post-migration.md` (created in ticket T10).
- Orphaned example-image sweep: a cleanup job that deletes public image files
  older than a threshold that are referenced by neither
  `article_mug_variants.example_image_filename` nor
  `article_subcategories.example_image_filename`. Accepted-orphan policy per
  legacy ADR 0001 stays; the sweep is a later, separate feature (owner: Joe).
- Order migration target design: order items must snapshot supplier article
  number and mug layout measurements at checkout instead of reading mutable
  article master data at production time (decided 2026-07-27, implemented in
  the Order migration).

## Analysis deliverable

Consolidated council plan, approved by Joe on 2026-07-27. The three council
proposals (orchestrator, Opus, Codex) agreed on the architecture; conflicts
were resolved in one rebuttal round plus Joe's decisions (see decision log).

### Module cut

One compilation module `backend/modules/article`, package
`shop.voenix.article`, with sub-packages (the module exceeds Account size):
root (module handle, exported capability, shared reorder input), `taxonomy`
(categories + subcategories), `mug` (admin + public mug slices),
`persistence` (Exposed tables, repositories, ordering-lock helper).
`installArticleModule(database, images, prices, suppliers)` returns the one
public capability `ArticleCatalog` (set-in/map-out batch lookup:
variant reference → article/variant names, purchasability, gross price,
supplier id + article number, mug layout measurements for the future
`ProductionItem` adapter). Article does not depend on `production`.

Prerequisite capabilities added by this migration:

- `pricing` exports `PriceCatalog`: suspending `prepare(PriceInput)` (validate,
  resolve VAT, calculate — no DB write) and non-suspending
  `storeInTransaction` / `replaceInTransaction` / `deleteInTransaction` that
  join the caller's open Exposed transaction, plus batched
  `find(ids): Map<Long, CalculatedPrice>`. Price value types become public.
- `supplier` exports `SupplierReader.find(ids)` mirroring `CountryReader`.

### Schema (Flyway V13, one migration)

- `article_types(article_type text PK)` — seeded with `MUG`. FK target for
  the identity tables AND the ordering lock anchor: every position-writing
  transaction (create, delete compaction, reorder) first takes
  `SELECT ... FOR UPDATE` on its type row. Category ordering locks a
  singleton `article_taxonomy_state` row; subcategory ordering locks the
  parent category row(s), ascending id on moves.
- `article_identities(id identity PK, article_type FK, UNIQUE(id, article_type))`
  and `article_variant_identities(id identity PK, article_id, article_type,
  composite FK, UNIQUE(id, article_id))` — identity-only registries, zero
  business data. Review rule: these tables never gain another column. They
  give Cart/Order one FK target across article types; the composite variant
  FK makes "variant belongs to article" a database fact.
- `article_categories` / `article_subcategories` — type-agnostic, per legacy
  plus: case-insensitive unique names for BOTH levels (LOWER indexes),
  positions `UNIQUE ... DEFERRABLE INITIALLY DEFERRED` (subcategories per
  category), alternate key `(id, category_id)` for the composite FK.
- `article_mugs` — merged articles + mug details; per-type dense position
  (deferrable unique); composite FK `(subcategory_id, category_id)`;
  supplier FK `ON DELETE RESTRICT`; `price_id` FK `ON DELETE RESTRICT` +
  `UNIQUE(price_id)`; CHECKs: details all-or-none, measurements positive,
  subcategory-requires-category, `active` requires price AND details AND
  category (the category requirement is new vs legacy).
- `article_mug_variants` — per legacy; partial unique index
  `(article_id) WHERE is_default` (at most one default); identity FK +
  article FK, `ON DELETE CASCADE`.
- No triggers (Joe 2026-07-27): cross-row invariants (at least one default,
  active-needs-active-variant, density, identity completeness) live in the
  single application write path and are covered by integration tests that
  prove they are unreachable through the public API.
- No `prices.owner_kind` (Joe 2026-07-27): price ownership holds by
  construction — no article contract ever accepts a `priceId` (tested rule),
  ids are minted by `storeInTransaction`, `UNIQUE(price_id)` backstops.

### API contract

Admin (auth per shared route protection), all lists bare arrays:

- `GET/POST /api/admin/articles/mugs`, `GET/PUT/DELETE .../mugs/{id}`,
  `PUT .../mugs/order`, `POST .../mugs/variant-example-images`
- `GET/POST /api/admin/articles/categories`, `.../categories/{id}`,
  `PUT .../categories/order`
- `GET/POST /api/admin/articles/subcategories`, `.../subcategories/{id}`,
  `PUT .../subcategories/order`, `POST .../subcategories/example-images`

Public (anonymous): `GET /api/articles/mugs`,
`GET /api/articles/mugs/categories` (bare array of categories with nested
subcategories, only taxonomy used by visible mugs, no type map).

Contract decisions: shared `ReorderInput { sourceId, targetId }`; reorder
returns the full dense list; unknown ids → 404 everywhere; position is
response-only; `price` is `PriceInput` in / `CalculatedPrice` out, no
separate `priceId` field; subcategory CRUD is plain JSON with
`exampleImageFilename` (pre-upload endpoint replaces multipart;
`null` removes); public mug omits `active`, variant `active`, and all
supplier fields; subcategory 409 meanings (`in use` vs `name conflict`)
distinguished by stable `ApiError.message` values, no `code` field.
Example request/response bodies: see the Opus council proposal recorded in
ticket T5/T6 acceptance criteria.

### Images

Both example-image kinds use the pre-upload pattern (legacy ADR 0001,
extended to subcategories) through the existing `PublicImageStorage`
(already WebP + UUID-with-dashes). Folders:
`articles/mugs/variant-example-images`,
`articles/subcategory-example-images`. Save-time validation: strict
UUID-`.webp` regex + `exists()`, unchanged stored filenames exempt. Obsolete
files deleted after commit, best effort. Orphans accepted (sweep deferred).

### Concurrency

No SERIALIZABLE. Lock anchor rows (see schema) + single-phase position
rewrite; deferrable unique checks at COMMIT map to 409 via
`executePostgresWrite` (name conflicts fire at statement time, position
conflicts at commit time, so the nested 23505 mappings stay unambiguous).

### Test plan (essentials)

Legacy test suites are the behavior spec (`Voenix.Api.Tests/Features/Article`,
`.../Pricing/PriceCalculatorTests.cs`, rounding AwayFromZero). Required
integration coverage: article+price atomicity both ways (rollback leaves no
price row); concurrency per position writer; deferred-constraint 409 at
commit; full validator matrix; variant diff incl. image cleanup; public
filter matrix; supplier delete → 409 InUse; `ArticleCatalog` batch lookup
with `ProductionItem` fields; no-`priceId`-accepted contract test.

## Decision log

### 2026-07-27 — Pre-council design decisions by Joe

Joe fixed seven design points before the council briefing (listed above under
approved deviations): per-type tables, per-type positions, deferrable unique
position constraints, active-requires-price, unified WebP image pipeline,
shared type-agnostic categories with simplified public contract, and
DB-enforced invariants. The Cart/Order polymorphism question (how carts and
orders reference articles across per-type tables) is explicitly NOT deferred:
the next article type is already planned, so the council must work out this
design now.

### 2026-07-27 — Council phase 1 result and Joe's conflict decisions

All three council proposals (orchestrator, Opus, Codex) independently
converged on: one `article` module with sub-packages, thin identity
registries, per-type routes, bare arrays, deferrable unique positions with
single-phase rewrite, exclusive article-owned prices with `ON DELETE
RESTRICT`, transaction-composable pricing capability, pre-upload image flow
extended to subcategories. One rebuttal round was run on three material
conflicts; Joe decided:

- **K1 (ordering lock)** — keep it simple, but the table must never end up
  in a broken state. Resolution: one-line anchor lock on the existing
  `article_types` row (Opus conceded to Codex in rebuttal; the lock-free
  variant can leave permanent gaps in the position sequence, which would
  block reordering until manual repair). `article_types` doubles as FK
  target instead of a CHECK constraint (Opus refinement).
- **K2 (price ownership)** — Joe rejected the `prices.owner_kind`
  discriminator (Codex) as too coupling-heavy; ownership by construction
  (Opus): no article contract accepts a `priceId`, update rewrites the same
  price row in place (no id churn), `UNIQUE(price_id)` backstops. Tested as
  an explicit contract rule.
- **K3 (constraint triggers)** — no triggers (Joe agreed with the
  orchestrator/Opus): declarative constraints to the maximum, cross-row
  invariants in the single write path with API-unreachability tests.
  Commit-time trigger errors would degrade field-precise 400s to 500s.

Joe also approved the ten minor points: identity registries (with the
never-add-a-column review rule), public contract stripped of supplier fields
and `active`, `priceId` dropped from admin DTO, subcategory JSON+pre-upload
(with the orphan sweep recorded as deferred work), active-requires-category,
404 for unknown reorder ids, case-insensitive subcategory names, 409
discrimination via stable messages, order-snapshot target design recorded
for the Order migration, no price delete endpoint.

### 2026-07-27 — T3 implementation decisions (schema and category slice)

Decisions taken where the approved plan left room. None of them changes an
approved contract or deviation.

- **Ordering lock anchor.** `article_taxonomy_state` is a data-free
  single-row table (`id integer PRIMARY KEY CHECK (id = 1)`); the row is the
  lock. The single-row shape is a database rule, so the anchor cannot
  accidentally become two anchors.
- **Type proof for a type table.** `article_mugs` carries a constant
  `article_type` column (`DEFAULT 'MUG'`, `CHECK (article_type = 'MUG')`) and
  references `article_identities (id, article_type)`. Without it a mug could
  claim the identity of another article type. The identity is the parent row:
  `article_mugs`, `article_variant_identities`, and `article_mug_variants`
  cascade from it, so deleting an identity removes the whole article.
- **Column types.** Lengths follow the legacy validators rather than the
  legacy `text` columns (category/subcategory name 200, description 1000, mug
  name 255, `description_short` 1000, `description_long` 5000, supplier and
  variant text 255), so the column and the field rule state the same limit.
- **Detail completeness.** The all-or-none CHECK also requires the optional
  detail fields (`filling_quantity`, the three `document_format_*` columns) to
  be NULL when the required measurements are absent, and the
  active-requires-details half of the activation CHECK uses `height_mm` as the
  representative of the block that all-or-none keeps together.
- **Statement time versus commit time instead of constraint names.** The
  repository tells a name conflict from a position conflict by *where*
  `executePostgresWrite` sits: inside the transaction (create, update) it can
  only see the statement-time `23505` of the name index; around the whole
  transaction (reorder) it can only see the commit-time `23505` of the
  deferred position rule. A `23505` raised by the commit of a create is
  deliberately not mapped — under the ordering lock it cannot happen, so it is
  an unexpected failure rather than a client conflict.
- **Shared reorder input.** `ReorderInput { sourceId, targetId }` lives in the
  module root package and is reused by the subcategory and mug slices.
- **No `mug` sub-package yet.** The package is created together with the mug
  slice in T5; Git cannot track an empty directory, and a placeholder file
  would be code without a reason. The mug tables exist in V13 and are covered
  by `ArticleMugSchemaIntegrationTest`.

### 2026-07-27 — T4 implementation decisions (subcategory slice)

Decisions taken where the approved plan left room. None of them changes an
approved contract or deviation.

- **The category row is the ordering anchor.** Subcategory positions are dense
  per category, so the anchor of a sequence is the row that owns it:
  `lockCategoriesForOrderingInTransaction(ids)` locks the category rows one
  statement at a time in ascending id order. The lock has a second effect the
  writes depend on — while the target category is held it cannot disappear, so
  the reference to it can no longer fail and SQL state `23503` on the update
  is unambiguously the composite key of `article_mugs`.
- **A write that lost its anchor retries the whole transaction.** A write reads
  the subcategory before it knows which categories to lock, and the subcategory
  can move in between. Rather than taking one more lock — which would break the
  ascending lock order and could deadlock — the transaction rolls back and
  starts over with the category it just observed (three attempts, then a
  failure that is genuinely unreachable).
- **`categoryId` flat on both sides.** The legacy contract took a flat
  `articleCategoryId` and answered with a nested `articleCategory` object. Both
  sides now carry `categoryId`, which is the asymmetry the migration guide
  explicitly warns about; the category itself is already available from the
  category routes.
- **Two category rejections are field errors, not conflicts.** An unknown
  category and a category change while articles use the subcategory both become
  `400` with a `categoryId` field error, following the Supplier precedent for a
  missing referenced row. This keeps every route at exactly one `409` meaning,
  which is what makes the stable per-route message enough to distinguish the
  two conflicts the plan asked to keep apart. The legacy backend answered `409`
  for the blocked move.
- **A reorder across categories is a `404`.** Positions count per category, so
  the ordered list a reorder works on is the source's category; a target from
  another category is not in it and is answered exactly like an unknown id
  (legacy: `409`). No extra rule and no third `409` meaning on the route.
- **Pre-upload answers `201`.** The upload creates a file, so the pre-upload
  route answers `201 Created` with `{ "filename": … }`; an oversized body is
  `413`, which is the point of refusing it while it streams.
- **`ExampleImage` and `ExampleImageUpload` live in the module root.** Both are
  needed by T4, and the mug variant pre-upload in T5 uploads through exactly
  the same two types, so they sit next to `ReorderInput` rather than inside
  `taxonomy`.

## Deviation and uncertainty log

| Behavior or contract | Source evidence | Kotlin behavior | Classification | Approval or owner | Follow-up |
| --- | --- | --- | --- | --- | --- |
| Global article display order | `AdminArticleService.cs` reorder + ADR 0007 (legacy) | Position dense/unique per article type table | proposed deviation | Approved by Joe 2026-07-27 | none |
| Two-phase position rewrite | `AdminArticleService.RewriteDensePositionsAsync` | Single-phase rewrite with deferrable unique constraint | proposed deviation | Approved by Joe 2026-07-27 | none |
| Article without price shows `price: 0` in storefront, cart rejects it | `ArticleService.cs:109` vs `CartService.GetArticlePriceAsync` | Active article requires a price | proposed deviation | Approved by Joe 2026-07-27 | none |
| Two image pipelines (variant: original format kept; subcategory: WebP) | `VariantExampleImageStorage.cs`, `PublicImageStorageService.cs` | One pipeline, always WebP, UUID-with-dashes filenames | proposed deviation | Approved by Joe 2026-07-27 | none |
| Public categories endpoint returns `Map<articleType, Category[]>` | `ArticleController.cs` | `GET /api/articles/mugs/categories` → bare array, nested subcategories | proposed deviation | Approved by Joe 2026-07-27 | Vue frontend adaptation (deferred work) |
| Subcategory CRUD is multipart with `removeExampleImage` | `AdminArticleSubcategoryController.cs` | JSON CRUD + pre-upload endpoint; `exampleImageFilename: null` removes | proposed deviation | Approved by Joe 2026-07-27 | Vue frontend adaptation; orphan sweep deferred |
| All `{ "items": [...] }` list wrappers | legacy list DTOs | Bare JSON arrays | proposed deviation | Approved by Joe 2026-07-27 (guide rule) | Vue frontend adaptation |
| Admin mug routes at `/api/admin/articles` | `AdminArticleController.cs` | `/api/admin/articles/mugs` (typed path) | proposed deviation | Approved by Joe 2026-07-27 | Vue frontend adaptation |
| Public mug exposes supplier fields and `active` | `MugArticleDto.cs` | Removed from public contract | proposed deviation | Approved by Joe 2026-07-27 | none |
| `AdminArticleDto.priceId` next to embedded `price` | `AdminArticleDto.cs` | Dropped; `price.id` carries it | proposed deviation | Approved by Joe 2026-07-27 | supersedes wording in `pricing-post-migration.md` |
| Active article may lack a category (invisible in storefront) | `ArticleRequestValidator.cs` (no rule) | `active` requires `category_id` | proposed deviation | Approved by Joe 2026-07-27 | none |
| Reorder unknown id: 404 articles / 409 taxonomy | `AdminArticleService` vs `ArticleCategoryService` | 404 everywhere | proposed deviation | Approved by Joe 2026-07-27 | none |
| Subcategory DTO nests the full category (`articleCategory`) while the request takes a flat `articleCategoryId` | `AdminArticleSubcategoryDetailDto.cs` | Flat `categoryId` on both sides | proposed deviation | T4 implementation decision 2026-07-27 | Vue frontend adaptation |
| Reorder of subcategories from two categories: 409 order conflict | `ArticleSubcategoryService.cs:407-414` | 404, because the target is outside the ordered list of the source's category | proposed deviation | T4 implementation decision 2026-07-27 | Vue frontend adaptation |
| Category change while articles use the subcategory: 409 in use | `ArticleSubcategoryService.cs:196-203` | 400 with a `categoryId` field error; `DELETE` keeps the 409 | proposed deviation | T4 implementation decision 2026-07-27 | Vue frontend adaptation |
| Subcategory names unique case-sensitively | `ArticleSubcategoryService.cs:82-89` | Case-insensitive per category | proposed deviation | Approved by Joe 2026-07-27 | none |
| 409 discriminated by `code` field | `DomainExceptionHandler.cs:219-232` | Stable `ApiError.message` values | proposed deviation | Approved by Joe 2026-07-27 | Vue frontend adaptation |
| Price FK `ON DELETE SET NULL` | `ArticleEntityConfiguration.cs` | `ON DELETE RESTRICT`; no price delete endpoint | proposed deviation | Approved by Joe 2026-07-27 | none |
| Production reads mutable article master data | `PdfService.cs` | Order snapshots production fields at checkout | proposed deviation (Order scope) | Approved by Joe 2026-07-27 | Order migration |

## Migration retrospective

Pending — filled before completion is reported.

| Finding | Evidence | Scope | Earlier signal or check | Destination and action |
| --- | --- | --- | --- | --- |
