# The prompt package

`shop.voenix.prompt` is the Kotlin module behind the generation prompts: the
texts the image generator builds its request from, the category structure that
orders them for the storefront, and the **slots** a prompt is composed of.

The module was migrated from the legacy .NET feature in three slices, the last
of which was split further. Everything below exists; the table records which
slice brought which part, because the migration record refers to them.

| Slice | Content | State |
| --- | --- | --- |
| 1 | slots and slot variants | migrated |
| 2 | prompt categories and subcategories | migrated |
| 3a | the prompts themselves: admin CRUD and the price they own | migrated |
| 3b | the example image: pre-upload, validation, and file lifecycle | migrated |
| 3c | the prompt reorder and its concurrency rules | migrated |
| 3d | the anonymous storefront list | migrated |
| 3e | the exported `PromptCatalog` capability | migrated |

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
  update has no `slotId` field, so no request body can express the move.
- **Variant names are unique across *all* slots**, not per slot, and
  case-insensitively. A variant should exist exactly once, whatever slot it
  fills.

## The files

```text
modules/prompt/src/shop/voenix/prompt/
|- PromptModule.kt              runtime handle, factory, installation, validation
|- ReorderInput.kt              the one {sourceId, targetId} body of every reorder route
|- Prompt.kt                    the prompt on the wire: the full representation, the
|                               overview row, its small price projection, the shared
|                               create/update input, and the pre-upload answer
|- PromptService.kt             the admin lifecycle: operation interface and service
|- PromptRoutes.kt              /api/admin/prompts
|- PublicPrompt.kt              storefront representation (no promptText, ever) and the
|                               nested {id, name, position} of both category levels
|- PublicPromptService.kt       the storefront read: operation interface and service
|- PublicPromptRoutes.kt        /api/prompts (anonymous)
|- PromptCatalog.kt             the one public type: the exported capability
|- PromptCatalogService.kt      composed text and gross price behind it
|- category/
|  |- PromptCategory.kt         admin representation (id, name, position, active) plus
|  |                            the shared create/update input
|  |- PromptCategoryService.kt  operation interface and service
|  |- PromptCategoryRoutes.kt   /api/admin/prompts/categories
|  |- PromptSubcategory.kt      adds categoryId and description, plus its own input
|  |- PromptSubcategoryService.kt  operation interface, service, and the two field
|  |                               errors on categoryId
|  `- PromptSubcategoryRoutes.kt   /api/admin/prompts/subcategories
|- slot/
|  |- PromptSlot.kt             admin representation (id, name, position, variantCount)
|  |                            plus the shared create/update input
|  |- PromptSlotService.kt      operation interface, validation, normalization, mapping
|  |- PromptSlotRoutes.kt       /api/admin/prompts/slots
|  |- PromptSlotVariant.kt      admin representation, the create input (carries slotId),
|  |                            and the update input (no slotId) that owns the field rules
|  |- PromptSlotVariantService.kt  operation interface and service
|  `- PromptSlotVariantRoutes.kt   /api/admin/prompts/slot-variants
`- persistence/
   |- PromptOrdering.kt         every lock a position sequence needs, that is the three
   |                            global anchors and the category rows, plus the three
   |                            ordering helpers every repository shares: isDenseBy, the dense
   |                            rewrite of a reorder, and the last taken position
   |- StoredPrompt.kt           a read row plus the id of the price it points at
   |- PromptCategoryRepository.kt     the Exposed mapping of prompt_categories, the
   |                                  repository, and its write/delete/order results
   |- PromptSubcategoryRepository.kt  the same three parts for prompt_subcategories
   |- PromptSlotRepository.kt         prompt_slots, plus write and delete results
   |- PromptSlotVariantRepository.kt  prompt_slot_variants, plus the same two results
   |- PromptRepository.kt             prompts and the prompt-to-variant mapping table,
   |                                  plus the write and order results
   |- PublicPromptRepository.kt       the one storefront read, prompt_text never selected
   `- PromptCatalogRepository.kt      the two capability reads, no category join at all,
                                      and the StoredComposition they answer with
```

Declarations share a file when they belong to the same concern: a representation
together with the input a client writes it with, a service together with the
operation interface it implements, a repository together with its Exposed table
and the results it returns. That grouping follows
[Kotlin source file organization](source-file-organization.md), and it changes
nothing a caller sees. What other code addresses is the package a declaration
lives in, never the file.

The sub-packages organize files; they are not visibility boundaries. The
compilation module is the real boundary, so `internal` declarations keep
collaborating across `slot` and `persistence` while staying invisible to every
other module.

## The HTTP contract

The five admin route groups sit behind the shared, fail-closed admin protection
and answer with bare JSON arrays and `201 Created` plus a `Location` header;
the four groups that have a delete answer it with `204 No Content`. The prompts
themselves have no delete route at all; see below. The sixth route,
`GET /api/prompts`, is the storefront one and takes no session at all.

| Route | Operations | Answer |
| --- | --- | --- |
| `/api/admin/prompts/slots` | list, get, create, update, delete | `{id, name, position, variantCount}` in `(position, id)` order |
| `/api/admin/prompts/slot-variants` | list, get, create, update, delete | `{id, slotId, slotName, name, prompt, description, llm, assignedPromptCount}` in slot order, then by name |
| `/api/admin/prompts/categories` | + reorder (`PUT /order`) | `{id, name, position, active}` in `(position, id)` order |
| `/api/admin/prompts/subcategories` | + reorder (`PUT /order`) | `{id, categoryId, name, description, position, active}` in category order, then own position |
| `/api/admin/prompts` | list, get, create, update, reorder (`PUT /order`), example-image pre-upload | list rows and the full prompt, both flat, in `(position, id)` order |
| `/api/prompts?categoryId=` (anonymous) | list | the visible prompts with nested category objects and no prompt text, in `(position, id)` order |

All three reorder routes take the same body, `{"sourceId": 42, "targetId": 8}`,
and answer with the complete new order. The categories and the prompts answer
with all of them, the subcategories with the affected category's list, because
their positions count per category and no other category can have moved. An id
the stored order does not contain is a `404`; the legacy backend answered a
`409` for the categories, which said nothing about what went wrong.

The subcategory relationship is flat on both sides: the request carries
`categoryId` and so does the answer. The legacy backend accepted a flat id and
answered with a nested category object, which made request and response
disagree about the shape of one relationship.

Each route can be rejected with `409` for exactly one reason, so the message is
stable per route instead of an error code inside the body:

- writing a name that already exists (in any case) → "… name already exists";
- deleting a slot that still has variants, or a variant a prompt still uses →
  "… cannot be deleted";
- losing the race for a position on a reorder → "… order changed concurrently,
  please retry".

A create that names a slot which does not exist is **not** a conflict: it is a
field error on `slotId` and therefore a `400` with the same shape as any other
broken field. The same holds for the two subcategory rejections that talk about
its category, an unknown category and a category change while prompts use the
subcategory. Both are field errors on `categoryId`.

## The prompt routes

`/api/admin/prompts` has two properties the other four route groups do not, and
both are the contract rather than an omission:

- **there is no delete route.** A prompt is retired by setting `archived`,
  because carts, orders, and generated images keep referring to it;
- **no prompt write but `PUT /order` answers `409`.** A prompt has no unique
  name, its position is decided under a lock, and every reference a client can
  get wrong is reported as a field error of the field that named it. What is left
  is the one race a client can lose without doing anything wrong, two admins
  moving prompts at the same time, and only the reorder can lose it.

A create body and the answer to it differ in four places, and each difference is
deliberate:

```jsonc
// POST /api/admin/prompts
{ "title": "  Watercolor portrait  ", "promptText": "Paint it.\n",
  "categoryId": 3, "subcategoryId": 7, "slotVariantIds": [12, 9, 12],
  "exampleImageFilename": "6f1c….webp",
  "llm": "  gpt-image-1  ", "active": true, "archived": false,
  "price": { "purchaseVatId": 1, "salesVatId": 1, "salesTotalInputCents": 499 } }

// 201 Created, Location: /api/admin/prompts/42
{ "id": 42, "position": 8, "title": "Watercolor portrait",
  "promptText": "Paint it.\n", "categoryId": 3, "subcategoryId": 7,
  "slotVariantIds": [9, 12], "exampleImageFilename": "6f1c….webp",
  "llm": "gpt-image-1", "active": true,
  "archived": false, "price": { "id": 77, "…": "the complete calculated price" } }
```

1. `price` is a flat input going in and the complete calculated price coming
   out, under the same field name;
2. `slotVariantIds` comes back deduplicated and sorted. Repeating an id asks for
   the same thing twice, which is not a mistake to reject;
3. `title` and `llm` are stored trimmed, while `promptText` keeps its whitespace
   **verbatim**: the composed generation text trims when it reads, so the stored
   text stays what the author typed;
4. `position` is response-only, and no body may carry one.

There is no `priceId` field anywhere in the contract. That is what makes a price
belong to exactly one prompt by construction: ids are only minted while a prompt
is written, so a body that sends one is ignored.

The list is the second representation. It carries the display names a table
needs (`categoryName`, `subcategoryName`) and only the small price projection
`{salesTotalNet, salesTotalGross, salesTotalTax, salesVatRatePercent}` instead of
the twenty fields of a calculated price. Both reads resolve their prices in
**one** batched `PriceCatalog.find`, never one lookup per row.

### The reorder

`PUT /api/admin/prompts/order` moves one prompt to the place another one holds,
with the same body every reorder route of this module takes:

```jsonc
// PUT /api/admin/prompts/order
{ "sourceId": 42, "targetId": 8 }

// 200 OK: the complete new order, in the rows of the list
[ { "id": 42, "position": 8, "title": "Watercolor portrait", "…": "…" } ]
```

Three properties of that exchange are worth naming:

- **the answer is the complete new order**, not the moved prompt. A client that
  moved one row does not have to work out which of the others moved with it;
- **the rows are the list rows**, including the small price projection, so the
  admin table can replace what it shows with what it received;
- **an unknown `sourceId` or `targetId` is `404`**, not a conflict. The legacy
  backend answered `409` for the categories and `404` here; every reorder route
  of this module now answers `404`, because "this id does not exist" is not a
  race.

A lost race answers `409` with the stable message
`Prompt order changed concurrently, please retry`. It is retryable and nothing
was written; how that is decided is the next section.

### The example image

Uploading and saving are two requests. `POST /api/admin/prompts/example-images`
takes a multipart body with a `file` part, converts it to WebP through the image
module's `PublicImageStorage`, stores it in the folder `prompt-example-images`,
and answers with the name:

```jsonc
// 201 Created
{ "filename": "6f1b0f34-6f0a-4a2f-9c1a-2b7f0c9d1e55.webp" }
```

The create or update that follows carries that name in
`exampleImageFilename`, which keeps both write routes plain JSON. A body without
a `file` part and a body larger than 10 MiB are both `400 Validation failed`
with the message on the `file` field. The oversized one is refused while it is
still arriving, because the shared reader stops taking bytes at the limit.
Everything the image storage itself rejects (an unsupported type, a broken file)
comes back as a field error on `file` as well. The part name is the only key
these errors ever use (`FILE_PART_NAME` in `UploadedImage.kt`).

The rule below is not written in this module. It lives once in the image
module's `ExampleImages`, which this service holds one of, for the
`prompt-example-images` folder and under its own logger (see
[`image-package.md`](image-package.md#the-example-image-rule)).

A submitted name is checked twice before the prompt is written, and a rejection
is a field error on `exampleImageFilename`:

1. it must have the shape the storage mints, a UUID with dashes and `.webp`;
2. the file must exist.

Both checks also run for a name the prompt already stores. There is no exemption
for "the value that is already there", which the legacy validation had: a file
is only removed once no prompt names it, so a stored name whose file is gone
means another writer replaced it and deleted the file in between. Writing
that name back would point the row at a picture that does not exist.

After a successful write, the file the prompt stopped naming is deleted, but
only when no other prompt row named it at the moment the write committed:

```text
prompt A: image X            update A to no image  → X still used by B → kept
prompt B: image X            update B to no image  → nobody uses X     → deleted
```

Nothing makes a name exclusive. The pre-upload hands a client one name, and it
may put that name on two prompts, so the "is anybody still using it?" question is
asked inside the write's own transaction, after its statement ran. That is the
only moment where the answer is the state the commit will publish.

Two failures are deliberately not the client's problem: a delete that fails is
logged and the write still answers `200`, and a file that was uploaded but never
named by any prompt stays behind as an accepted orphan. Sweeping those orphans is
a separate feature, the same one the article module waits for.

## The storefront list

`GET /api/prompts` is the one route of this module a customer's browser calls,
and it is registered *outside* the `authenticate` block. Anonymous access is not
a rule the handler applies but the absence of the admin subtree around it. The
path is `/api/prompts`, so the two trees cannot be confused by a reader or by
Ktor.

```jsonc
// GET /api/prompts?categoryId=1  →  200 OK, a bare array
[ { "id": 1, "position": 1, "title": "Watercolor portrait",
    "category":    { "id": 1, "name": "Portraits", "position": 1 },
    "subcategory": { "id": 2, "name": "Adults",    "position": 2 },
    "exampleImageFilename": "6f1b0f34-….webp", "llm": "gpt-image-1",
    "price": { "salesTotalNet": 419, "salesTotalGross": 499,
               "salesTotalTax": 80,  "salesVatRatePercent": 19 } } ]
```

**There is no `promptText` here, and there never is.** The composed generation
text is what this shop sells; an anonymous client that receives it does not have
to buy anything. The rule is not a filter over a shared representation but the
shape of `PublicPrompt` plus a query that does not select the column, and
`PublicPromptIntegrationTest` compares the whole document to keep it that way.
`active`, `archived`, and `priceId` are absent for the smaller reason that only
visible prompts are in the list at all.

The two category levels are **nested objects** here while the admin contract is
flat. That is not an inconsistency: the admin client loads both category lists
itself and can label anything from them, while this list is the storefront's only
source for either. A name it does not get here it cannot get at all.

Four rules decide what the answer contains:

1. **Visibility.** A prompt is listed while it is `active`, not `archived`, its
   category is active, and it either has no subcategory or an active one. The
   generation and cart lookups of `PromptCatalog` deliberately check *only*
   `active && !archived`, so a prompt in a deactivated category stays generatable
   and buyable by id while disappearing from the storefront. That divergence is
   preserved on purpose, not an oversight.
2. **Order.** `(position, id)`, always, with and without the filter. The module
   has one global prompt order, and a filtered view of it is still that order.
   The legacy backend sorted the filtered list by subcategory and title instead,
   which meant the order an admin arranged stopped applying the moment a customer
   picked a category (approved deviation).
3. **`categoryId`.** A value that is not a number is `400 Invalid prompt category
   id`, decided before the operation runs. A number that names no category is
   `[]`. "There is no such category" is an answer, not an error, and a customer
   following a stale link should see an empty list. An absent, empty, or blank
   parameter means no filter: a value that is only whitespace is treated exactly
   like a missing one.
4. **`price`.** The same small projection the admin list carries, resolved in
   **one** batched `PriceCatalog.find` per response and recalculated from the
   current VAT entries on every read. A page without a single price asks the
   pricing module nothing. A prompt whose nullable `price_id` is empty answers
   `"price": null`, never `0`, which is a price a shop may legitimately charge.

## The exported capability

`PromptCatalog` is the only public type of this module besides the two
composition functions. It answers the two questions another module will ask
about a prompt it stores a reference to, and nothing else:

```kotlin
public interface PromptCatalog {
    public suspend fun composedText(promptId: Long): String?
    public suspend fun findSalesGrossPriceCents(promptIds: Set<Long>): Map<Long, Int>
}
```

`composedText` is what the Generator sends to the image model. It is the
prompt's own text followed by the text of every slot variant the prompt is
mapped to, ordered `(slot.position, slot.id, variant.name, variant.id)` and
joined by a **blank line**:

```text
Turn the photo into art.

on a beach

in watercolor
```

The database does the ordering and the read does the trimming. Every part is
trimmed, blank variant texts drop out instead of producing an empty paragraph,
and the answer is `null` when the prompt is unknown, inactive, archived, or has
a blank text. That is one absent case rather than four, because a caller can do
exactly one thing about any of them. Trimming *here* is the counterpart of
storing the prompt text verbatim: the author keeps the whitespace, the model
does not get it.

`findSalesGrossPriceCents` is what the Cart asks before it snapshots a
line. A prompt is in the answer while it is active, not archived, and linked to
a price row; every other id is **absent**. That is the whole rule, and the one
thing it must never do is answer `0`: a shop may legitimately charge nothing for
a prompt, so a `0` meaning "not for sale" could not be told apart from a free
one. Whatever the batch holds, the prices are resolved in one
`PriceCatalog.find` and recalculated from the current VAT, and an empty set is
answered without touching the database.

Both lookups deliberately **ignore the category and subcategory `active`
flags**, unlike the storefront list of the previous section. A prompt in a
deactivated category disappears from the shop while staying generatable and
buyable by id. Deactivating a category hides a group from browsing; it does not
break the carts and generator jobs that already name a prompt inside it. The
divergence is the legacy behavior, kept on purpose (deviation D12 of the
migration record), and `PromptCatalogIntegrationTest` asserts it rather than the
absence of a filter being left to chance.

Nothing of the module's own representations crosses this boundary: `Prompt`,
`PublicPrompt`, `PromptListItem`, and `PromptPrice` stay `internal`, so a
consumer receives a text and an amount instead of a shape it could grow
opinions about.

## How a prompt and its price stay one write

The price is a row of the pricing module, and a prompt owns exactly one:

```kotlin
val price = prices.prepare(input.price)   // validate, resolve VAT, calculate, no database
repository.insert(normalized, price)      // and only then open a transaction
```

`prepare` never touches the `prices` table, so it runs *before* the transaction
and a price that does not calculate is answered without any lock being held. The
writing half, `storeInTransaction` on create and `replaceInTransaction` on
update, runs inside the prompt's own transaction. That is what makes the two
failure directions symmetric: a rejected price never creates a prompt, and a
prompt that fails to be written never leaves a price row behind. Both
directions are proven in `PromptAdminIntegrationTest`, not assumed.

An update writes over the same price row, so the id never churns. One special
case is worth knowing: the `price_id` column is nullable, so a prompt without a
linked price can exist. A valid update **creates and links** a price there
instead of failing on it, while an update that submits no price at all stays a
`400`, because a prompt is something the shop sells.

The field errors of the price keep the path the client sent them at:
`salesVatId` becomes `price.salesVatId`, so nobody has to guess which of the two
objects in the body a rejected field belongs to.

## How the position is decided

Slot positions decide the display order and nothing else. A create appends
behind the last one:

```kotlin
lockSlotOrderingInTransaction()          // queue on the SLOT anchor row
val nextPosition = PromptSlots.maxPositionInTransaction(PromptSlots.position) + 1
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
can only come from a writer that ignored the anchor, a manual database fix for
instance, so the answer is a retryable `409` that leaves the evidence in place.

The rewrite itself is single-phase: it writes each row's final position directly.
Two rows briefly share a position while it runs, and PostgreSQL allows that
because the unique rule on `position` is `DEFERRABLE INITIALLY DEFERRED` and is
therefore only checked at `COMMIT`. The legacy backend needed a two-phase rewrite
into temporary positions instead.

Prompt positions work exactly like the category positions, on their own `PROMPT`
anchor, with one difference: prompts are **never deleted**, so there is no
compaction. A create appends, and `PUT /order` rewrites the sequence.

```kotlin
lockPromptOrderingInTransaction()              // queue on the PROMPT anchor row
val stored = listInTransaction()               // ... and only then read
if (!stored.isDenseBy { row -> row.prompt.position }) return PositionConflict
lockPromptsInTransaction(stored.map { it.prompt.id })   // rows, ascending by id
```

The row locks are taken **after** the read on purpose: what the transaction
decides from is the order it read, and a position another writer changed in the
meantime is exactly what the deferred unique rule catches at `COMMIT`. The
reorder locks no category row at all, because it is the one prompt write that
changes no reference, only positions. The anchor is taken by the writes that
*decide* a position: the create takes it first and the category row after it,
and the reorder takes it alone. An update decides no position, since it keeps
the one the prompt has, so it takes no anchor at all and locks the category row
and then its own prompt row.

Subcategory positions count **per category**, so there is no global anchor for
them: the category row *is* the anchor of its own sequence. A move to another
category is a position change in two sequences at once: it appends in the target
and compacts the source. That is why both rows are locked before anything is
written.

That leaves three kinds of writers locking category rows: the category writers,
which queue on the `CATEGORY` anchor first; the subcategory writers, which never
take that anchor at all; and the two prompt writes that name a category, the
create, which holds the `PROMPT` anchor while it does, and the update, which
holds no anchor. Two rules keep them from waiting on each other:

- **the global anchor is taken before any category row**, and
- **category rows are locked distinct, ascending by id, one statement each**,
  never in the display order a rewrite happens to need.

A violation of the second rule is a deadlock, which nothing maps and which would
surface as a failed request. `PromptCategoryLockOrderConcurrencyIntegrationTest`
is what keeps the rule honest.

## How a failed write is recognized

Never by the name of a constraint. Only by the SQL state PostgreSQL reports,
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
surface as an unexpected failure, not as a mislabelled "name already exists".

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
the only thing the `prompts` statement can violate. The mapping insert
references nothing but slot variants. A single mapping around the whole write
could not tell the three apart, and the client would be told which field to fix
by guesswork.

The one prompt write that maps `23505` is the reorder, and it maps it around the
whole transaction rather than around a statement, because a deferred rule is
only checked at `COMMIT`, so nothing inside the transaction could see it.
Prompts have no unique name, so a `23505` from a create or an update stays the
broken invariant it is: under the anchor the position rule is unreachable there.

The last row is the one that needs the lock to be unambiguous. A subcategory
write has two references that could fail: its category, and the composite key
`prompts(subcategory_id, category_id)` that holds it there. The write locks the
category row first, so while it runs that category cannot disappear and only one
relationship is left to fail. A missing category is not a SQL state at all then:
it is a lock that found no row.

That composite key is also why moving a used subcategory needs no preliminary
read. A prompt references its subcategory *together with* the category, so the
database refuses the move by itself. The legacy `ValidateSelectedSubcategory`
check is gone, not reimplemented.

## Composition

```kotlin
public fun Application.installPromptModule(
    database: Database,
    images: PublicImageStorage,
    prices: PriceCatalog,
): PromptCatalog
public fun RequestValidationConfig.validatePromptRequests()
```

Everything else is `internal`: the handle `PromptModule`, the factory
`createPromptModule`, the operation interfaces, the services, the repositories,
the Exposed tables, and `PromptPrice`. `Application.kt` installs the module
after Article and registers the seven request types in the one Request
Validation plugin.

One installation call registers both trees: the five admin route groups inside
the `authenticate` block, and the storefront route outside it. A storefront that
could be installed without its admin half, or the other way round, would be a
seam nobody needs.

The composition root **binds** the returned `PromptCatalog` to two modules, one
per half of the capability. The cart module has bound
`findSalesGrossPriceCents` since the Cart migration: an add-to-cart request that
names a prompt snapshots its current gross sales price, and a prompt that is
unknown, inactive, or archived makes the add fail. The generator module has
bound `composedText` since the Generator migration of 2026-07-30: it sends that
text to the image model and turns the `null` answer into its own `404`, which is
why this module never had to know which status code an unusable prompt deserves
(see the [Generator package guide](generator-package.md)).

The installation signature grew with the slices: it took `PriceCatalog` with the
prompt slice, Image's `PublicImageStorage` with the example-image slice, and the
`PromptCatalog` return value with the catalog slice. Each parameter arrived with
the slice that used it, because a parameter no caller can use would be worse than
a signature that changes once per slice.

## Tests

The module test source set mirrors the categories the article module
established:

- `PromptSlotInputValidationTest` and `PromptSlotVariantInputValidationTest`:
  the pure field-rule matrix, including the create/update asymmetry;
- `PromptSlotRouteSecurityAndValidationTest` and its variant counterpart:
  the admin subtree rejects anonymous, customer, and CSRF-less requests
  *before* an operation runs, and every result maps to the documented status;
- `PromptSlotAdminIntegrationTest` and `PromptSlotVariantAdminIntegrationTest`:
  the real module on real PostgreSQL: case-insensitive duplicates, the global
  cross-slot variant duplicate, the in-use conflicts, and the counts;
- `PromptSlotConcurrencyIntegrationTest`: two creates that start at the same
  time append `1` and `2` without a retry, and a delete's gap is not reused;
- `PromptSlotSchemaIntegrationTest`: Flyway on an empty database, followed by
  the seeded anchor rows, the `LOWER(name)` rules, the restricting foreign keys,
  and the position rule that is accepted by the statement and rejected by the
  `COMMIT`.

The category slice adds the same categories plus two the slots do not need,
because slots have no reorder and no per-sequence anchor:

- `ReorderInputValidationTest`, `PromptCategoryInputValidationTest`, and
  `PromptSubcategoryInputValidationTest`: the field rules;
- `PromptCategoryRouteSecurityAndValidationTest` and its subcategory
  counterpart: including the reorder route and the `404` for an unknown id;
- `PromptCategoryAdminIntegrationTest` and
  `PromptSubcategoryAdminIntegrationTest`: dense append, compaction after a
  delete, the reorder answer, the cross-category move that appends in the target
  and compacts the source, and the used subcategory that cannot move;
- `PromptCategoryConcurrencyIntegrationTest` and its subcategory counterpart:
  two reorders serialize, a gapped sequence is refused without writing anything,
  and a position written outside the lock makes the reorder fail at `COMMIT`;
- `PromptCategoryLockOrderConcurrencyIntegrationTest`: the ascending-id rule,
  built deterministically: a raw connection holds one category row until both
  writers are visibly waiting;
- `PromptCategorySchemaIntegrationTest`: the per-category `LOWER(name)` rule,
  the restricting foreign keys, the composite subcategory key, and both position
  rules asserted at the `COMMIT` that rejects them.

The prompt slice adds the same categories once more, plus one that no single
module can cover:

- `PromptInputValidationTest`: the field rules, and the two fields the contract
  must **not** have: a body carrying `position` or `priceId` is decoded without
  them;
- `PromptRouteSecurityAndValidationTest`: the admin subtree including the
  pre-upload route, the shapes of both representations, the absent delete route,
  the reorder with the one `409` of this route group, the three upload answers
  (stored, no `file` part, oversized), and the storefront route that answers
  without any session while rejecting only an unparsable `categoryId`;
- `PublicPromptIntegrationTest`: the storefront read against the real module,
  covering the visibility matrix written through the admin routes and read
  anonymously including all three reactivations, the order proven by swapping two positions
  with and without the filter, the whole-document comparison that names
  `promptText` as forbidden, one batched price lookup for one prompt and for
  three, the empty answer that asks the pricing module nothing, the VAT change
  recalculated into the projection, and the missing price row that stays `null`;
- `PromptExampleImageIntegrationTest`: the file lifecycle against the real
  module. It covers the pre-upload answer, a malformed name, an unknown file, a
  stored name whose file vanished, a shared file that survives the prompt dropping it, the
  last reference that takes the file with it, the orphan a rejected write leaves
  behind, and the failing cleanup that does not fail the request;
- `PromptAdminIntegrationTest`: the real module plus the real pricing module on
  real PostgreSQL. It proves both price-atomicity directions, the three
  reference field errors told apart, the replaced mapping set, `[12, 9, 12]` in and `[9, 12]`
  out, the untrimmed prompt text round trip, and the repair of a prompt whose
  price was never linked;
- `PromptConcurrencyIntegrationTest`: the ordering rules against the real
  routes. Concurrent creates append `1..n` instead of reading the same maximum,
  two reorders serialize and each answers a dense order, a create running next to
  a reorder cannot corrupt the sequence, a manually gapped sequence is refused
  before anything is written, and a rotation committed outside the anchor makes
  the reorder lose the deferred unique check at `COMMIT`;
- `PromptSchemaIntegrationTest`: the seeded `PROMPT` anchor, the `NOT NULL`
  prompt text, the bounded title, `UNIQUE (price_id)`, the restricting price
  reference, the mapping key, and the position rule that the statement accepts
  and the `COMMIT` rejects;
- `PromptPricingRelationshipIntegrationTest`: the cross-module half. A price a
  prompt minted is a normal price to the pricing routes, an edit made there is
  what the prompt answers with afterwards, and the row cannot be deleted away
  from the prompt that holds it;
- `PromptCatalogIntegrationTest`: the exported capability against the real
  module. It asserts the composition order proved with slots whose ids run
  against their positions, the blank-line join, the stored text that stays untrimmed while the
  composed one is trimmed, the four cases that answer `null`, the prompt in a
  deactivated category that still resolves while an archived one does not, the
  prompt priced at `0` that is present with the value `0` next to the ineligible
  ids that are absent, one batched price lookup per call with none at all for an
  empty set, and a VAT change recalculated into the gross cents.

Every schema rule is asserted through the SQL state a rejected write produces,
never through a constraint name, so renaming a constraint stays a free change.
