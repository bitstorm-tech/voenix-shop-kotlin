# Article module migration

The workflow lives in the `migrate-dotnet-feature` skill
(`.agents/skills/migrate-dotnet-feature/SKILL.md`); the migration rules live in
[`module-migration-guide.md`](module-migration-guide.md). This record keeps
only module-specific facts, decisions, deviations, and history. Explicit
decisions recorded here override defaults in the guide.

This migration is planned and verified by the migration council
(`.agents/skills/migration-council/SKILL.md`).

## Status

`complete`

All ten tickets are implemented (GitHub issues, blocked-by chained): T1 #14,
T2 #15, T3 #16, T4 #17, T5 #18, T6 #19, T7 #20, T8 #21, T9 #22, T10 #23. The
implementation order was T1/T2/T3 first (T1 and T2 in parallel to T3), then
T4 → T5 → {T6, T7, T8} → T9 → T10.

The council's phase-3 verification ran on 2026-07-28: three independent
reviews, one rebuttal round per contested finding, and three fix tickets
(#24 code, #25 code, #26 docs), each accepted with a full quality-gate run.
See the phase-3 entry in the decision log and the consolidated findings on
the pull request.

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

Resolved deferred work:

- Dense-sequence validation for the two structure reorders (categories, subcategories) — **closed in T10**.
  The category and subcategory reorders now check the stored sequence exactly
  as the mug reorder does: a gap answers `409` with the stable order-changed
  message of the route and writes nothing. One helper (`isDenseBy`) implements
  the rule for all three levels.

Explicitly deferred work:

- Adapt the Vue frontend to the changed contracts (owner: Joe / frontend
  follow-up). The complete itemized list lives in
  [`article-post-migration.md`](article-post-migration.md).
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
root (module handle, exported capability, shared reorder input), `category`
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
  singleton `article_category_ordering` row; subcategory ordering locks the
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
subcategories, only categories used by visible mugs, no type map).

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

- **Ordering lock anchor.** `article_category_ordering` is a data-free
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
  `413`, which is the point of refusing it while it streams. **Superseded on
  2026-07-30:** Joe decided that every upload rejection answers `400` with the
  message on the `file` field; see
  [`cart-migration.md`](cart-migration.md) and
  [`image-package.md`](../dev/backend/packages/image-package.md).
- **`ExampleImage` and `ExampleImageUpload` live in the module root.** Both are
  needed by T4, and the mug variant pre-upload in T5 uploads through exactly
  the same two types, so they sit next to `ReorderInput` rather than inside
  `category` (named `taxonomy` until the 2026-07-28 rename).

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
- **The category-structure tests stopped writing mug rows by hand.**
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
  one per category level for the distinct ids referenced, plus exactly one
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
  The category and subcategory reorders of T3 and T4 do not have this check; they were written
  before the rule was implemented anywhere. That difference is recorded as open
  work rather than fixed here, because T7's scope is the mug slice.
- **The mug routes gained their first `409`.** T5 recorded that no mug route can
  answer a conflict, and that stays true for create, update, and delete — their
  `respondFailure` still treats one as a broken invariant. The reorder maps its
  own conflict instead, with the stable per-route message
  `Article order changed concurrently, please retry`, following the category-route
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
- **One visibility rule, written once.** `visibleMugsWithCategories()` (the inner
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
- **The categories route answers one `DISTINCT` query.** A category with ten mugs
  is one row per subcategory it uses, and a mug without a subcategory
  contributes the left join's `NULL`, which the grouping skips. The row order is
  the display order of both levels, so nothing is sorted in Kotlin.
- **The counting doubles moved out of the read test.** `CountingDataSource` and
  `CountingPriceCatalog` are now shared test types of the article module, used
  by the admin read test and the public one. Copying the statement recorder into
  the second test would have made "no N+1" two implementations of one check.

### 2026-07-28 — T9 implementation decisions (ArticleCatalog, app wiring, supplier InUse)

Decisions taken where the approved plan left room. None of them changes an
approved contract or deviation.

- **The reference is `(articleId, variantId)`, and both halves count.** The
  identity registries mint globally unique variant ids, so the variant id alone
  would resolve. It is deliberately not enough: a cart line and an order line
  store the pair, `article_variant_identities` carries the composite foreign key
  that makes "this variant belongs to that article" a stored fact, and the
  capability keeps that rule instead of weakening it. A mismatched pair is
  therefore *unknown* — absent from the map, like a deleted article — rather
  than silently resolved to the other article's data. The article type is not
  part of the reference; it is one of the answers.
- **Five measurements, not nine.** `ProductionItem` overrides a page size, a
  print area, and a bottom margin, so `CatalogVariant` carries
  `printTemplateWidthMm`, `printTemplateHeightMm`, `documentFormatWidthMm`,
  `documentFormatHeightMm`, and `documentFormatMarginBottomMm`. Height,
  diameter, filling quantity, and the dishwasher flag describe the physical mug;
  they are storefront copy, and exporting them would hand production article
  master data it has no use for. The values stay whole millimetres — widening
  them to the `Double` fields of the PDF layout is the adapter's job, and the
  adapter belongs to the module that owns the order line. Article does not
  depend on `production`.
- **`purchasable` is one flag, not three facts.** Active article ∧ active
  variant ∧ price present is computed by the module that owns the rule. This is
  the same decision as the non-nullable `price` of the public representation
  (T8), applied to the capability: the legacy `price: 0` came from consumers
  recombining these parts themselves. `grossSalesPriceCents` is `null` when no
  price exists, never `0`.
- **The unresolvable price is an answer, not an exception.** `PublicMugService`
  treats a missing price of an active mug as a broken invariant
  (`checkNotNull`), because it answers a page its own module wrote. The
  capability answers carts and orders, where "cannot be bought right now" is a
  case the caller handles anyway, so an unresolved price yields
  `purchasable = false`. The restricted foreign key on `price_id` keeps the case
  from occurring at all.
- **`ArticleType` is a public closed enum, and the persistence literal derives
  from it.** The persistence literal (`MUG_ARTICLE_TYPE` in
  `ArticleMugRepository.kt`, formerly `ArticleMugs.ARTICLE_TYPE`) is now
  `ArticleType.MUG.name` instead of a second `"MUG"` string, so the value stored in `article_types` and the value
  a consumer switches on cannot drift apart. A new article type is a new table
  and a new branch in every consumer, so the enum can never meet a value it does
  not know.
- **One query per article type, merged into one map.** Today that is one query
  on `article_mug_variants` joined to `article_mugs`; a later type adds its own
  query and nothing about the reference or the answer changes. The batch filters
  on variant ids and matches the article half in memory, which is what makes the
  mismatched pair unknown.
- **The capability reports no `OperationResult`.** Like the other readers it
  lets a database failure surface as an exception. An empty map would tell a
  cart that its articles no longer exist.
- **The composition root discards the capability.** `installArticleModule`
  returns `ArticleCatalog`, and `Application.kt` drops it exactly as it drops
  Promotion's `PromotionCodes`, until Cart or Order binds it. `ArticleModule`
  stays `internal`: what leaves the module is the capability, not the handle.
- **The supplier `InUse` item was closed and corrected.**
  `docs/migration/supplier-post-migration.md` claimed the outcome was
  unreachable because no article table existed. It was already reachable through
  `production_destinations` (V6) and `production_jobs` (V8), which reference
  `suppliers.id` with `ON DELETE RESTRICT` — what was missing was a test.
  `ArticleSupplierRelationshipIntegrationTest` now installs both modules on one
  database and proves the `409`, the intact rows, and the body free of schema
  names, plus the release path after the article is deleted.
- **The "active article without a price" case is not tested, because it cannot
  exist.** The activation CHECK refuses it, so the three reasons for
  `purchasable = false` are covered by three articles that an admin can really
  create: an inactive but complete article, an active article with an inactive
  variant, and a draft that owns no price row.

### 2026-07-28 — T10 decisions (documentation, simplification, retrospective)

- **The three reorders now answer the same way (council decision).** The dense
  check T7 implemented for mugs was the legacy rule
  (`ValidateDenseGlobalSequence`), which the legacy backend applied to its one
  global sequence. Article positions are per sequence now — one global category
  sequence, one per category, one per article type — so applying the rule to
  every reorder is what preserves the legacy behavior rather than deviating
  from it. A gapped sequence answers `409` with the route's stable
  order-changed message and writes nothing;
  `ArticleCategoryConcurrencyIntegrationTest` and
  `ArticleSubcategoryConcurrencyIntegrationTest` prove it the way the mug test
  does, by gapping the stored positions and asserting that none of them moved.
  The check lives once, in `persistence/DensePositions.kt` (`isDenseBy`), and
  the mug-only `List<MugArticleListItem>.isDense()` of T7 is gone.
- **`asFailure` has one implementation.** T5 and T7 left the identical
  `OperationResult<*>.asFailure()` in both `MugArticleService.kt` and
  `ArticleSubcategoryService.kt`. It moved to `OperationFailures.kt` in the
  module root, next to the other things both slices share.
- **`databaseOperation` was deliberately *not* deduplicated.** The private
  `try/catch (SQLException)` helper exists once per service in Article and in
  six other modules, each with its own logger. Joe already declined moving it
  into `platform` during the Promotion retrospective (2026-07-26) and named the
  condition for revisiting it — a seventh module. Article is that module, so
  the finding is reopened in the retrospective as a decision for Joe rather
  than applied: it is an architecture default under guide rule 4, and
  deduplicating it inside Article alone would make one module differ from the
  repository-wide pattern for no behavioral gain.
- **Legacy ADR 0007 is superseded.** The legacy decision "one global article
  display order, rewritten in two phases" no longer describes the system. The
  legacy repository is read-only for this migration, so the supersession is
  recorded here and in `article-post-migration.md` rather than in the legacy
  file.
- **The record's status is `verification`, not `complete`.** T10's ticket text
  asked for `complete`; the council workflow puts a verification phase after
  implementation, and claiming completion before it would be the one thing the
  guide forbids — reporting a verification that did not run.

### 2026-07-28 — Phase-3 verification (council)

Three independent reviews of the full change set (orchestrator, Opus, Codex)
with one rebuttal round per contested finding; consolidated findings and
outcomes are published on the pull request. Joe's three special check orders
were answered unanimously:

- **A (T4/T5/T6 implementation decisions):** every classified row endorsed by
  all three reviewers; none overturned. The one decision that was overturned
  sat outside the classified set: `mugVariants[i].active` had silently flipped
  its default from legacy `false` to `true`. The council restored legacy
  parity (fix #25) instead of approving a deviation.
- **B (one-time 500 in the T5 concurrency test):** no defect in the mug write
  path — all three reviews independently closed every suspected mechanism.
  Best-fitting explanation: the shared test pool of 2 connections against 4
  concurrent writers can hit Hikari's acquisition timeout on a loaded host.
  The pool is now 8 with an explicit 10-second timeout (fix #24).
- **C (K2/K3):** pass, verified end to end. Noted without action:
  `PUT /api/admin/prices/{id}` can still edit an article-owned price directly,
  consistent with the approved plan (only the delete endpoint was removed).

Confirmed defects, all fixed and re-verified (tickets #24, #25, #26; commits
`8647d61`, `f461ac0`, `d3f0270`):

- **Cross-slice lock-order deadlock (major).** The category reorder and
  delete compaction wrote `article_categories` rows in display order while
  the subcategory and mug slices lock them ascending by id; the category-ordering
  anchor does not serialize the slices, so a `40P01` (an unmapped 500) was
  reachable. Both category writers now lock all category rows ascending by id
  before the first mutation; `ArticleCategoryLockOrderConcurrencyIntegrationTest`
  reproduces the deadlock without the fix. In the reorder the locks sit after
  the read on purpose: locking first would re-snapshot and silently disable
  the documented `23505`-at-COMMIT backstop.
- **Price write inside the supplier `23503` mapping (major).** `prices`
  carries two VAT foreign keys, so a VAT deleted between `prepare` and the
  write was mis-answered as a `supplierId` field error. The price write now
  runs inside the transaction but outside the mapping.
- **Stale example-image exemption (minor).** The "unchanged stored filename"
  exemption was decided from an unlocked pre-read and could store a reference
  to a deleted file. It is gone entirely — its rationale was wrong, because
  the deferred sweep never removes referenced files — and every submitted
  filename is validated. Consequence: an update that resubmits a vanished
  filename answers the existing `400` field error, and the pre-reads the
  exemption needed are gone with it.
- **Shared filename deleted while referenced (minor).** A removed or replaced
  example-image filename is now reported obsolete only when no other row of
  the owning table references it at commit time (mug update *and* delete,
  both subcategory paths). The mug half was a faithful port of a legacy gap;
  the subcategory half became reachable only through the approved pre-upload
  deviation and has its own deviation row. The partial unique index on the
  filename columns is recorded in `article-post-migration.md` as the
  long-term option.

Documentation findings (fixed in #26 and by the orchestrator in this record):
the Supplier and Pricing package guides still described Article as
unmigrated; the "price is never `0`" wording overstated what was eliminated
(a legitimate calculated price may be zero — only the missing-price sentinel
is gone); eleven missing deviation rows were added; assorted factual slips in
`article-package.md`/`article-post-migration.md` were corrected. The guide's
simplification-review checklist gained the cross-module doc-staleness item.

### 2026-07-28 — Term rename: "taxonomy" → "category" (Joe)

Joe retired the term "taxonomy" after verification. The sub-package is now
`category`, the lock table `article_category_ordering` (with its constraints),
the Exposed object `ArticleCategoryOrdering`, and the two test classes
`ArticleCategorySchemaIntegrationTest` and
`ArticleCategoryLockOrderConcurrencyIntegrationTest`; prose says "category
structure" or names the levels. `V13__create_articles.sql` was edited in place
because the branch is unmerged — a local development database created before
the rename must be rebuilt (Flyway checksum and table name changed). The
canonical language now lives in the repository-root `CONTEXT.md`.

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
| Reorder unknown id: 404 articles / 409 categories | `AdminArticleService` vs `ArticleCategoryService` | 404 everywhere | proposed deviation | Approved by Joe 2026-07-27 | none |
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
| Dense-sequence check before a reorder (`ValidateDenseGlobalSequence`) | `AdminArticleService.cs` (one global sequence) | Every reorder checks its own sequence: mugs per article type, subcategories per category, categories globally; a gap is `409` without a write | required behavior, preserved per sequence | T10 council decision 2026-07-28 | none — the legacy rule now holds for all three levels |
| Global article display order (ADR 0007) | legacy ADR 0007 | Superseded: per-type dense positions, single-phase rewrite | documentation of a superseded decision | T10 2026-07-28; legacy repository is read-only | recorded in `article-post-migration.md` |
| Subcategory reorder returns all subcategories of all categories | `ArticleSubcategoryService.cs:427,449-451` | Only the affected category's dense list | proposed deviation | Phase-3 verification 2026-07-28 (behavior follows the approved per-category positions; the row was missing) | Vue frontend adaptation |
| Reorder request fields `sourceArticleId`/`targetArticleId` (per level) | legacy reorder DTOs | Shared `ReorderInput { sourceId, targetId }`; validation errors are field errors on those keys (legacy: plain 400 message) | proposed deviation | Approved plan (shared reorder input); row added in phase-3 verification 2026-07-28 | Vue frontend adaptation |
| Unknown category on subcategory create/update: 404 | `ArticleSubcategoryService.cs` | 400 with a `categoryId` field error (Supplier precedent) | proposed deviation | T4 implementation decision 2026-07-27; row added in phase-3 verification | Vue frontend adaptation |
| Non-numeric path id: 404 via `{id:long}` route constraint | legacy route templates | 400 `Invalid … id` (module-wide route rule) | proposed deviation | Follows the repo-wide route validation pattern; row added in phase-3 verification | Vue frontend adaptation |
| Mug detail field errors keyed by bare member name (`HeightMm`) | `ArticleRequestValidator.cs` | JSON-path keys with the `mugDetails.` prefix (same rule as `price.` and `mugVariants[i].`) | proposed deviation | T5 implementation decision 2026-07-27; row added in phase-3 verification | Vue frontend adaptation |
| No positive-id rule, duplicate variant ids applied twice | `ArticleRequestValidator.cs` (no rules) | Reference ids must be positive; variant ids must be distinct (two of the three T5 additions; active-requires-category has its own row) | proposed deviation | T5 implementation decision 2026-07-27; row added in phase-3 verification | none |
| Variant example-image pre-upload answers 200; oversized body 400 | `AdminArticleController.cs:59-63`, `DomainExceptionHandler.cs:203` | 201 Created; 413 for an oversized body (same as the new subcategory pre-upload) | proposed deviation | T5 implementation decision 2026-07-27; row added in phase-3 verification; **the 413 was superseded on 2026-07-30** — all four upload endpoints answer `400` with the message on the `file` field (Joe's decision, see [`cart-migration.md`](cart-migration.md)) | Vue frontend adaptation |
| Blank or padded example-image filename: 400 `Invalid example image filename` | `AdminArticleService.cs:611-627` | Trimmed; a blank name normalizes to `null` (no image) and is accepted | proposed deviation | T5 implementation decision 2026-07-27; row added in phase-3 verification | Vue frontend adaptation |
| Subcategory example-image filenames are server-minted (multipart), sharing impossible by construction | `ArticleSubcategoryService.cs:221-233` | Pre-upload lets clients submit any uploaded filename; exclusive ownership is enforced by the delete-side reference check (fix V2), not by construction | consequence of the approved multipart→pre-upload deviation | Phase-3 verification 2026-07-28 | partial unique index recorded as long-term option in `article-post-migration.md` |
| Cross-category reorder answers `404 Article subcategory not found` although the target exists in another category | n/a (Kotlin wording) | Status is the T4 decision; the stable message asserts non-existence, which is imprecise for this case | wording caveat | Phase-3 verification 2026-07-28: status kept, message caveat recorded | none — revisit only if a client needs to distinguish the cases |

## Completion checklist result (2026-07-28)

The guide's migration completion checklist, walked item by item against the
code, the tests, and this record. Only the items that need an explanation are
spelled out; the rest are plainly satisfied.

- **Required behavior and verification.** Every row of the behavior matrix has
  a test; the last open one (dense-sequence validation on the two structure
  reorders) was closed in T10.
- **Observable deviations.** All approved by Joe on 2026-07-27 or recorded as
  implementation decisions in the log above, with the frontend consequences
  itemized in [`article-post-migration.md`](article-post-migration.md).
- **Lists, representations, shared inputs.** Every list is a `List<T>` served
  as a bare array. The module has one additional admin representation,
  `MugArticleListItem` (justified in the T6 log), and the storefront
  representations `PublicMug`/`PublicMugVariant` (justified in the T8 log).
  Create and update share one input per level.
- **`OperationResult` and module write results.** Operations answer
  `OperationResult<T>`; the persistence result types exist because their writes
  really have those outcomes. The two delete results carry the example images
  that may only be removed after the commit, which is why they are not row
  counts.
- **Routes, module handle, capability.** Routes use the shared HTTP, validation,
  and admin-protection infrastructure — no copied plugin setup anywhere in the
  module. `ArticleModule` is `internal`, `installArticleModule` returns only
  `ArticleCatalog`.
- **PostgreSQL owns the invariants.** Ordering locks plus declarative
  constraints, no guard inside a writing statement, no triggers. SQL states are
  mapped by placement, never by a constraint name; no undeclared state is
  converted into an expected result; `CancellationException` is rethrown by
  every service helper. Flyway owns the schema (`V13`), and there is no
  existing-schema adoption code.
- **Simplification review.** Searched for `ListResponse`, `ListResult`,
  `ListItem`, `DeleteResult`, `Article*Result`, copied auth/CSRF/JSON/
  StatusPages/validation setup, constraint-name or message inspection,
  transaction wrappers, compatibility code, and TODOs. Findings: the duplicated
  `asFailure` (merged into `OperationFailures.kt`), the mug-only dense check
  (merged into `DensePositions.kt`), and the repository-wide
  `databaseOperation` copies (left alone, see the retrospective). No TODO and
  no compatibility code exists in the module.
- **Documentation.** `article-package.md`, `module-architecture.md`,
  `migration-roadmap.md`, this record, and the four post-migration files are
  current as of T10.
- **Quality gate.** `./kotlin do ktfmt` then `./kotlin check` pass with Docker
  available; after the phase-3 fixes the article module contributes 123 tests,
  and the second formatter run reports no further changes. The full gate was
  re-run by the orchestrator after each verification fix ticket.

## Migration retrospective

Filled on 2026-07-28, after the ten implementation tickets and before the
council verification. The plan held: no ticket had to reopen an approved
decision, the schema of T3 carried all nine slices without a follow-up
migration, and the only behavior question that stayed open across tickets (the
dense check) is closed in T10. The findings below are the ones with real
evidence; process noise without a lesson is not listed.

| Finding | Evidence | Scope | Earlier signal or check | Destination and action |
| --- | --- | --- | --- | --- |
| A rule discovered in one ticket does not reach the tickets written before it. T7 added the dense-sequence check for mugs; the category and subcategory reorders of T3/T4 had been written without it and stayed inconsistent until T10. | T7 decision log ("they were written before the rule was implemented anywhere"), closed by the T10 alignment | Cross-ticket, applies to any migration split into slices | The behavior matrix already listed "dense sequence validation and 409 conflict semantics stay" as required behavior. Nothing compared the *later* slices against the earlier ones. | Module record. Ticket-splitting rule for the council: when a required behavior is implemented in a later slice, the earlier slices that share it are part of that ticket's scope or of an explicit follow-up item — the deferred-work entry did work here, and is what made T10 close it. |
| The copied `databaseOperation` helper reached the seventh module — the case the Promotion retrospective said should reopen the question. | Four copies in Article (`MugArticleService`, `PublicMugService`, `ArticleCategoryService`, `ArticleSubcategoryService`) next to `VatService`, `SupplierService`, `PromotionService`, `ProductionDestinationService`, plus the same shape under other names in `PriceService` and `MagicCoinsService` | Repository-wide shared infrastructure | The Promotion retrospective (2026-07-26) already recorded the duplication; Joe declined the move then and named the trigger for revisiting it: "when a later migration adds the seventh copy". Article is that migration. | Moved to [`all-post-migration.md`](all-post-migration.md) (2026-07-28): the open decision, the unchanged `platform` proposal, and the Account counter-example live there now, together with the other work that waits for the end of the whole migration. Not applied in T10 — it is an architecture default under guide rule 4, and deduplicating it inside Article alone would make one module differ from the other six for no behavioral gain. |
| The Kotlin quality rules that every migration trips over were documented but unreachable: nothing linked `kotlin-code-quality.md`. | T3 lost a run to a `private companion object` in a `@Serializable` input, which that file already explains; T6 and T8 both hit Detekt's function limits and answered them with a split | Workflow | The document existed since before this migration and was not referenced by the guide, the skill, or any `AGENTS.md`. | Guide, applied in T10: step 3 of the workflow now links `kotlin-code-quality.md` and names both failures. |
| Detekt's `TooManyFunctions` was a useful design signal twice, not an obstacle. | T6 split the read slice, T8 split the public storefront out of `MugArticleOperations`/`MugArticleService`/`ArticleMugRepository` after Detekt refused both the class and the file | Design | — the signal worked as intended | Module record only. No rule change: the existing suppression policy already forbids silencing it, and the correct reaction happened both times. |
| Two test runs failed once and never again: a `country` test in the first full T1 run and a `500` in the T5 mug concurrency test. Neither reproduced in any later run, including the T10 gate. | T1 and T5 implementation reports; the T10 run of the whole article module was green (117 tests) | Verification | Nothing distinguishes a flaky test from a real race after the fact; only a reproduction attempt does, and both were re-run. | Module record only. Not promoted: a single non-reproducing failure is not evidence of a rule. If either recurs, the concurrency design of that slice — not the test — is the thing to inspect. |
| `gh` fails inside the command sandbox with a TLS error, so every ticket read its issue with the sandbox disabled. | Every implementation ticket of this migration | Tooling | The first occurrence already showed the pattern; it repeated ten times. | Council/skill knowledge, recorded here: `gh` calls belong outside the sandbox. No repository change — the sandbox policy is a user setting, not migration infrastructure. |
| Per-slice deadlock analyses do not compose: every slice proved its own lock order sound, and the cross-slice cycle (category rewrite in display order vs. ascending-id locks in the other slices) survived ten tickets and the T10 review, found only by phase-3 verification. | OP-1, fixed in #24; the regression test fails with the rethrown `40P01` without the fix | Design/verification | The T5 log documented a *pairwise* deadlock analysis against T4 — the category slice itself was never re-analyzed after the others adopted the ascending-id rule it does not use. | Module record. Council rule of thumb: a lock-order argument is a claim about every writer of the shared rows, so each new slice that locks them re-opens the proof for the existing slices — and the proof wants a cross-slice concurrency test, not only per-slice ones. |
| Binding another module's capability silently invalidates that module's package guide. Five passages in the Supplier and Pricing guides still said "once Article is migrated" / "the composition root discards this". | OP-4, fixed in #26 | Documentation | The guide's checklist required the *own* package guide but never mentioned the guides of consumed modules. | Guide, applied in phase 3: the simplification-review checklist now requires updating the package guides of every module whose capability the migration binds or whose tables it references. |
| A defaulted primitive in a `@Serializable` input is an easy silent contract change: `mugVariants[i].active = true` deviated from the legacy `false`, survived review because every fixture set the flag explicitly, and was caught only by field-by-field DTO comparison in verification. | CX-3/OP-3, fixed in #25 | Contract fidelity | The migration guide asks to preserve behavior, but no step compares request-DTO defaults against the legacy DTOs member by member. | Module record. Verification-briefing knowledge for the council: reviewing a migrated contract includes diffing every defaulted field of every input DTO against the legacy default, exactly like response shapes are locked as whole documents. |
