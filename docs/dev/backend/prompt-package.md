# The prompt package

`shop.voenix.prompt` is the Kotlin module behind the generation prompts: the
texts the image generator builds its request from, the category structure that
orders them for the storefront, and the **slots** a prompt is composed of.

The module is being migrated from the legacy .NET feature in three slices, the
last of which is split further. This guide describes what exists today and says
which part is still to come, so that a reader can tell "not implemented yet"
from "does not exist by design".

| Slice | Content | State |
| --- | --- | --- |
| 1 | slots and slot variants | migrated |
| 2 | prompt categories and subcategories | migrated |
| 3a | the prompts themselves: admin CRUD and the price they own | migrated |
| 3b–3e | example images, reorder, the storefront list, `PromptCatalog` | planned |

The whole database schema is already there:
[`V14__create_prompts.sql`](../../../backend/modules/platform/resources/db/migration/V14__create_prompts.sql)
creates all seven tables in one migration. That is deliberate. The slot answers
already contain "how many variants does this slot have" and "how many prompts
use this variant", and a count whose table does not exist yet would be a lie
until the last slice landed.

## What a slot is

A prompt is not one block of text. It is a base text plus a choice per slot:

```text
prompt "Watercolor portrait"
  slot "Background"  -> variant "Meadow"     -> "in a meadow"
  slot "Style"       -> variant "Watercolor" -> "in watercolor"
```

A **slot** is the question ("which background?"), a **slot variant** is one
possible answer together with the text it contributes. The legacy application
called the slot a `PromptSlotType`; the Kotlin module dropped the `Type`,
because the thing is a slot and not a kind of slot.

Two rules of that model are worth remembering, because they are unusual:

- **A variant never changes its slot.** "Watercolor" is an answer to "which
  style?" and cannot become an answer to "which background?". This is why the
  create input and the update input of a variant are two different types: the
  update simply has no `slotId` field, so no request body can express the move.
- **Variant names are unique across *all* slots**, not per slot, and
  case-insensitively. A variant should exist exactly once, whatever slot it
  fills.

## The files

```text
modules/prompt/src/shop/voenix/prompt/
|- PromptModule.kt                 runtime handle, factory, installation, validation
|- ReorderInput.kt                 the one {sourceId, targetId} body of every reorder route
|- Prompt.kt                       admin representation of one prompt, price embedded
|- PromptListItem.kt               the overview row: flat ids, display names, small price
|- PromptPrice.kt                  the small price projection of a list row
|- PromptInput.kt                  shared create/update input, price nested and required
|- PromptOperations.kt
|- PromptService.kt
|- PromptRoutes.kt                 /api/admin/prompts
|- category/
|  |- PromptCategory.kt            admin representation: id, name, position, active
|  |- PromptCategoryInput.kt       shared create/update input
|  |- PromptCategoryOperations.kt
|  |- PromptCategoryService.kt
|  |- PromptCategoryRoutes.kt      /api/admin/prompts/categories
|  |- PromptSubcategory.kt         adds categoryId and description
|  |- PromptSubcategoryInput.kt
|  |- PromptSubcategoryOperations.kt
|  |- PromptSubcategoryService.kt
|  `- PromptSubcategoryRoutes.kt   /api/admin/prompts/subcategories
|- slot/
|  |- PromptSlot.kt                admin representation: id, name, position, variantCount
|  |- PromptSlotInput.kt           shared create/update input
|  |- PromptSlotOperations.kt      the operation interface routes talk to
|  |- PromptSlotService.kt         validation, normalization, result mapping
|  |- PromptSlotRoutes.kt          /api/admin/prompts/slots
|  |- PromptSlotVariant.kt         admin representation of a variant
|  |- PromptSlotVariantInput.kt    create input (carries slotId)
|  |- PromptSlotVariantUpdate.kt   update input (no slotId) and the shared field rules
|  |- PromptSlotVariantOperations.kt
|  |- PromptSlotVariantService.kt
|  `- PromptSlotVariantRoutes.kt   /api/admin/prompts/slot-variants
`- persistence/
   |- PromptOrdering.kt            the lock anchors of the global position sequences
   |- DensePositions.kt            isDenseBy: is the stored order 1..n without a gap?
   |- PromptCategories.kt          Exposed mapping + the per-category lock function
   |- PromptSubcategories.kt       Exposed mapping of prompt_subcategories
   |- PromptCategoryRepository.kt
   |- PromptSubcategoryRepository.kt
   |- PromptSlots.kt               Exposed mapping of prompt_slots
   |- PromptSlotVariants.kt        Exposed mapping of prompt_slot_variants
   |- PromptSlotVariantMappings.kt Exposed mapping of the prompt-to-variant table
   |- Prompts.kt                   Exposed mapping of prompts
   |- StoredPrompt.kt              a read row plus the id of the price it points at
   |- PromptSlotRepository.kt
   |- PromptSlotVariantRepository.kt
   |- PromptRepository.kt
   `- Prompt*WriteResult.kt / Prompt*DeleteResult.kt
```

The sub-packages organize files; they are not visibility boundaries. The
compilation module is the real boundary, so `internal` declarations keep
collaborating across `slot` and `persistence` while staying invisible to every
other module.

## The HTTP contract

Both route groups sit behind the shared, fail-closed admin protection and
answer with bare JSON arrays, `201 Created` plus a `Location` header, and
`204 No Content` for a delete.

| Route | Operations | Answer |
| --- | --- | --- |
| `/api/admin/prompts/slots` | list, get, create, update, delete | `{id, name, position, variantCount}` in `(position, id)` order |
| `/api/admin/prompts/slot-variants` | list, get, create, update, delete | `{id, slotId, slotName, name, prompt, description, llm, assignedPromptCount}` in slot order, then by name |
| `/api/admin/prompts/categories` | + reorder (`PUT /order`) | `{id, name, position, active}` in `(position, id)` order |
| `/api/admin/prompts/subcategories` | + reorder (`PUT /order`) | `{id, categoryId, name, description, position, active}` in category order, then own position |
| `/api/admin/prompts` | list, get, create, update | list rows and the full prompt, both flat, in `(position, id)` order |

Both reorder routes take the same body, `{"sourceId": 42, "targetId": 8}`, and
answer with the complete new order — the categories with all of them, the
subcategories with the affected category's list, because their positions count
per category and no other category can have moved. An id the stored order does
not contain is a `404`; the legacy backend answered a `409` there, which said
nothing about what went wrong.

The subcategory relationship is flat on both sides: the request carries
`categoryId` and so does the answer. The legacy backend accepted a flat id and
answered with a nested category object, which made request and response
disagree about the shape of one relationship.

Each route can be rejected with `409` for exactly one reason, so the message is
stable per route instead of an error code inside the body:

- writing a name that already exists (in any case) → "… name already exists";
- deleting a slot that still has variants, or a variant a prompt still uses →
  "… cannot be deleted".

A create that names a slot which does not exist is **not** a conflict: it is a
field error on `slotId` and therefore a `400` with the same shape as any other
broken field. The same holds for the two subcategory rejections that talk about
its category — an unknown category, and a category change while prompts use the
subcategory — which are field errors on `categoryId`.

## The prompt routes

`/api/admin/prompts` has two properties the other four route groups do not, and
both are the contract rather than an omission:

- **there is no delete route.** A prompt is retired by setting `archived`,
  because carts, orders, and generated images keep referring to it;
- **no prompt write answers `409`.** A prompt has no unique name, its position is
  decided under a lock, and every reference a client can get wrong is reported as
  a field error of the field that named it.

A create body and the answer to it differ in four places, and each difference is
deliberate:

```jsonc
// POST /api/admin/prompts
{ "title": "  Watercolor portrait  ", "promptText": "Paint it.\n",
  "categoryId": 3, "subcategoryId": 7, "slotVariantIds": [12, 9, 12],
  "llm": "  gpt-image-1  ", "active": true, "archived": false,
  "price": { "purchaseVatId": 1, "salesVatId": 1, "salesTotalInputCents": 499 } }

// 201 Created, Location: /api/admin/prompts/42
{ "id": 42, "position": 8, "title": "Watercolor portrait",
  "promptText": "Paint it.\n", "categoryId": 3, "subcategoryId": 7,
  "slotVariantIds": [9, 12], "llm": "gpt-image-1", "active": true,
  "archived": false, "price": { "id": 77, "…": "the complete calculated price" } }
```

1. `price` is a flat input going in and the complete calculated price coming
   out, under the same field name;
2. `slotVariantIds` comes back deduplicated and sorted — repeating an id asks for
   the same thing twice, which is not a mistake to reject;
3. `title` and `llm` are stored trimmed, while `promptText` keeps its whitespace
   **verbatim**: the composed generation text trims when it reads, so the stored
   text stays what the author typed;
4. `position` is response-only, and no body may carry one.

There is no `priceId` field anywhere in the contract. That is what makes a price
belong to exactly one prompt by construction: ids are only minted while a prompt
is written, so a body that sends one is simply ignored.

The list is the second representation. It carries the display names a table
needs (`categoryName`, `subcategoryName`) and only the small price projection
`{salesTotalNet, salesTotalGross, salesTotalTax, salesVatRatePercent}` instead of
the twenty fields of a calculated price. Both reads resolve their prices in
**one** batched `PriceCatalog.find`, never one lookup per row.

## How a prompt and its price stay one write

The price is a row of the pricing module, and a prompt owns exactly one:

```kotlin
val price = prices.prepare(input.price)   // validate, resolve VAT, calculate — no database
repository.insert(normalized, price)      // and only then open a transaction
```

`prepare` never touches the `prices` table, so it runs *before* the transaction
and a price that does not calculate is answered without any lock being held. The
writing half — `storeInTransaction` on create, `replaceInTransaction` on update —
runs inside the prompt's own transaction. That is what makes the two failure
directions symmetric: a rejected price never creates a prompt, and a prompt that
fails to be written never leaves a price row behind. Both directions are proven
in `PromptAdminIntegrationTest`, not assumed.

An update writes over the same price row, so the id never churns. One special
case is worth knowing: the `price_id` column is nullable, so a prompt without a
linked price can exist. A valid update **creates and links** a price there
instead of failing on it — while an update that submits no price at all stays a
`400`, because a prompt is something the shop sells.

The field errors of the price keep the path the client sent them at:
`salesVatId` becomes `price.salesVatId`, so nobody has to guess which of the two
objects in the body a rejected field belongs to.

## How the position is decided

Slot positions decide the display order and nothing else. A create appends
behind the last one:

```kotlin
lockSlotOrderingInTransaction()          // queue on the SLOT anchor row
val nextPosition = maxPositionInTransaction() + 1
```

Reading the maximum without the lock would race: under PostgreSQL's default
`READ COMMITTED` isolation two creates would read the same maximum and write it
twice. Both writers therefore lock the same row in `prompt_ordering` first, and
only the statements *after* the lock see what the other writer committed. The
legacy service instead caught the resulting conflict and retried once; with the
anchor, the conflict it retried cannot happen, so the retry is gone.

Two things slots deliberately do **not** do, unlike the article categories:

- **no reorder route**, and
- **no compaction after a delete**.

A deleted slot leaves its position empty, and the next create appends behind the
highest position rather than filling the gap. Nothing reads the number, only the
order it produces, so closing the gap would move rows for no reason.

Category positions do all three: a create appends, a delete closes the gap it
leaves, and `PUT /order` rewrites the whole sequence. They stay `1..n` without a
gap, and that is a promise the code checks before it writes:

```kotlin
lockCategoryOrderingInTransaction()            // queue on the CATEGORY anchor row
val stored = orderedCategoriesInTransaction()  // ... and only then read
if (!stored.isDenseBy(PromptCategory::position)) return PositionConflict
```

Refusing a gapped sequence instead of repairing it matters, because a rewrite
would move *every* row a client can see although it asked to move one. The gap
can only come from a writer that ignored the anchor — a manual database fix, for
instance — so the answer is a retryable `409` that leaves the evidence in place.

The rewrite itself is single-phase: it writes each row's final position directly.
Two rows briefly share a position while it runs, and PostgreSQL allows that
because the unique rule on `position` is `DEFERRABLE INITIALLY DEFERRED` and is
therefore only checked at `COMMIT`. The legacy backend needed a two-phase rewrite
into temporary positions instead.

Subcategory positions count **per category**, so there is no global anchor for
them: the category row *is* the anchor of its own sequence. A move to another
category is a position change in two sequences at once — it appends in the target
and compacts the source — which is why both rows are locked before anything is
written.

That leaves two kinds of writers locking category rows: the category writers,
which queue on the `CATEGORY` anchor first, and the subcategory writers, which
never take that anchor at all. Two rules keep them from waiting on each other:

- **the global anchor is taken before any category row**, and
- **category rows are locked distinct, ascending by id, one statement each** —
  never in the display order a rewrite happens to need.

A violation of the second rule is a deadlock, which nothing maps and which would
surface as a failed request. `PromptCategoryLockOrderConcurrencyIntegrationTest`
is what keeps the rule honest.

## How a failed write is recognized

Never by the name of a constraint — only by the SQL state PostgreSQL reports,
and by *where* the mapping sits. The unique rule on `position` is
`DEFERRABLE INITIALLY DEFERRED`, which means PostgreSQL checks it at `COMMIT`,
while the unique index on `LOWER(name)` is checked while the statement runs:

```kotlin
executePostgresWrite(uniqueViolation = PromptSlotWriteResult.NameConflict) {
    // one statement: a 23505 raised here can only be the name
}
```

Because the wrapper sits inside the transaction, it cannot see a position
conflict at all. That is the point: a position conflict is impossible under the
anchor, so if one ever happened it would be a broken invariant and should
surface as an unexpected failure — not as a mislabelled "name already exists".

The foreign-key state `23503` is mapped only where exactly one relationship can
fail the statement:

| Statement | Only possible cause | Result |
| --- | --- | --- |
| insert a variant | the slot does not exist | field error `slotId` |
| delete a slot | variants still reference it | `409` still in use |
| delete a variant | prompts still reference it | `409` still in use |
| delete a category | subcategories or prompts reference it | `409` still in use |
| delete a subcategory | prompts reference it | `409` still in use |
| update a subcategory | prompts hold it in its current category | field error `categoryId` |
| write a prompt row | the composite `(subcategory_id, category_id)` key | field error `subcategoryId` |
| insert a slot-variant mapping | the slot variant does not exist | field error `slotVariantIds` |

The variant *update* declares no foreign-key mapping, because it never writes
the slot column and therefore has no reference that could fail.

The last two rows are why a prompt write maps `23503` **per statement** and
never once for the whole write. A prompt has four references, and each is ruled
out or isolated in turn: the category row is locked first (a missing category is
a lock that found no row, not a SQL state), the price id is minted inside the
same transaction and cannot fail, which leaves the composite subcategory key as
the only thing the `prompts` statement can violate — and the mapping insert
references nothing but slot variants. A single mapping around the whole write
could not tell the three apart, and the client would be told which field to fix
by guesswork.

A prompt write maps no `23505` at all: prompts have no unique name, and the
`DEFERRABLE` position rule is unreachable under the anchor.

The last row is the one that needs the lock to be unambiguous. A subcategory
write has two references that could fail — its category, and the composite key
`prompts(subcategory_id, category_id)` that holds it there. The write locks the
category row first, so while it runs that category cannot disappear and only one
relationship is left to fail. A missing category is not a SQL state at all then:
it is a lock that found no row.

That composite key is also why moving a used subcategory needs no preliminary
read. A prompt references its subcategory *together with* the category, so the
database refuses the move by itself — the legacy `ValidateSelectedSubcategory`
check is gone, not reimplemented.

## Composition

```kotlin
public fun Application.installPromptModule(database: Database, prices: PriceCatalog)
public fun RequestValidationConfig.validatePromptRequests()
```

Everything else — the handle `PromptModule`, the factory `createPromptModule`,
the operation interfaces, the services, the repositories, the Exposed tables, and
`PromptPrice` — is `internal`. `Application.kt` installs the module after Article
and registers the seven request types in the one Request Validation plugin.

The installation signature grows with the slices: it takes `PriceCatalog` since
the prompt slice, the example-image slice adds Image's `PublicImageStorage`, and
the catalog slice makes it return the exported `PromptCatalog` capability that
the future Generator and Cart migrations consume. Each parameter arrives with the
slice that uses it, because a parameter no caller can use would be worse than a
signature that changes once per slice.

## Tests

The module test source set mirrors the categories the article module
established:

- `PromptSlotInputValidationTest` and `PromptSlotVariantInputValidationTest` —
  the pure field-rule matrix, including the create/update asymmetry;
- `PromptSlotRouteSecurityAndValidationTest` and its variant counterpart —
  the admin subtree rejects anonymous, customer, and CSRF-less requests
  *before* an operation runs, and every result maps to the documented status;
- `PromptSlotAdminIntegrationTest` and `PromptSlotVariantAdminIntegrationTest` —
  the real module on real PostgreSQL: case-insensitive duplicates, the global
  cross-slot variant duplicate, the in-use conflicts, and the counts;
- `PromptSlotConcurrencyIntegrationTest` — two creates that start at the same
  time append `1` and `2` without a retry, and a delete's gap is not reused;
- `PromptSlotSchemaIntegrationTest` — Flyway on an empty database: the seeded
  anchor rows, the `LOWER(name)` rules, the restricting foreign keys, and the
  position rule that is accepted by the statement and rejected by the `COMMIT`.

The category slice adds the same categories plus two the slots do not need,
because slots have no reorder and no per-sequence anchor:

- `ReorderInputValidationTest`, `PromptCategoryInputValidationTest`, and
  `PromptSubcategoryInputValidationTest` — the field rules;
- `PromptCategoryRouteSecurityAndValidationTest` and its subcategory
  counterpart — including the reorder route and the `404` for an unknown id;
- `PromptCategoryAdminIntegrationTest` and
  `PromptSubcategoryAdminIntegrationTest` — dense append, compaction after a
  delete, the reorder answer, the cross-category move that appends in the target
  and compacts the source, and the used subcategory that cannot move;
- `PromptCategoryConcurrencyIntegrationTest` and its subcategory counterpart —
  two reorders serialize, a gapped sequence is refused without writing anything,
  and a position written outside the lock makes the reorder fail at `COMMIT`;
- `PromptCategoryLockOrderConcurrencyIntegrationTest` — the ascending-id rule,
  built deterministically: a raw connection holds one category row until both
  writers are visibly waiting;
- `PromptCategorySchemaIntegrationTest` — the per-category `LOWER(name)` rule,
  the restricting foreign keys, the composite subcategory key, and both position
  rules asserted at the `COMMIT` that rejects them.

The prompt slice adds the same categories once more, plus one that no single
module can cover:

- `PromptInputValidationTest` — the field rules, and the two fields the contract
  must **not** have: a body carrying `position` or `priceId` is decoded without
  them;
- `PromptRouteSecurityAndValidationTest` — the admin subtree, the shapes of both
  representations, and the absent delete route;
- `PromptAdminIntegrationTest` — the real module plus the real pricing module on
  real PostgreSQL: both price-atomicity directions, the three reference field
  errors told apart, the replaced mapping set, `[12, 9, 12]` in and `[9, 12]`
  out, the untrimmed prompt text round trip, and the repair of a prompt whose
  price was never linked;
- `PromptSchemaIntegrationTest` — the seeded `PROMPT` anchor, the `NOT NULL`
  prompt text, the bounded title, `UNIQUE (price_id)`, the restricting price
  reference, the mapping key, and the position rule that the statement accepts
  and the `COMMIT` rejects;
- `PromptPricingRelationshipIntegrationTest` — the cross-module half: a price a
  prompt minted is a normal price to the pricing routes, an edit made there is
  what the prompt answers with afterwards, and the row cannot be deleted away
  from the prompt that holds it.

Every schema rule is asserted through the SQL state a rejected write produces,
never through a constraint name, so renaming a constraint stays a free change.
