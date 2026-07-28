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

- Dense-sequence validation for the two taxonomy reorders. The mug reorder
  refuses a gapped sequence with `409` (T7, legacy behavior); the category and
  subcategory reorders of T3/T4 silently rewrite one dense instead. Aligning
  them is a small, behavior-visible change outside T7's scope (owner: council,
  candidate for T10).

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

### 2026-07-27 — T5 implementation decisions (mug write slice)

Decisions taken where the approved plan left room. None of them changes an
approved contract or deviation.

- **Three locks, always in the same order.** A mug write takes the
  `article_types` anchor (only when it decides a position), then the category
  row, then the mug row. The order is not free: the subcategory slice holds a
  category row and then touches referencing `article_mugs` rows through its
  composite foreign key, so a mug write that held its own row while asking for a
  category would close a deadlock cycle. Taking the category first — which is
  possible, because the target category comes from the request and not from the
  stored row — keeps both slices on the same order.
- **The supplier is the only foreign key left, so `23503` is unambiguous.**
  Category and subcategory are pre-checked under the category lock and answer
  with their own field errors; identity and price rows are minted by the same
  transaction. That leaves exactly one relationship a client can get wrong,
  which is what lets the mug writes declare `SupplierNotFound` for SQL state
  `23503` without a `SupplierReader`. Ticket T6 may still add the reader for its
  list projection, but the write path does not need it.
- **`active` requires a price in the write path, not in the validator.** The
  other three activation rules are facts about the request and stay field rules.
  Whether a price exists can be a fact about the *stored* article, because an
  update that omits `price` keeps it, so this rule lives in the one place that
  knows both — and answers a `400` on `price` before the CHECK constraint could
  turn it into a `500`.
- **No `409` on any mug route.** Mugs have no unique name, and under the type
  anchor a position cannot collide, so the write results contain no conflict at
  all and the routes treat one as a broken invariant rather than a client
  answer. This is the T3 rule applied to a slice where it removes the outcome
  entirely.
- **Variant writes clear the default flag first.** The partial unique index
  allows one default per article and is checked per statement, so a swap of the
  default between two variants would collide halfway through. The diff therefore
  deletes what left, clears `is_default` on everything that stays, and only then
  writes the submitted flags.
- **Nested field errors use the path of the request body.** A rejected
  measurement is `mugDetails.heightMm`, a rejected variant name is
  `mugVariants[0].name`, and the field errors of the embedded price are prefixed
  with `price.`. The legacy validator used C# member names (`MugVariants[0].Name`);
  the message keeps its wording, the key becomes the JSON path.
- **Three rules added to the legacy matrix.** Reference ids must be positive
  (the convention of every other input in this backend), the variant array may
  not address the same variant twice (the diff would otherwise apply one entry
  twice), and an active mug needs a category (approved deviation, now enforced
  where it is decidable).
- **`StoredMug` carries the price id next to the article.** No article contract
  has a price id, and the price is calculated outside the transaction, so
  persistence answers with the reference and the service resolves it through
  `PriceCatalog.find`. The read slice will resolve a whole list the same way,
  with one price query.
- **The taxonomy tests stopped writing mug rows by hand.**
  `ArticleTestSchema.seedMugUsing` (raw SQL, fixed id 1) is gone: the subcategory
  in-use test now creates its article through the mug route, so what makes a
  subcategory "in use" is a real article.

### 2026-07-27 — T6 implementation decisions (mug admin read slice)

Decisions taken where the approved plan left room. None of them changes an
approved contract or deviation.

- **The list is the module's one second representation.**
  `MugArticleListItem` is not a smaller `MugArticle`: it spells out what a mug
  only *references* — the names of category, subcategory, and supplier — and
  leaves out the descriptions, measurements, variants, and the calculated price
  that the overview table does not display. That is the difference the guide
  asks for before a second representation may exist; answering the list with
  the full representation would mean reading every variant and recalculating
  every price for a screen that shows none of them.
- **Persistence answers the list without the supplier name.** The repository
  builds the rows and leaves `supplierName` at `null`, exactly as it leaves the
  price of a single mug to the service (`StoredMug`). The one label that lives
  in another module is filled in by the one component that may talk to it.
- **A default variant without a picture does not hide another variant's.** The
  legacy list chose the example image among the variants *that have one*,
  preferring the default and otherwise taking the oldest. That refinement of
  "default, else first by id" is preserved, because a mug whose default variant
  has no picture would otherwise look pictureless in the overview while a
  picture exists.
- **Four statements and two capability calls, whatever the page size.** The
  list runs one query for the mugs, one for the variants of all of them, and
  one per taxonomy level for the distinct ids referenced, plus exactly one
  `SupplierReader.find`. The detail adds exactly one `PriceCatalog.find`, and a
  mug without a price asks for none. A statement-counting data source in the
  integration test proves the constant: listing three mugs runs the same SQL as
  listing one.
- **Response shapes are locked as whole documents.** The list and the detail
  are compared against the documented example bodies as complete JSON, not
  field by field, because that is what catches a field which reappears —
  `priceId` and `articleType` being the two this migration dropped.
- **Reading needs no CSRF token and answers the two known errors.** `GET` sits
  in the same admin subtree as the writes, so authentication and the `ADMIN`
  role are checked before the id is bound; an invalid id is
  `400 Invalid article id` and an unknown one reuses the route's
  `404 Article not found`.
- **`installArticleModule` reached its planned signature.**
  `(database, images, prices, suppliers)`. The composition root stops
  discarding the `SupplierReader` that `installSupplierModule` has returned
  since T2, and `article` depends on `supplier` and re-exports it, because the
  public installation function names the capability.

### 2026-07-28 — T7 implementation decisions (mug reorder and ordering concurrency)

Decisions taken where the approved plan left room. None of them changes an
approved contract or deviation.

- **The dense-sequence check stays, and only the mug reorder has it.** Under the
  type anchor the reorder verifies that the stored positions are `1..n` and
  answers `409` without writing when they are not — the legacy
  `ValidateDenseGlobalSequence` rule, which the approved deviation list keeps
  ("dense sequence validation and 409 conflict semantics stay"). The reason it
  is not redundant next to a rewrite that produces a dense list anyway: a
  rewrite would *repair* a broken sequence silently, so every row a client sees
  would move although it asked to move one. A gap can only come from a writer
  that bypassed the anchor, and refusing the move leaves the evidence in place.
  The taxonomy reorders of T3 and T4 do not have this check; they were written
  before the rule was implemented anywhere. That difference is recorded as open
  work rather than fixed here, because T7's scope is the mug slice.
- **The mug routes gained their first `409`.** T5 recorded that no mug route can
  answer a conflict, and that stays true for create, update, and delete — their
  `respondFailure` still treats one as a broken invariant. The reorder maps its
  own conflict instead, with the stable per-route message
  `Article order changed concurrently, please retry`, following the taxonomy
  wording. One route, one `409` meaning.
- **The reorder answers the list representation.** It returns
  `MugArticleListItem[]`, resolved through the same single `SupplierReader.find`
  the list uses, because the client that reorders is the one that renders the
  overview table. The service therefore has one place that labels list rows and
  two callers.
- **Both conflict sources map to the same answer through different mechanisms.**
  A gap is found before any statement writes; a position rewritten outside the
  anchor is found by the deferred unique rule at COMMIT, which is why
  `executePostgresWrite` wraps the *whole* transaction here (the T3 placement
  rule). Neither leaves anything behind, so the client's reaction is the same.
- **`/order` is registered before `/{id}`.** Ktor prefers a literal segment over
  a parameter regardless of registration order, but the file reads in the order
  the routes resolve, and a route test proves that a reorder never reaches the
  item routes.

### 2026-07-28 — T8 implementation decisions (public storefront endpoints)

Decisions taken where the approved plan left room. None of them changes an
approved contract or deviation.

- **The storefront is its own slice, from the route down to the repository.**
  `PublicMugRoutes` installs `/api/articles/mugs` and
  `/api/articles/mugs/categories` outside the `authenticate` block that
  `MugArticleRoutes` wraps everything in — anonymous access is then not a rule a
  handler applies but the absence of the admin subtree around it. Below it,
  `PublicMugOperations`, `PublicMugService`, and `PublicMugRepository` are
  separate from the admin ones rather than three more methods on them. The first
  attempt did add them to `MugArticleOperations`/`MugArticleService`/
  `ArticleMugRepository`, and Detekt's `TooManyFunctions` refused both the class
  and the file — correctly: the storefront service needs neither the image
  storage nor the supplier capability, it opens no transaction that writes, and
  it answers a different question (what a customer may see, not what is stored).
  The shared piece is `ResultRow.toMugDetails()`, which moved next to
  `ArticleMugs` because both repositories build that value from the same nine
  columns.
- **One visibility rule, written once.** `visibleMugsWithTaxonomy()` (the inner
  join on the category, the left join on the subcategory) and
  `visibleMugCondition()` are shared by both public queries. If the list and the
  navigation could disagree, a customer could follow a category into an empty
  list. The legacy backend had the same predicate twice, once per service.
- **The public representation makes the invariant visible.** `PublicMug` has a
  non-nullable `categoryId`, `mugDetails`, and `price`, although `MugArticle`
  allows `null` in all three. Only active mugs reach this list and the database
  refuses an active mug without a category, its details, and a price — so the
  legacy `price: 0` is not merely unused, there is no case left that could
  produce it. A missing price row is a broken invariant (`checkNotNull`), not a
  fallback.
- **`StoredPublicMug` carries the price id, like `StoredMug`.** The amount is
  calculated by the pricing module from the current VAT entries, so persistence
  answers with the reference and the service resolves the whole page in one
  `PriceCatalog.find`. That keeps the T6 division of labor and is what allows
  `price` to be a plain `Int` in the representation.
- **The id tie-breaker of the display order is not observable.** The list is
  ordered `position ASC, id ASC` like the admin one, but `article_mugs.position`
  is unique, so two mugs can never share a position. The legacy test dropped its
  position index to prove the tie-break; here the order test swaps two positions
  instead, and the `id` clause stays as the deterministic backstop it is.
- **The taxonomy route answers one `DISTINCT` query.** A category with ten mugs
  is one row per subcategory it uses, and a mug without a subcategory
  contributes the left join's `NULL`, which the grouping skips. The row order is
  the display order of both levels, so nothing is sorted in Kotlin.
- **The counting doubles moved out of the read test.** `CountingDataSource` and
  `CountingPriceCatalog` are now shared test types of the article module, used
  by the admin read test and the public one. Copying the statement recorder into
  the second test would have made "no N+1" two implementations of one check.

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
| Article response field `articleType` | `AdminArticleDto.cs` | Dropped; the route path names the type | proposed deviation | T5 implementation decision 2026-07-27 | Vue frontend adaptation |
| Article list item field `articleType` | `AdminArticleListItemDto.cs` | Dropped, like the detail field: the route only serves mugs | proposed deviation | T6 implementation decision 2026-07-27 | Vue frontend adaptation |
| Admin article list is global across article types | `AdminArticleService.FindAllAdminArticleListItemsAsync` | `GET /api/admin/articles/mugs` lists one type, ordered by its per-type position | proposed deviation | Follows the approved per-type tables and positions | Vue frontend adaptation |
| Foreign or unknown variant id: `400` with a message | `AdminArticleService.ApplyMugVariants` | `400` with a `mugVariants` field error | proposed deviation | T5 implementation decision 2026-07-27 | none |
| Example image file names may be PNG, JPEG, or WebP | `AdminArticleService.ExampleImageFilenameRegex` | UUID + `.webp` only, because one pipeline always converts | proposed deviation | Follows the approved single image pipeline | none |
| Variant example image errors carry the file name in the message | `AdminArticleService.ValidateExampleImageFilename` | Field error on `mugVariants[i].exampleImageFilename` | proposed deviation | T5 implementation decision 2026-07-27 | Vue frontend adaptation |
| Public mug may answer `mugDetails: null` and `price: 0` | `ArticleService.cs:109`, `MugArticleDto.cs` | Both always present, and `categoryId` too: only active mugs are listed, and an active mug has a category, its details, and a price | proposed deviation | Follows the approved active-requires-price/details/category rules | Vue frontend adaptation |
| Public categories route is `GET /api/articles/categories` | `ArticleController.cs` | `GET /api/articles/mugs/categories` — the path names the type instead of a map key | proposed deviation | Approved by Joe 2026-07-27 (same decision as the dropped type map) | Vue frontend adaptation |
| Production reads mutable article master data | `PdfService.cs` | Order snapshots production fields at checkout | proposed deviation (Order scope) | Approved by Joe 2026-07-27 | Order migration |

## Migration retrospective

Pending — filled before completion is reported.

| Finding | Evidence | Scope | Earlier signal or check | Destination and action |
| --- | --- | --- | --- | --- |
