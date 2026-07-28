# The prompt package

`shop.voenix.prompt` is the Kotlin module behind the generation prompts: the
texts the image generator builds its request from, the category structure that
orders them for the storefront, and the **slots** a prompt is composed of.

The module is being migrated from the legacy .NET feature in three slices. This
guide describes what exists today — the slot slice — and says which part is
still to come, so that a reader can tell "not implemented yet" from "does not
exist by design".

| Slice | Content | State |
| --- | --- | --- |
| 1 | slots and slot variants | migrated |
| 2 | prompt categories and subcategories | planned |
| 3 | prompts, example images, price, storefront read, `PromptCatalog` | planned |

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
   |- PromptSlots.kt               Exposed mapping of prompt_slots
   |- PromptSlotVariants.kt        Exposed mapping of prompt_slot_variants
   |- PromptSlotVariantMappings.kt Exposed mapping of the prompt-to-variant table
   |- PromptSlotRepository.kt
   |- PromptSlotVariantRepository.kt
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

Each route can be rejected with `409` for exactly one reason, so the message is
stable per route instead of an error code inside the body:

- writing a name that already exists (in any case) → "… name already exists";
- deleting a slot that still has variants, or a variant a prompt still uses →
  "… cannot be deleted".

A create that names a slot which does not exist is **not** a conflict: it is a
field error on `slotId` and therefore a `400` with the same shape as any other
broken field.

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

The variant *update* declares no foreign-key mapping, because it never writes
the slot column and therefore has no reference that could fail.

## Composition

```kotlin
public fun Application.installPromptModule(database: Database)
public fun RequestValidationConfig.validatePromptRequests()
```

Everything else — the handle `PromptModule`, the factory `createPromptModule`,
the operation interfaces, the services, the repositories, and the Exposed
tables — is `internal`. `Application.kt` installs the module after Article and
registers the three request types in the one Request Validation plugin.

The installation signature grows with the slices: the prompt slice adds Image's
`PublicImageStorage` and Pricing's `PriceCatalog` and returns the exported
`PromptCatalog` capability that the future Generator and Cart migrations
consume. It takes only the database today because nothing else is used yet, and
a parameter no caller can use would be worse than a signature that changes once.

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

Every schema rule is asserted through the SQL state a rejected write produces,
never through a constraint name, so renaming a constraint stays a free change.
