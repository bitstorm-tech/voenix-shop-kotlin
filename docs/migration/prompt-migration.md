# Prompt module migration

Module-specific record for the migration of the legacy .NET Prompt feature to
the Kotlin backend. Workflow: `migrate-dotnet-feature` skill, orchestrated by
the `migration-council` skill. Rules: [`module-migration-guide.md`](module-migration-guide.md).

## Status

`complete` — analysis and deviations D1–D12 approved by Joe on 2026-07-28
("D1–D12 wie empfohlen"); every slice implemented (tickets #29–#35); phase-3
council verification passed on 2026-07-29 (three independent reviews, fixes
applied and re-verified, full `./kotlin check` green) and the retrospective
below is filled in. D13 and D14 were approved by Joe on 2026-07-29. The retrospective
candidates were decided on 2026-07-29 (four guide/skill changes applied; the
shared-helper question stays with `all-post-migration.md`). Still open: only
the follow-ups owned by
[`prompt-post-migration.md`](prompt-post-migration.md).

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
  admin ids, no `details.code`, UUID-`.webp` filenames, upload status): written
  down in [`prompt-post-migration.md`](prompt-post-migration.md) by slice 3e;
  owner: frontend follow-up after the module lands.
- Revisit the nullable `price_id` + required-price tension (a CHECK like
  article's active-requires-price) after Cart exists; recorded, not blocking —
  now item 3 of `prompt-post-migration.md`, together with the promotion of the
  copied test doubles into `test-support`.

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

Documentation: [`prompt-package.md`](../dev/backend/packages/prompt-package.md) is new
and describes the slot slice; `module-architecture.md` lists the module in the
graph, the dependency table, the physical layout, and the composition steps.

### 2026-07-28 — Slice 2 implemented (categories + subcategories, issue #30)

The `category` sub-package and its persistence are on `prompt-migration`. No
schema change was needed: `V14__create_prompts.sql` already carried the two
tables, both `DEFERRABLE` position uniques, the `LOWER(name)` rules, and the
`(id, category_id)` alternate key. Implemented as recorded — flat `PromptCategory`
and `PromptSubcategory` (one representation each), shared create/update inputs,
one `ReorderInput`, bare arrays, `404` for every unknown reorder id, reorder
answers with the complete new order (subcategories: the affected category only),
`isDenseBy` refusal, and the lock hierarchy "global anchor before category rows,
rows distinct and ascending by id".

Four implementation decisions inside the approved frame, recorded because slice 3
continues from them:

1. **`ReorderInput` lives in the module root, not in `category`.** The prompt
   reorder of slice 3c uses the same body; putting it in the category package
   would make the prompt slice import a category type for a rule that is not
   about categories. Same placement as the article module's `ReorderInput`.
2. **`PromptOrdering` grew a shared private lock function.** `lockSlotOrdering-
   InTransaction` and the new `lockCategoryOrderingInTransaction` both delegate
   to one `lockOrderingInTransaction(sequence)`; slice 3 adds `PROMPT` the same
   way. The check message names the sequence instead of the entity.
3. **The subcategory result mapping is a private top-level function.** Its one
   line — receiver, name, and return type — is 102 characters, which ktfmt wraps
   and ktlint then rejects as a broken return-type spacing. At file level the
   same signature fits in 98. The KDoc there carries the "these two are field
   errors on `categoryId`" rule, so nothing was lost by moving it out of the
   class.
4. **`PromptTestSchema` grew `seedCategories`, `seedSubcategories`,
   `seedPromptIn`, `orderedCategories`, and `orderedSubcategories`.** The
   existing `seedPromptUsing` stays for the variant tests; `seedPromptIn` is the
   general one the in-use and composite-key assertions need.

Verification: 91 tests in the module (`./kotlin test --include-module prompt`),
ktfmt, ktlint, and Detekt clean. The one test the first run caught was a
schema-test seed, not production code: the "a used subcategory cannot leave its
category" assertion collided with a duplicate name in the target category and
reported `23505` before the composite key could report `23503`.

Documentation: [`prompt-package.md`](../dev/backend/packages/prompt-package.md) gains the
category slice — the file map, the two route groups, how a dense position is
decided, the lock hierarchy, the extended `23503` table, and the new tests;
`module-architecture.md` names the category API in the module table and graph.

### 2026-07-28 — Slice 3a implemented (prompts: admin CRUD + price, issue #31)

The prompts themselves are on `prompt-migration`, in the module root as the type
map prescribes. No schema change was needed: `V14__create_prompts.sql` already
carried `prompts`, `prompt_slot_variant_mappings`, the deferred position unique,
the composite subcategory key, and `UNIQUE (price_id)`. Implemented as recorded —
flat `categoryId`/`subcategoryId` (D4), the shared `PromptInput` with the nested
required `PriceInput`, bare arrays, `201` + `Location`, no delete route, no `409`
from any prompt write, `PriceCatalog.prepare` before the transaction and
`storeInTransaction`/`replaceInTransaction` inside it, the null-price repair
(D10), a submitted `priceId` ignored, per-statement foreign-key disambiguation,
the `PROMPT` anchor before the category row, delete-all + insert-all mapping
replacement, `[12,9,12]` in and `[9,12]` out, and `promptText` stored untrimmed
while `title`/`llm` are trimmed.

Five implementation decisions inside the approved frame, recorded because the
following sub-tickets continue from them:

1. **`installPromptModule(database, prices)`.** The record's end state is
   `(database, images, prices)`. 3a consumes the pricing capability and nothing
   of the image storage, so `images` arrives with 3b, exactly as slice 1 recorded
   the rule. The four existing admin integration tests now install the real
   pricing module (`installPricingModule(database, installVatModule(database))`),
   which is also what makes the price atomicity assertions real rather than
   stubbed.
2. **`StoredPrompt` is generic (`StoredPrompt<Prompt>`, `StoredPrompt<PromptListItem>`).**
   The record names one persistence type for "the row plus its `priceId` before
   the batched price resolution", but the detail and the list row are two
   representations of the same idea. A second, near-identical type would have
   said nothing the first does not; the type parameter says it once.
3. **`PromptListItem.categoryName` is non-nullable and read by join.** `list()`
   inner-joins `prompt_categories` and outer-joins `prompt_subcategories`, so the
   whole page costs one query and the name a prompt always has is not an optional
   lookup result. The article module resolves the same labels through batched id
   maps because its category reference is nullable; a prompt's is not.
4. **`PromptSlotVariantMappings.promptId` became a real Exposed reference.**
   Slice 1 recorded it as a plain `long` column because the `Prompts` table
   object did not exist yet. It does now, so the mapping table says what the
   database has always said.
5. **`exampleImageFilename` is carried, not policed.** The field is part of the
   input and the answers from this ticket on (the type map has it), and 3a
   validates only the column bound of 255 characters. The UUID-`.webp` shape
   rule, the existence check against `PublicImageStorage`, and the post-commit
   cleanup are 3b's, as the ticket cut prescribes.

One conflict between the ticket and the repository, resolved and worth Joe's
attention: the acceptance criteria ask for a cross-module pricing relationship
test in which **deleting a price through the pricing route answers `409`**. The
pricing module deliberately exposes no delete route — `PriceRoutes` has
`POST`, `POST /calculate`, `GET /default`, `GET /{id}`, `PUT /{id}` and nothing
else, because a price is deleted by the owner that holds it, inside the owner's
transaction. `PromptPricingRelationshipIntegrationTest` therefore proves the
three things that *are* true across the module boundary: the price a prompt
minted is a normal price to the pricing routes, an edit made there is what the
prompt answers with afterwards (both the detail and the list projection), and the
row cannot be taken away from the prompt — asserted against the database, by SQL
state `23503` and without any constraint name. Nothing in the migration depends
on a price delete route; if one is ever wanted, it belongs to the pricing module
and not to this slice.

Verification: 117 tests in the module (`./kotlin test --include-module prompt`),
ktfmt, ktlint, and Detekt clean, `:app:compileJvm` green. The one test the first
run caught was an expectation, not production code: Ktor answers a `DELETE` on
the collection path with `405` and on `/{id}` with `404`, and the "there is no
delete route" test now states both.

Documentation: [`prompt-package.md`](../dev/backend/packages/prompt-package.md) gains the
prompt slice — the file map, the route group with its two absences, the four
request/response asymmetries side by side, how a prompt and its price stay one
write, the two new rows of the `23503` table with the per-statement rule, the
grown composition signature, and the new tests; `module-architecture.md` names
the prompt admin API and the `PriceCatalog` parameter in the module table, the
graph, and the composition steps.

### 2026-07-28 — Slice 3b implemented (example images, issue #32)

The example-image lifecycle is on `prompt-migration`, implemented as D8 and D9
prescribe: the pre-upload `POST /api/admin/prompts/example-images` answers `201`
with `{"filename": "<uuid>.webp"}` through `PublicImageStorage` and the folder
`prompt-example-images`; a submitted `exampleImageFilename` is validated by shape
(UUID with dashes plus `.webp` — no `png|jpe?g`) and by existence, with no
exemption for the name the prompt already stores; the replaced file is deleted
after the commit and only when no other prompt row referred to it at that moment;
a failing delete is logged and never surfaced; an orphaned pre-upload is
accepted. `installPromptModule(database, images, prices)` is now the recorded
end-state signature except for the `PromptCatalog` return value of 3e.

D9's promotion is done: `ExampleImageUpload` and `receiveExampleImageUpload` live
in the `image` module (`ExampleImageUpload.kt`), `ExampleImageUploadTest` moved
with them, and the two article route files import them from there. The article
module's behavior and its 118 tests are unchanged.

Four implementation decisions inside the approved frame:

1. **`ExampleImage` was not promoted, only the reader.** The pre-upload answer is
   a serialized response body of the module that owns the route, and prompt's
   copy is a different route's contract than article's. Promoting it would put a
   JSON DTO of two admin APIs into the image module, which stores images and does
   not answer admin requests. The ticket names the reader, and only the reader
   met the guide's promotion condition (a second consumer with the same policy).
2. **The shape and existence rules live in `PromptService`, not in
   `PromptInput`.** The length bound (255) stays a pure input rule, but the shape
   check sits next to the existence check that needs the image storage, so the
   two halves of one rule are read together and produce the same field error —
   the article module's placement, and the guide's "exactly one implementation
   per rule".
3. **The obsolete file is decided in the repository, inside the transaction.**
   `PromptWriteResult.Stored` gained `obsoleteExampleImageFilename`, filled by a
   `SELECT ... LIMIT 1` over `prompts.example_image_filename` after the update
   statement ran. Only there is the answer the state the commit will publish;
   the service then deletes after the commit and never inside it.
4. **`PromptRoutes` gained the article module's three private `Route`
   extensions** (`installCollectionRoutes`, `installExampleImageRoute`,
   `installItemRoutes`). The pre-upload branch would otherwise have made one
   `install` function long enough to hide its own structure.

Verification: 123 tests in the prompt module, 27 in `image`, 118 in `article`
(`./kotlin test --include-module prompt --include-module image --include-module
article`), ktfmt/ktlint/Detekt clean for all three, `:app:compileJvm` green.

Documentation: [`prompt-package.md`](../dev/backend/packages/prompt-package.md) gains the
example-image section (both requests, the two checks, the shared-file rule, the
two failures that are not the client's problem) and the grown composition
signature; [`image-package.md`](../dev/backend/packages/image-package.md) documents the
promoted reader and its test; [`article-package.md`](../dev/backend/packages/article-package.md)
records where the reader went; `module-architecture.md` and
[`image-post-migration.md`](image-post-migration.md) follow, and
[`article-post-migration.md`](article-post-migration.md) notes that the orphan
sweep now has a third column to cover.

### 2026-07-28 — Slice 3c implemented (prompt reorder + concurrency, issue #33)

`PUT /api/admin/prompts/order` is on `prompt-migration`, implemented as recorded:
the shared `ReorderInput {sourceId, targetId}`, the complete new order as a bare
array of `PromptListItem` rows including the small price projection, `404` for an
id the stored order does not contain (D3), the single-phase rewrite under the
`PROMPT` anchor with the `DEFERRABLE` unique rule, the `isDenseBy` refusal of a
gapped stored sequence that writes nothing, and `409` with the stable message
"Prompt order changed concurrently, please retry" for both conflict sources. No
schema change was needed.

Three implementation decisions inside the approved frame:

1. **The reorder locks no category row.** Every other prompt write takes the
   `PROMPT` anchor and then the category row it writes into, because it changes a
   reference. The reorder changes positions only, so it takes the anchor and then
   the prompt rows ascending by id — the category writers never wait for prompt
   rows while holding a category row, so there is no cycle to avoid. This is the
   category reorder's shape, one level down.
2. **`PromptService.withPrices` is shared by `list` and `reorder`.** The reorder
   answers list rows, so it needs the same batched `PriceCatalog.find` the list
   needs. Extracting the one function was cheaper than a second copy and keeps
   "one batched lookup per response" a property of the code rather than of two
   places that happen to agree.
3. **`PromptConcurrencyIntegrationTest` drives the real HTTP routes**, like the
   article module's `MugArticleConcurrencyIntegrationTest` and unlike the two
   category concurrency tests, which call their service directly. A prompt cannot
   be created without the pricing capability, and `installPricingModule` is an
   `Application` extension; going through the installed module is what the other
   priced slice already does, and it also proves the answer bodies and the `409`
   message, not only the stored positions.

Verification: 129 tests in the prompt module (`./kotlin test --include-module
prompt`), ktfmt, ktlint, and Detekt clean for the module, `:app:compileJvm`
green. No test needed a fix after the first run.

Documentation: [`prompt-package.md`](../dev/backend/packages/prompt-package.md) gains the
reorder section (body, answer, the three `404`/`409` rules), the prompt half of
"How the position is decided" with the lock order and the reason the row locks
come after the read, the one `23505` mapping the module now has, the route table
row, and the new test.

### 2026-07-28 — Slice 3d implemented (public list, issue #34)

The anonymous `GET /api/prompts?categoryId=` is on `prompt-migration`, implemented
as recorded: `PublicPrompt` with the nested `PromptCategoryReference` objects on
both levels (R3/D4) and **no** `promptText`, the visibility rule `active &&
!archived && category.active && (subcategory null || subcategory.active)`, the
order `(position, id)` with and without the filter (the approved deviation),
`400` for an unparsable `categoryId` and an empty array for an unknown one, and
the small `PromptPrice` projection resolved by exactly one batched
`PriceCatalog.find` per response. Bare array (D1), no admin protection, one more
route-test-seam overload. No schema change was needed; the read touches only
columns `V14__create_prompts.sql` already carried.

Four implementation decisions inside the approved frame:

1. **The public query never selects `prompt_text`.** The type not carrying the
   field would already satisfy the contract, but a column an anonymous read does
   not touch cannot reach an anonymous answer by a later refactor either. The
   whole-document test names the rule; the query is what makes it structural.
2. **An empty `categoryId` parameter means "no filter", not a rejected request.**
   `?categoryId=` is what a form that submits its fields unconditionally sends,
   and it is also what the legacy `long?` model binding did with it. Only a
   non-empty value that is not a `Long` is the `400` the ticket asks for; the
   test covers `not-a-long`, `1.5`, and an overflowing number.
3. **The batched lookup is skipped when no listed prompt has a price id**, not
   only when the list is empty. The ticket asks for "no lookup on an empty list";
   a list whose rows all have a null `price_id` has nothing to look up either,
   and asking for an empty set would be a statement with no question in it.
4. **`CountingDataSource` and `CountingPriceCatalog` were copied into the prompt
   test source set**, as `RecordingPublicImageStorage` already was in slice 3b.
   They are test doubles of another module's test source set, which no module can
   depend on; promoting them into `test-support` is a real option but touches
   every module that has a copy, and doing it inside a slice ticket would have
   been a change nobody asked for. Recorded as a candidate for the retrospective.

Verification: 137 tests in the prompt module (`./kotlin test --include-module
prompt`), ktfmt, ktlint, and Detekt clean for the module, `:app:compileJvm`
green. The one test the first run caught was an expectation, not production code:
the admin create response resolves its own price, so the counted lookups had to
be cleared after the writes and before the storefront read.

Documentation: [`prompt-package.md`](../dev/backend/packages/prompt-package.md) gains the
storefront section (the answer, the absent prompt text, why the categories are
nested here and flat there, and the four rules that decide the answer), the file
map, the route table row, the composition note that one call registers both
trees, and the two tests; `module-architecture.md` names the storefront list in
the module table, the graph, and the composition steps.

### 2026-07-28 — Slice 3e implemented (PromptCatalog + closing work, issue #35)

The exported capability is on `prompt-migration`, with the interface exactly as
section 4 records it: `composedText(promptId): String?` and
`findSalesGrossPriceCents(promptIds): Map<Long, Int>`. Implemented as recorded —
the composition is `prompt_text.trim()` plus the non-blank variant texts trimmed,
ordered `(slot.position, slot.id, variant.name, variant.id)` in SQL and joined
with `"\n\n"`; `null` covers unknown, `!active`, `archived`, and blank text as one
case; the price answer is `active && !archived` plus a linked price, batched
through **one** `PriceCatalog.find`, with ineligible ids absent and never a `0`
sentinel (R2); an empty set touches nothing. Neither read joins the category
tables at all, which is D12 made structural rather than remembered.
`installPromptModule(database, images, prices): PromptCatalog` is now the
recorded end-state signature, and `Application.kt` discards the value with the
comment Article and Promotion already carry. No schema change was needed.

Four implementation decisions inside the approved frame:

1. **One query answers a composition.** The prompt row and its variant texts come
   from a single left-joined, ordered statement instead of "read the prompt, then
   read its variants". Both mapping columns are `NOT NULL` foreign keys, so a
   `null` variant in a row can only mean "this prompt has no mappings" — which is
   why the same query answers a prompt with five slots and a prompt with none,
   and why an empty result means "no usable prompt with this id".
2. **`StoredComposition` is a new persistence type.** The type map names only
   `PromptCatalogRepository`, but the repository has to answer two things at once
   — the prompt's own text and its variant texts in order — and a `List<String>`
   whose first element is secretly the prompt text would have been a smaller type
   with a bigger rule. It is the sibling of `StoredPrompt`: what was read, before
   the service turns it into an answer.
3. **The composition rule lives in a private function next to the service**, not
   in the repository. Ordering is the database's job and trimming is the read's;
   putting the `"\n\n"` join in the repository would have made a persistence type
   own a text format, and putting the ordering in Kotlin would have made the
   service sort what SQL already sorts.
4. **`PromptModule` suppresses Detekt's `LongParameterList`.** The handle now
   names six route groups and one capability, one over the limit of six.
   Grouping some of them into a container type would have given the list a
   shorter name and no meaning, so the length is suppressed with the reason
   written next to it.

The closing work of the module is done with this ticket:
[`prompt-package.md`](../dev/backend/packages/prompt-package.md) gains the capability
section (the two methods, the composed-text example, the no-`0` rule, and D12)
plus the file map, the composition signature, and the new test;
`module-architecture.md` names the capability in the graph, the module table, the
capability list, the handle-visibility paragraph, and composition step 9;
[`migration-roadmap.md`](migration-roadmap.md) moves Prompt into "Already
migrated" and recomputes the waves (Cart is the only Wave-1 item left, Generator
is now blocked by Cart alone); the new
[`prompt-post-migration.md`](prompt-post-migration.md) owns the frontend
adaptation list and the three recorded open points; the Prompt rows of
[`image-post-migration.md`](image-post-migration.md) and
[`pricing-post-migration.md`](pricing-post-migration.md) are closed; and
`CONTEXT.md` gains the glossary entry "composed prompt text" next to the two slot
terms the setup recorded.

Verification: 143 tests in the prompt module (`./kotlin test --include-module
prompt`), ktfmt, ktlint, and Detekt clean for the module, `:app:compileJvm`
green. Two things the first run caught were test-only: three session secrets were
shorter than the 32 bytes `AuthSettings` requires, and Detekt's parameter limit
(decision 4 above).

### 2026-07-29 — Phase-3 council verification (orchestrator, Opus, Codex)

Three independent reviews of `d1afeb8..5de07b6` against this record, the
behavior matrix, D1–D12, `backend/AGENTS.md`, and the module migration guide.
All three found the production code behaviorally correct against the record;
every accepted finding was a test-coverage or documentation gap. Consolidation
with one rebuttal round per contested finding:

- **Codex maintained, then conceded after rebuttal (all three):** the missing
  `PROMPT` anchor in `PromptRepository.update` is a documentation error, not a
  code error — an update decides no position, and no lock-order cycle exists
  (traced independently by two reviewers; the overstated sentences in
  `prompt-package.md` and `PromptOrdering.kt` were corrected instead);
  non-positive path ids answering `404` instead of legacy `400` stays, because
  the article module's identical parser is the repo's route contract (now D14);
  the per-service `databaseOperation` wrapper is the repo-wide service pattern,
  not a duplicated domain rule — the shared-helper idea moved to the
  retrospective.
- **Accepted and fixed** by a `council-opus-implementer` agent: the successful
  prompt reorder was never proven to *move* anything against the database (the
  tests asserted only dense positions — a no-op reorder would have passed);
  the globally unique slot-variant name had no concurrency test although
  `AGENTS.md` requires one; the example-image tests did not substantially prove
  "delete only after commit" (the double now probes the committed state through
  a separate connection at delete time, asserts the folder, and the failing
  delete asserts the warning log via a test-only logback binding); the admin
  list gained the batching guard and `withPrices` the empty-set short-circuit
  the storefront read already had; the admin detail's full `CalculatedPrice` is
  now compared as an exact key set; documentation drift in
  `module-architecture.md` (prompt's exported pricing dependency, composition
  steps 4 and 6) and `prompt-package.md` (update lock narrative, three category
  writers, file map, prompt delete absence, example body, blank `categoryId`)
  was corrected. Two implementer decisions inside that frame:
  `PublicImageFolder` gained a behavior-neutral `toString` (the path stays
  internal to `image`), and the prompt module's tests got `logback-classic`
  plus a silent `logback-test.xml`, because a failing cleanup is observable
  only in the log.
- **Unrecorded deviations found and recorded, approval pending Joe:** D13
  (`llm` and `example_image_filename` narrowed to `varchar(255)` — only
  `title` was recorded as D5) and D14 (non-positive ids → `404`).

Re-verification after the fixes: 147 prompt-module tests (was 143), image
module unchanged at 27, module-scoped ktfmt/ktlint/Detekt clean, followed by
the orchestrator's full `./kotlin check` acceptance run.

## Deviation and uncertainty log

| Behavior or contract | Source evidence | Kotlin behavior | Classification | Approval or owner | Follow-up |
| --- | --- | --- | --- | --- | --- |
| Public list with `categoryId` sorts by subcategory position, subcategory id, title, id and ignores `position` | `PromptService.FindActivePromptsAsync` | Always sort by `(position, id)`, with and without `categoryId` | Proposed deviation | Approved by Joe, 2026-07-28 | Done in slice 3d: `PublicPromptIntegrationTest` proves the order with and without the filter by swapping two positions |
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
| D9: Multipart example-image reader | article's module-local `ExampleImageUpload.kt` | Promote `receiveExampleImageUpload` into the `image` module (second consumer, same policy — the guide's promotion condition); rewires article | Proposed deviation (touches a migrated module) | Approved by Joe, 2026-07-28 | Done in slice 3b; `ExampleImageUploadTest` moved to `image`, article unchanged |
| D10: Update of a prompt whose stored `price_id` is null answers 500 | `PromptService.UpdateAdminPromptAsync` | A valid update creates and links the price (repairs the state the nullable column permits); missing request price stays 400 | Proposed deviation | Approved by Joe, 2026-07-28 | Null-price repair integration test |
| Cross-module pricing relationship test: "price delete through the pricing route answers 409" (ticket #31) | `PriceRoutes` has no delete route at all | The relationship is proven through the pricing routes that exist (read, update, recalculated prompt answer) plus the `RESTRICT` rule asserted by SQL state `23503` | Ticket/repository conflict, resolved in the test | Implementer, 2026-07-28; for Joe's awareness | Only if a price delete route is ever added to the pricing module |
| D11: No price-ownership backstop | legacy has no unique rule | `UNIQUE(price_id)` + FK RESTRICT (article precedent; ids only minted by `storeInTransaction`) | Proposed deviation (schema only) | Approved by Joe, 2026-07-28 | Schema test |
| D13: `llm` and `example_image_filename` unbounded in legacy (`text`, no `HasMaxLength`) | `PromptConfiguration.cs` | `varchar(255)` + validation rule, same reasoning as D5; found by phase-3 verification, not recorded during analysis | Unrecorded deviation, recorded late | Approved by Joe, 2026-07-29 | Boundary behavior already covered by input validation tests |
| D14: Non-positive path ids (`0`, negative) answer 400 in legacy | `PromptSlotTypeService.cs` id guards → `DomainExceptionHandler` 400 | Plain `toLongOrNull` parser → the operation answers `404`, identical to the article module's parsers (the repo's route contract) | Unrecorded deviation, recorded late | Approved by Joe, 2026-07-29 | None — changing it would diverge from article |
| D12: Storefront filters by category/subcategory active flags; Generator/Cart lookups do not | `FindActivePromptsAsync` vs `FindActiveByIdAsync`/`CartService` | Preserve the divergence deliberately (a prompt in a deactivated category stays generatable/buyable by id) | Required — confirm, do not silently unify | Approved by Joe, 2026-07-28 (conscious yes) | Done in slice 3e: `PromptCatalogIntegrationTest` proves that a prompt whose category and subcategory are switched off still resolves both answers while an archived one resolves neither; the capability queries never join the category tables |

## Migration retrospective

Before reporting completion, compare the original analysis and design with the
final implementation, tests, documentation, and simplification changes. Record
late discoveries, avoidable rework, repeated manual effort, and missing checks
that could improve a future migration.

| Finding | Evidence | Scope | Earlier signal or check | Destination and action |
| --- | --- | --- | --- | --- |
| Ordering and reorder tests used non-discriminating fixtures: successful reorders asserted only dense positions, and several list-order tests seeded rows whose id order equals the position order — a no-op reorder and plain id ordering would have passed | Phase-3 verification (Opus blocker B1 + nits); fixed on the branch | Migration-wide | A test-plan rule: "an ordering assertion must use a fixture whose expected order differs from insertion/id order, and a reorder test must assert which row is where" | Guide's test coverage list, approved by Joe and applied 2026-07-29 |
| Schema-test seed asserted two rules at once, so the name unique (`23505`) masked the composite-FK rule (`23503`) on the first run | #30, first-run failure | Migration-wide | "One rule per rejected write in schema tests" | Guide's test coverage list (Flyway bullet), approved by Joe and applied 2026-07-29 |
| A ticket acceptance criterion named a route that does not exist (price delete via the pricing module) and had to be reinterpreted mid-slice | #31, resolved ticket/repo conflict (deviation log) | Process | Check cross-module acceptance criteria against the target module's real route surface when cutting tickets | Council skill, phase-1 step 6, approved by Joe and applied 2026-07-29 |
| Only one of three narrowed legacy columns got a deviation row during analysis; the other two surfaced in verification (D13) | Phase-3 verification (Opus m1) | Migration-wide | Analysis checklist: one deviation row per narrowed or re-typed legacy column | Guide's analysis step list, approved by Joe and applied 2026-07-29 |
| Test doubles are copied per module (`CountingDataSource`, `CountingPriceCatalog`, `RecordingPublicImageStorage` in article and prompt) | #34; slice-3d decision 4 | Cross-module | — | Promotion into `test-support`; already item 3 of `prompt-post-migration.md`, owner: separate refactor task |
| The `databaseOperation` cancellation/SQL-logging wrapper exists once per service (four article + five prompt services) | Codex verification finding, conceded to the retrospective | Cross-module | — | Already tracked as the open decision in `all-post-migration.md` (Prompt is recorded there as the eighth module); no new action from this migration |

Use the scopes and promotion rules from `module-migration-guide.md`. Keep
module-specific findings in this record or the appropriate post-migration
file. Improve the skill, base, guide, `AGENTS.md`, or a mechanical check only
when the finding meets the guide's evidence threshold.

Do not invent an improvement merely to fill the table. Record `No reusable
process finding` when the review finds none. Keep semantic rule changes pending
until Joe approves them.
