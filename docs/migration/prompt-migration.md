# Prompt module migration

Module-specific record for the migration of the legacy .NET Prompt feature to
the Kotlin backend. Workflow: `migrate-dotnet-feature` skill, orchestrated by
the `migration-council` skill. Rules: [`module-migration-guide.md`](module-migration-guide.md).

## Status

`implementation` — analysis and deviations D1–D12 approved by Joe on
2026-07-28 ("D1–D12 wie empfohlen"). Ticket sequence: slice 1 (slots +
variants), slice 2 (categories + subcategories), slice 3 as sub-tickets
3a schema+CRUD+price, 3b example images, 3c reorder+concurrency,
3d public list, 3e PromptCatalog.

Keep this value current whenever the migration changes phase so that a later
session can continue from the correct phase.

## Task parameters

Target module:

`prompt`

Source feature:

`../voenix-shop/backend/Voenix.Api/Features/Prompt` (plus the prompt-related
parts of `Database/VoenixDbContext.cs`, `ErrorHandling/DomainExceptionHandler.cs`,
and the prompt migrations under `Voenix.Api/Migrations/`)

Target package:

`backend/modules/prompt/src/shop/voenix/prompt`

Analysis checkpoint:

`wait-for-approval`

Known consumers:

- Storefront frontend: `GET /api/prompts?categoryId=` (public list)
- Admin frontend: the five admin route groups under `/api/admin/prompts/**`
- Legacy `Generator` feature (not yet migrated): `IPromptService.GetPromptTextAsync(promptId)` — plan the exported capability for it now
- Legacy `Cart`/`Checkout`/`Order` features (not yet migrated): read the prompt sales gross price (`CartService.GetPromptPriceAsync`); price is snapshotted, no FK on `prompts`

Approved deviations from current behavior:

- Public prompt list sorts by global `(position, id)` even when filtered by
  `categoryId`. Legacy ignores `position` for the filtered list and sorts by
  subcategory/title instead. Approved by Joe, 2026-07-28.
- Rename `PromptSlotType` → `PromptSlot` (table `prompt_slots`); the mapping
  column `slot_id` becomes `slot_variant_id`. `PromptSlotVariant` keeps its
  name (consistent with article variants). Approved by Joe, 2026-07-28.
- The legacy slot-type create retry on position conflict (one automatic retry,
  then 409) is dropped; the anchor-row + `FOR UPDATE` dense-position mechanism
  from the article module replaces the whole two-phase position rewrite.
  Behavior-equivalent from the client's perspective. Approved by Joe, 2026-07-28.
- No SQLite portability: Postgres-only error handling via
  `executePostgresWrite` (SQL states `23505`/`23503`), no constraint-name
  inspection, per repo standard.

Explicitly deferred work:

- Generator, Cart, Checkout, Order integrations land with their own module
  migrations; this migration only shapes the exported capability they will
  consume. Owner: the respective future migration.

## Analysis deliverable

Before implementation, record these analysis artifacts in this file:

1. the behavior-evidence-classification-verification matrix;
2. the operation contract table, plus one concrete example request body and one
   example response body written out next to it, so that a shape mismatch
   between the two is visible before implementation starts;
3. material ambiguities and proposed deviations;
4. the Kotlin operation interface and production type map;
5. the runtime composition design: `XModule`, `createXModule`,
   `installXModule`, their visibility, and exported capabilities;
6. application-composition and Flyway changes;
7. the test plan; and
8. deferred work and its owner.

Every required behavior must have a planned verification. Use the table
formats from the guide and record only the module-specific findings and
design.

If `Analysis checkpoint` is `wait-for-approval`, stop after sharing this
analysis. If it is `continue-automatically`, continue unless a stop condition
from the guide applies.

### Pre-analysis inputs from earlier migrations

- `pricing-post-migration.md` ("Prompt relationship and projection"): nullable
  `prompts.price_id` with index, reuse the pricing calculator, expose only the
  small public price projection (net/gross/tax + VAT percent) on the
  storefront.
- `image-post-migration.md`: example images go through the shared
  `PublicImageStorage`, folder `prompt-example-images`.

### Recorded analysis (2026-07-28, council round)

Produced by the migration-council analysis phase: three independent proposals
(orchestrator, Opus, Codex/GPT), one rebuttal round per material conflict.
The three proposals agreed on nearly everything; the rebuttal outcomes are in
the decision log. Evidence base: the complete legacy feature, the prompt parts
of `VoenixDbContext`, `DomainExceptionHandler`, the five prompt migrations,
the legacy consumers (`CartService.GetPromptPriceAsync`,
`GeneratorController`), the Vue stores, and on the Kotlin side the article
module, `PriceCatalog`, `PublicImageStorage`, and `V13__create_articles.sql`.

#### 1. Behavior matrix (decisive rows)

| Behavior | Evidence | Classification | Kotlin approach | Verification |
| --- | --- | --- | --- | --- |
| Composed text = `promptText.trim()` + non-blank variant prompts trimmed, ordered `(slot.position, slot.id, variant.name, variant.id)`, joined `"\n\n"` | `PromptService.GetPromptTextAsync` | Required | `PromptCatalogService.composedText`; ordering in SQL, trim at read time | Catalog integration test: multi-slot ordering, blank-line join, untrimmed stored text |
| Composed text unavailable when prompt missing, `!active`, `archived`, or blank text → one absent case | same + `FindActiveByIdAsync` | Required | `composedText` returns `null` | same |
| Cart price filter is `active && !archived` only — category/subcategory active flags deliberately NOT checked (unlike storefront) | `CartService.GetPromptPriceAsync` | Required | `findSalesGrossPriceCents` uses only `active && !archived` | Catalog test: prompt in inactive category resolves; archived does not |
| Storefront visibility: `active && !archived && category.active && (subcat null or subcat.active)` | `FindActivePromptsAsync` | Required | `PublicPromptRepository` WHERE | Public read visibility matrix |
| Public list always `(position, id)`, also filtered | record | Approved deviation | one query, optional category predicate | order equal with/without filter, proved by swapping positions |
| Prompt/category positions global dense unique; create appends, reorder single-phase rewrite; category delete compacts | services + unique indexes | Required (mechanism: approved deviation) | anchor rows + `FOR UPDATE` + `DEFERRABLE` uniques + `isDenseBy` | admin + concurrency tests per slice |
| Reorder refuses a gapped stored sequence with 409, writes nothing | `ValidateDenseGlobalSequence` | Required | `isDenseBy` → `Conflict` | concurrency test with manual gap |
| Unknown reorder id: legacy 404 (prompts) but 409 (categories/subcategories) | services | Unclear (inconsistent) | 404 everywhere (article contract) | admin tests |
| Subcategory positions dense per category; move to another category appends in target and compacts source; reorder only within one category | `PromptSubcategoryService` | Required | category rows as per-sequence anchors, locked ascending by id | admin + concurrency + lock-order tests |
| Slot positions unique, appended `MAX+1`, never compacted, no reorder route — gapped by design | `PromptSlotTypeService` (no compaction/reorder) | Required | anchor-serialized append; no `isDenseBy` for slots | concurrent creates append 1,2; delete leaves gap, next create does not reuse it |
| Slot create retry loop | legacy create loop | Approved deviation | dropped; anchor makes the conflict unreachable | same concurrency test |
| Names case-insensitively unique: categories, slots; slot-variant names globally across all slots | `LOWER(name)` unique indexes | Required | `LOWER(name)` unique indexes, statement-scoped `23505` → name conflict | duplicate + concurrent duplicate tests |
| Subcategory names unique per category, case-SENSITIVE in legacy | plain unique index | Proposed deviation | `(category_id, LOWER(name))` (article's identical correction) | duplicate case-variant tests |
| Subcategory must belong to the prompt's category: pre-read + composite FK | `ValidateSelectedSubcategoryAsync` + composite FK | Required (invariant); the pre-read is incidental | keep composite FK `prompts(subcategory_id, category_id) → prompt_subcategories(id, category_id)`; drop the pre-read; FK maps to field error | admin + schema tests |
| Price required on create and update; update applies to the same price row in place | services + `[Required] Price` | Required (decision 4) | `PriceCatalog.prepare` before, `storeInTransaction`/`replaceInTransaction` inside the prompt transaction | both atomicity directions tested |
| Public/admin-list price = small projection (net/gross/tax cents + VAT percent); admin detail = full `CalculatedPrice` | DTOs + `pricing-post-migration.md` | Required | internal `PromptPrice` + `CalculatedPrice`; one batched `PriceCatalog.find` per response | JSON document comparison + VAT-change test |
| Public DTO never exposes `promptText` | DTOs | Required (decision 8) | `PublicPrompt` has no such field | whole-document comparison |
| `promptText` validated non-blank, stored untrimmed; title and llm trimmed | services | Required (decision 8) | `normalized()` leaves `promptText` alone | round-trip with leading/trailing whitespace |
| `slotVariantIds` required (may be empty), positive, deduplicated (not rejected), detail returns them sorted | `NormalizeSlotVariantIds` | Required | validate positive, `distinct()`, read back sorted | `[12,9,12]` in → `[9,12]` out |
| More than one variant of the same slot may be mapped to one prompt | mapping PK is only `(prompt_id, slot_variant_id)` | Required | no `(prompt_id, slot_id)` unique rule | integration test |
| Update replaces the mapping set | `ReplaceSlotVariantMappings` | Required | delete-all + insert-all in the prompt transaction | mapping replacement test |
| A slot variant cannot change its slot (update DTO has no slot id) | legacy update DTO/statement | Required (preserved) | separate create/update inputs | update asserts unchanged `slotId` |
| Prompts soft-delete via `archived` only; the four other entities hard-delete with `RESTRICT` in-use protection | controllers + configurations | Required (decision 8) | no prompt delete route/result; typed delete results elsewhere | route inventory + in-use 409 tests |
| Example image: pre-upload → filename; validated by shape + existence; old file deleted after commit, failures logged | `ValidateExampleImageFilename`, storage | Required (lifecycle) / deviations below | `PublicImageStorage`, folder `prompt-example-images`; shared-file-aware post-commit cleanup | example-image test matrix |
| List responses `{items:[...]}`; reorder bodies with entity-specific field names; ProblemDetails `code` discriminator | DTOs, exception handler, Vue stores | Proposed deviation / incidental | bare arrays; one `ReorderInput`; shared `ApiError` | route contract tests |

#### 2. Operation contract

Conventions: admin subtrees behind the shared fail-closed admin protection;
lists are bare JSON arrays ordered as stated; `201` + `Location` on create,
`204` on delete, `400` invalid, `404` not found, `409` conflict with one
stable message per route, `500` unexpected. Operations return the shared
`OperationResult<T>`.

| Group | Operations | Success value | Expected errors | Ordering |
| --- | --- | --- | --- | --- |
| `/api/admin/prompts/slots` | list, get, create, update, delete | `PromptSlot {id, name, position, variantCount}` | 400; 404; 409 name conflict; 409 in use (delete) | `(position, id)`; create appends `MAX+1` |
| `/api/admin/prompts/slot-variants` | list, get, create, update, delete | `PromptSlotVariant {id, slotId, slotName, name, prompt, description, llm, assignedPromptCount}` | 400 (incl. field error `slotId`); 404; 409 name conflict (global); 409 in use | `(slot.position, slot.id, name, id)` |
| `/api/admin/prompts/categories` | list, get, create, update, reorder, delete | `PromptCategory {id, name, position, active}`; reorder → complete new order | 400; 404 (incl. unknown reorder id); 409 name; 409 in use; 409 order conflict | `(position, id)` dense |
| `/api/admin/prompts/subcategories` | list, get, create, update, reorder, delete | `PromptSubcategory {id, categoryId, name, description, position, active}`; reorder → the affected category's list | 400 (field errors `categoryId`: missing category / referenced move); 404 (incl. cross-category target); 409 name; 409 in use; 409 order conflict | `(category.position, category.id, position, id)` |
| `/api/admin/prompts` | list, get, create, update, reorder, upload example image | list row `PromptListItem` (flat ids + `categoryName`/`subcategoryName`, small `price`); detail `Prompt` (adds `promptText`, `slotVariantIds`, full `CalculatedPrice`) | 400 only for writes (all reference failures are field errors — **no prompt write answers 409**); 404; 409 only on reorder | `(position, id)` dense; create appends |
| `GET /api/prompts?categoryId=` (anonymous) | list | `PublicPrompt {id, position, title, category {id,name,position}, subcategory?, exampleImageFilename, llm, price?}` — nested category objects stay because the storefront has no other category source | 400 unparsable `categoryId`; unknown id → empty array | `(position, id)` always |

Reorder body for all three reorder routes: `{"sourceId": 42, "targetId": 8}`.

Admin prompt create, side by side (the shape-mismatch check):

Request `POST /api/admin/prompts`:

```json
{
  "title": "  Watercolor portrait  ",
  "promptText": "Turn the photo into a watercolor portrait.\n",
  "categoryId": 3,
  "subcategoryId": 7,
  "slotVariantIds": [12, 9, 12],
  "exampleImageFilename": "6f1b0f34-6f0a-4a2f-9c1a-2b7f0c9d1e55.webp",
  "llm": "  gpt-image-1  ",
  "active": true,
  "archived": false,
  "price": { "purchaseVatId": 1, "purchaseCalculationMode": "NET",
    "purchaseActiveRow": "COST", "purchasePriceInputCents": 0,
    "purchaseCostInputCents": 120, "purchaseCostPercent": 0,
    "salesVatId": 1, "salesCalculationMode": "GROSS",
    "salesActiveRow": "TOTAL", "salesMarginInputCents": 0,
    "salesMarginPercent": 0, "salesTotalInputCents": 499 }
}
```

Response `201 Created`, `Location: /api/admin/prompts/42`:

```json
{
  "id": 42,
  "position": 8,
  "title": "Watercolor portrait",
  "promptText": "Turn the photo into a watercolor portrait.\n",
  "categoryId": 3,
  "subcategoryId": 7,
  "slotVariantIds": [9, 12],
  "exampleImageFilename": "6f1b0f34-6f0a-4a2f-9c1a-2b7f0c9d1e55.webp",
  "llm": "gpt-image-1",
  "active": true,
  "archived": false,
  "price": { "id": 77, "…": "the complete CalculatedPrice the pricing module answers with,
    i.e. the 13 input scalars plus id, both VAT objects, and the seven derived amounts" }
}
```

The four intentional asymmetries this exposes: `price` is flat `PriceInput`
in, full `CalculatedPrice` out under the same field name (article precedent);
`slotVariantIds` comes back deduplicated and sorted; `title`/`llm` come back
trimmed while `promptText` keeps its whitespace verbatim; `position` is
response-only and never accepted in a body. A submitted `priceId` is ignored.

#### 3. Kotlin type map

Sub-packages `slot`, `category`, `persistence`; the prompt slice itself in the
module root (~66 production files; article's size-and-navigation rule).

- Slice 1 (`slot` + persistence, 20 files): `PromptSlot`, `PromptSlotInput`
  (shared create/update), `PromptSlotOperations/Service/Routes`;
  `PromptSlotVariant`, `PromptSlotVariantInput` (create, with `slotId`),
  `PromptSlotVariantUpdate` (no `slotId` — the one legitimate asymmetric input
  pair), `PromptSlotVariantOperations/Service/Routes`; persistence:
  `PromptOrdering` (anchor table + lock functions), `PromptSlots`,
  `PromptSlotVariants`, both repositories, `PromptSlotWriteResult`
  (`Stored`/`NotFound`/`NameConflict`), `PromptSlotDeleteResult`
  (`Deleted`/`NotFound`/`InUse`), `PromptSlotVariantWriteResult` (+
  `SlotNotFound`), `PromptSlotVariantDeleteResult`; `PromptModule.kt` grown
  per slice.
- Slice 2 (`category` + persistence, 22 files): the five-schema pairs for
  `PromptCategory` and `PromptSubcategory` (one representation each — the
  legacy list/detail subcategory DTOs are field-identical), `ReorderInput`,
  persistence tables/repositories, write/order/delete results incl.
  `CategoryNotFound` and `InUse`, `DensePositions` (`isDenseBy`).
- Slice 3 (root + persistence, ~24 files): `Prompt` (admin detail),
  `PromptListItem`, `PromptInput`, `PromptOperations/Service/Routes`,
  `PublicPrompt`, `PromptCategoryReference` (public nested objects),
  `PublicPromptOperations/Service/Routes`, `PromptPrice` (internal),
  `ExampleImage`/`ExampleImageUpload` (or the promoted shared reader, decision
  D9), `PromptCatalog` (public) + `PromptCatalogService`; persistence:
  `Prompts`, `PromptSlotVariantMappings`, `PromptRepository`,
  `PublicPromptRepository`, `PromptCatalogRepository`, `StoredPrompt`
  (row + `priceId` before batched price resolution), `PromptWriteResult`
  (`Stored`/`NotFound`/`CategoryNotFound`/`SubcategoryNotFound`/
  `SlotVariantNotFound`), `PromptOrderResult`.

Types that fail the deletion test and are not created: the six `*ListResponse`
wrappers, `AdminPromptSlotTypeSummaryDto` (→ `slotId` + `slotName`), the
subcategory list/detail DTO pair, `CreateAdminPromptRequest` (empty subclass),
any `PromptDeleteResult`, any slot `OrderResult`, module-local operation
results or transaction wrappers.

#### 4. Runtime composition

```kotlin
internal class PromptModule(
    slots, slotVariants, categories, subcategories, prompts, publicPrompts,
    val catalog: PromptCatalog,
) { fun install(application: Application) }

internal fun createPromptModule(
    database: Database, images: PublicImageStorage, prices: PriceCatalog,
): PromptModule

public fun Application.installPromptModule(
    database: Database, images: PublicImageStorage, prices: PriceCatalog,
): PromptCatalog

public fun RequestValidationConfig.validatePromptRequests()
```

Public surface: exactly `installPromptModule`, `validatePromptRequests`, and
`PromptCatalog`. Everything else `internal`, incl. `PromptPrice`. One internal
`installPromptModule` route-test-seam overload per operation interface.

```kotlin
public interface PromptCatalog {
    /** Composed generation text, or null when the prompt is unknown, inactive,
     * archived, or has no text. For the future Generator migration. */
    public suspend fun composedText(promptId: Long): String?

    /** Current gross sales price in integer cents per usable prompt
     * (active && !archived, price linked). Unknown/ineligible ids are absent,
     * never 0 — 0 is a legitimate price. For the future Cart migration. */
    public suspend fun findSalesGrossPriceCents(promptIds: Set<Long>): Map<Long, Int>
}
```

Both capability methods deliberately ignore the category/subcategory active
flags (matching legacy Generator and Cart); only the storefront list checks
them.

#### 5. Application composition and Flyway

- `backend/modules/prompt/module.yaml`: dependencies `../platform`,
  `../image`, `../pricing` (exported — `installPromptModule` names
  `PriceCatalog`, the admin detail serializes `CalculatedPrice`); add `prompt`
  to `backend/app/module.yaml`.
- `Application.kt`: `validatePromptRequests()` in the RequestValidation block
  (inputs registered per slice); `installPromptModule(database, images,
  prices)` after `installArticleModule`; capability discarded until
  Generator/Cart.
- **One** Flyway migration `V14__create_prompts.sql` (platform resources),
  delivered complete with slice 1 so `variantCount`/`assignedPromptCount`
  never lie and later slices add only Kotlin. Content: `prompt_ordering`
  anchor table (`sequence text PK`, CHECK in `('SLOT','CATEGORY','PROMPT')`,
  three rows seeded); `prompt_slots`; `prompt_slot_variants`;
  `prompt_categories`; `prompt_subcategories` (AK `(id, category_id)`);
  `prompts`; `prompt_slot_variant_mappings` — with the constraint choices
  from the behavior matrix: all four position uniques `DEFERRABLE INITIALLY
  DEFERRED` (rebuttal outcome R1: statement-time `23505` then provably means
  name conflict), `LOWER(name)` uniques (slots, variants global, categories,
  subcategories per category), composite subcategory FK, `price_id` nullable
  + FK RESTRICT + `UNIQUE(price_id)`, mapping FKs prompt CASCADE / variant
  RESTRICT, `prompt_text NOT NULL`, `title varchar(255)`, no tautological
  subcategory-requires-category check (category is NOT NULL).
- Lock hierarchy (the deadlock contract, article's rule): a global anchor row
  is taken before any category row; category rows always distinct, ascending
  by id, one statement each. Prompt writes disambiguate their four foreign
  keys per statement instead of one generic `23503` mapping: lock the
  category row (absent → field error `categoryId`), price ids are minted by
  `storeInTransaction` (FK cannot fail), the `prompts` statement maps `23503`
  → `SubcategoryNotFound`, the mapping insert maps `23503` →
  `SlotVariantNotFound`.

#### 6. Test plan

Article's categories per slice — input validation (pure), route security and
validation (stubbed operations), admin integration, concurrency, schema
(Flyway on empty PostgreSQL, rules asserted through rejected writes and SQL
states, never constraint names), public read, catalog/capability:

- Slice 1: `PromptSlotInputValidationTest`,
  `PromptSlotVariantInputValidationTest` (full field matrix incl. create/
  update asymmetry), both route-security tests, both admin integration tests
  (case-insensitive duplicates, the global cross-slot variant duplicate,
  in-use 409s, `variantCount`), `PromptSlotConcurrencyIntegrationTest`
  (concurrent creates append without retry; delete leaves a gap that is not
  reused), `PromptSlotSchemaIntegrationTest` (deferred position rule, LOWER
  indexes, RESTRICT, seeded anchor rows).
- Slice 2: the three input-validation tests (incl. `ReorderInput`), two
  route-security tests, two admin integration tests (dense append/compact/
  reorder, cross-category move appends + compacts, 404 for unknown reorder
  ids and cross-category targets), `PromptCategoryConcurrencyIntegrationTest`
  + `PromptSubcategoryConcurrencyIntegrationTest` (serialized reorders,
  gapped-sequence refusal, out-of-anchor write fails at COMMIT),
  `PromptCategoryLockOrderConcurrencyIntegrationTest` (ascending-id rule),
  `PromptCategorySchemaIntegrationTest`.
- Slice 3: `PromptInputValidationTest` (incl. fields the contract must NOT
  have: `position`, `priceId`), `PromptRouteSecurityAndValidationTest`
  (anonymous public route, absent DELETE, upload errors),
  `PromptAdminIntegrationTest` (price atomicity both directions, the three
  separate reference field errors, mapping replacement, dedup/sort round
  trip, untrimmed `promptText`, null-price repair),
  `PromptExampleImageIntegrationTest` (article's matrix + shared-file rule),
  `PromptConcurrencyIntegrationTest`, `PublicPromptIntegrationTest`
  (visibility matrix, order with/without filter, whole-document comparison,
  one batched price lookup), `PromptCatalogIntegrationTest` (composition
  rules, eligibility divergence, batching, VAT change),
  `PromptSchemaIntegrationTest`, plus a cross-module pricing relationship
  test (price delete through the pricing route answers 409 without
  constraint names).

#### 7. Deferred work and owners

- Generator/Cart/Checkout/Order integrations: their own migrations (unchanged
  from the task parameters).
- Frontend adaptation (bare arrays, `{sourceId,targetId}`, `/slots`, flat
  admin ids, no `details.code`, UUID-`.webp` filenames, upload status): a
  required `docs/migration/prompt-post-migration.md` deliverable of slice 3;
  owner: frontend follow-up after the module lands.
- Revisit the nullable `price_id` + required-price tension (a CHECK like
  article's active-requires-price) after Cart exists; recorded, not blocking.

## Decision log

### 2026-07-28 — Brainstorming decisions (Joe + Claude)

Scope and mechanics agreed before formal analysis:

1. **Dense-position mechanics**: reuse the article module's `DensePositions`
   pattern (anchor row, `SELECT ... FOR UPDATE`, `DEFERRABLE INITIALLY
   DEFERRED` unique index) instead of porting the legacy two-phase rewrite
   with serializable isolation.
2. **Global prompt ordering is intentional**: per-category ordering would be
   the ideal model but is hard to do in the UI, so a single ordering across
   all prompts stays. The storefront must respect this ordering; it does not
   need the sequence to be gap-free, only unambiguous. Hence the approved
   deviation: the filtered public list also sorts by position.
3. **Slot-variant names stay globally unique** (case-insensitive), across all
   slots — a variant should exist only once regardless of slot. Confirmed as
   intended behavior, not an accident.
4. **Prompt price stays required in the admin flow** (option a): create always
   creates a price via the pricing module; update rejects a missing price.
   The DB column stays nullable per `pricing-post-migration.md`.
5. **Exported capability**: a small `PromptCatalog`-style surface for future
   modules — resolve the composed prompt text (for Generator) and the prompt
   sales price (for Cart). Everything else stays `internal`.
6. **Delivery as a ticket sequence** via the `migration-council` skill, slice
   order: (1) slots + slot variants, (2) categories + subcategories,
   (3) prompts (public API, price, example images, reorder). Order follows
   the foreign keys; each slice is fully testable on its own.
7. **Terminology**: `PromptSlot` (was `PromptSlotType`) and
   `PromptSlotVariant`; recorded in the `CONTEXT.md` glossary.
8. **Kept as-is**: soft delete via `archived` (no DELETE endpoint for
   prompts), `promptText` is validated but never trimmed on storage (the
   composed text trims at read time), the public DTO never exposes
   `promptText`.

### 2026-07-28 — Council analysis round (orchestrator, Opus, Codex)

Three independent proposals, one rebuttal round per material conflict:

- **R1 — Slot position unique is `DEFERRABLE INITIALLY DEFERRED`** like every
  other position rule (Opus conceded to Codex): a statement-scoped `23505` on
  slot create/update then provably means a name conflict; a position
  collision — unreachable under the anchor — stays an unexpected COMMIT-time
  failure instead of a mislabelled name conflict.
- **R2 — `PromptCatalog` exports `findSalesGrossPriceCents(ids): Map<Long,
  Int>`** (Opus conceded to Codex): legacy Cart consumes exactly the gross
  cents; `PromptPrice` stays internal. Conditions: the name carries the unit,
  and absent means unknown/ineligible — never a `0` sentinel.
- **R3 — Admin prompt responses are flat (`categoryId`/`subcategoryId`,
  display names on list rows only); the public DTO keeps the nested
  `{id, name, position}` objects** (Codex conceded to Opus): the admin client
  loads both taxonomy lists itself, while the storefront has no other
  category source than the prompt list.
- Consensus without rebuttal: one `prompt_ordering` anchor table with three
  seeded rows; subcategories lock their category rows ascending by id;
  per-statement foreign-key disambiguation in the prompt write; slots gapped
  by design (no reorder, no compaction); separate create/update inputs for
  slot variants; one complete `V14__create_prompts.sql` delivered with
  slice 1; slice 3 split into sub-tickets (schema+CRUD+price, example
  images, reorder+concurrency, public list, catalog).
- Sent to Joe without a rebuttal round (both positions fully argued, and
  either choice needs his approval): subcategory-name case sensitivity
  (Opus: case-insensitive like article's correction; Codex: preserve
  case-sensitive legacy) and the upload success status (Opus: `201` like
  article; Codex: preserve legacy `200`).

### 2026-07-28 — Analysis approved

Joe approved the recorded analysis and all pending deviations with
"D1–D12 wie empfohlen" (all recommendations accepted, including the two
contested points D7 — case-insensitive subcategory names — and D8's `201`
upload status, and the conscious yes on D12). Status moved to
`implementation`; sub-tickets created under issue #28.

### 2026-07-28 — Slice 1 implemented (slots + slot variants, issue #29)

The complete `V14__create_prompts.sql` and the slot slice are on
`prompt-migration`. Three implementation decisions inside the approved frame
are worth recording, because the later slices continue from them:

1. **`installPromptModule(database)` for now.** The record's end-state
   signature is `(database, images, prices): PromptCatalog`. Slice 1 uses
   neither the image storage nor the pricing capability and cannot return a
   capability that does not exist, so the installation takes only the database
   and grows in slice 3 (3a adds `prices`, 3b adds `images`, 3e returns
   `PromptCatalog`). `module.yaml` already declares `../image` and
   `../pricing: exported` as the record prescribes. Everything else of the
   composition is as recorded: internal handle and factory, two internal
   route-test-seam overloads, `installPromptModule` and
   `validatePromptRequests` public, wired after `installArticleModule`.
2. **`PromptSlotVariantMappings` exists one slice early.** The record assigns
   the Exposed mapping table to slice 3, but `assignedPromptCount` is part of
   the variant contract from the start and has to be counted somewhere.
   `prompt_id` is a plain `long` column there instead of an Exposed reference,
   because the `Prompts` table object arrives with slice 3; the database
   foreign key exists from `V14` on.
3. **The shared variant field rules live in `PromptSlotVariantUpdate`.**
   `PromptSlotVariantInput.validate()` checks `slotId` and then delegates
   through `values()`, so the four shared rules keep the guide's "exactly one
   implementation per rule" while the two inputs stay separate types.

Not deviations, only recorded so that the reader of a later slice does not look
for them: slots have no reorder route and no delete compaction, the delete gap
is proven by test, and the deferred position unique is asserted at `COMMIT`.

Documentation: [`prompt-package.md`](../dev/backend/prompt-package.md) is new
and describes the slot slice; `module-architecture.md` lists the module in the
graph, the dependency table, the physical layout, and the composition steps.

## Deviation and uncertainty log

| Behavior or contract | Source evidence | Kotlin behavior | Classification | Approval or owner | Follow-up |
| --- | --- | --- | --- | --- | --- |
| Public list with `categoryId` sorts by subcategory position, subcategory id, title, id and ignores `position` | `PromptService.FindActivePromptsAsync` | Always sort by `(position, id)`, with and without `categoryId` | Proposed deviation | Approved by Joe, 2026-07-28 | Verify with a storefront read test |
| Slot-type create retries once on position conflict, then 409 | `PromptSlotTypeService` create loop (`attempt < 2`) | Anchor-row dense positions; no client-visible retry semantics | Proposed deviation (behavior-equivalent) | Approved by Joe, 2026-07-28 | Concurrency test on slot create |
| Entity/table names `PromptSlotType` / `prompt_slot_types`, mapping column `slot_id` | Legacy schema | `PromptSlot` / `prompt_slots`, mapping column `slot_variant_id` | Proposed deviation (naming only) | Approved by Joe, 2026-07-28 | Glossary entry in `CONTEXT.md` |
| D1: List responses are `{items:[...]}` wrappers | All six `*ListResponse` DTOs; Vue stores read `.items` | Bare JSON arrays everywhere | Proposed deviation | Approved by Joe, 2026-07-28 | Route contract tests; frontend work in `prompt-post-migration.md` |
| D2: Admin route path `/api/admin/prompts/slot-types` | Legacy controller route | `/api/admin/prompts/slots` (+ `slotId` field name) after the PromptSlot rename | Proposed deviation | Approved by Joe, 2026-07-28 | Route tests; frontend follow-up |
| D3: Three reorder bodies with entity-specific field names; unknown reorder id → 409 for categories/subcategories but 404 for prompts | `Reorder*Request` DTOs; services | One `ReorderInput {sourceId, targetId}`; unknown id → 404 everywhere | Proposed deviation | Approved by Joe, 2026-07-28 | Reorder route tests |
| D4: Admin prompt/subcategory responses nest category objects | legacy DTOs | Flat `categoryId`/`subcategoryId` + display names on list rows; public DTO keeps nested objects (council consensus R3) | Proposed deviation | Approved by Joe, 2026-07-28 | Admin route tests; public whole-document test |
| D5: Prompt title unbounded | `PromptConfiguration` (`text`, no rule) | `varchar(255)` + validation rule | Proposed deviation | Approved by Joe, 2026-07-28 | Boundary tests 255/256 |
| D6: `prompt_text` column nullable (writes reject blank anyway) | `PromptConfiguration`; `?? string.Empty` compensation | `NOT NULL`, compensation deleted | Proposed deviation (low risk) | Approved by Joe, 2026-07-28 | Schema test |
| D7: Subcategory names unique per category case-sensitively | plain unique index | `(category_id, LOWER(name))` — contested: Opus for, Codex against; recommendation: case-insensitive (article's identical correction) | Proposed deviation | Approved by Joe, 2026-07-28 | Duplicate case-variant tests |
| D8: Example-image filename regex `png|jpe?g|webp`, permissive shape; name equal to stored value skips validation; old file deleted unconditionally | `ValidateExampleImageFilename`, `PromptExampleImageStorage` | UUID-`.webp`-only regex (the only names the Kotlin pipeline mints); no equality exemption; delete after commit only when no other prompt references the file; upload status `201` (contested: Codex prefers legacy `200`) | Proposed deviation | Approved by Joe, 2026-07-28 | Example-image integration matrix |
| D9: Multipart example-image reader | article's module-local `ExampleImageUpload.kt` | Promote `receiveExampleImageUpload` into the `image` module (second consumer, same policy — the guide's promotion condition); rewires article | Proposed deviation (touches a migrated module) | Approved by Joe, 2026-07-28 | `ExampleImageUploadTest` moves to `image` |
| D10: Update of a prompt whose stored `price_id` is null answers 500 | `PromptService.UpdateAdminPromptAsync` | A valid update creates and links the price (repairs the state the nullable column permits); missing request price stays 400 | Proposed deviation | Approved by Joe, 2026-07-28 | Null-price repair integration test |
| D11: No price-ownership backstop | legacy has no unique rule | `UNIQUE(price_id)` + FK RESTRICT (article precedent; ids only minted by `storeInTransaction`) | Proposed deviation (schema only) | Approved by Joe, 2026-07-28 | Schema test |
| D12: Storefront filters by category/subcategory active flags; Generator/Cart lookups do not | `FindActivePromptsAsync` vs `FindActiveByIdAsync`/`CartService` | Preserve the divergence deliberately (a prompt in a deactivated category stays generatable/buyable by id) | Required — confirm, do not silently unify | Approved by Joe, 2026-07-28 (conscious yes) | Catalog test: inactive category still resolves |

## Migration retrospective

Before reporting completion, compare the original analysis and design with the
final implementation, tests, documentation, and simplification changes. Record
late discoveries, avoidable rework, repeated manual effort, and missing checks
that could improve a future migration.

| Finding | Evidence | Scope | Earlier signal or check | Destination and action |
| --- | --- | --- | --- | --- |
| `<finding or no reusable finding>` | `<test, review, diff, or decision>` | `<scope>` | `<earlier signal or check>` | `<destination and status>` |

Use the scopes and promotion rules from `module-migration-guide.md`. Keep
module-specific findings in this record or the appropriate post-migration
file. Improve the skill, base, guide, `AGENTS.md`, or a mechanical check only
when the finding meets the guide's evidence threshold.

Do not invent an improvement merely to fill the table. Record `No reusable
process finding` when the review finds none. Keep semantic rule changes pending
until Joe approves them.
