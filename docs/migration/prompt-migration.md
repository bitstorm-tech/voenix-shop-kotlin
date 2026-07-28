# Prompt module migration

Module-specific record for the migration of the legacy .NET Prompt feature to
the Kotlin backend. Workflow: `migrate-dotnet-feature` skill, orchestrated by
the `migration-council` skill. Rules: [`module-migration-guide.md`](module-migration-guide.md).

## Status

`analysis`

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

## Deviation and uncertainty log

| Behavior or contract | Source evidence | Kotlin behavior | Classification | Approval or owner | Follow-up |
| --- | --- | --- | --- | --- | --- |
| Public list with `categoryId` sorts by subcategory position, subcategory id, title, id and ignores `position` | `PromptService.FindActivePromptsAsync` | Always sort by `(position, id)`, with and without `categoryId` | Proposed deviation | Approved by Joe, 2026-07-28 | Verify with a storefront read test |
| Slot-type create retries once on position conflict, then 409 | `PromptSlotTypeService` create loop (`attempt < 2`) | Anchor-row dense positions; no client-visible retry semantics | Proposed deviation (behavior-equivalent) | Approved by Joe, 2026-07-28 | Concurrency test on slot create |
| Entity/table names `PromptSlotType` / `prompt_slot_types`, mapping column `slot_id` | Legacy schema | `PromptSlot` / `prompt_slots`, mapping column `slot_variant_id` | Proposed deviation (naming only) | Approved by Joe, 2026-07-28 | Glossary entry in `CONTEXT.md` |

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
