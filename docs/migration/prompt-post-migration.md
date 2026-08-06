# Prompt post-migration to-do list

This file owns the work the Prompt migration deliberately left to somebody else.
The migration itself, with every decision behind the changes below, is recorded
in [`prompt-migration.md`](prompt-migration.md); the implemented backend is
described in [`prompt-package.md`](../dev/backend/prompt-package.md).

The largest item is the frontend. The Kotlin backend does **not** serve the
legacy prompt contract, and there is no compatibility layer — legacy is dead
(see the change-freedom rules in `CLAUDE.md`). Everything the Vue frontend in
`frontend/` must change is listed below, item by item, with the file that holds
it today.

## 1. Frontend adaptation (owner: Joe / frontend follow-up)

### 1.1 The slot route path and the `slotId` field name

`PromptSlotType` is called `PromptSlot` now, and the route and the field follow
the name (deviation D2).

| Legacy path | Kotlin path |
| --- | --- |
| `GET/POST /api/admin/prompts/slot-types` | `GET/POST /api/admin/prompts/slots` |
| `GET/PUT/DELETE /api/admin/prompts/slot-types/{id}` | `.../slots/{id}` |

- [x] Retarget the four `slot-types` URLs in `src/stores/admin/promptSlots.ts`
  and their `__tests__` specs. The other five route groups keep their paths:
  `/api/admin/prompts/slot-variants`, `/api/admin/prompts/categories`,
  `/api/admin/prompts/subcategories`, `/api/admin/prompts`, and the storefront
  `GET /api/prompts`.
- [x] A slot variant no longer carries a nested `slotType` summary object. It
  carries a flat `slotId` and `slotName` instead, and the create body sends
  `slotId`. The update body has **no** `slotId` at all: a variant cannot change
  its slot, so there is no field to send.

### 1.2 Every list is a bare JSON array

No endpoint answers `{ "items": [...] }` any more (deviation D1).

- [x] `fetchSlotTypes`, `fetchSlotVariants` (`src/stores/admin/promptSlots.ts`),
  `fetchCategories`, `reorderCategories`, `fetchSubcategories`,
  `reorderSubcategories` (`src/stores/admin/promptCategories.ts`),
  `fetchPrompts`, `reorderPrompts` (`src/stores/admin/prompts.ts`), and the
  storefront `fetchPrompts` (`src/stores/shop/prompts.ts`, currently
  `prompts.value = data.items`) must read the response body itself instead of
  `data.items`.

### 1.3 The reorder request and its answer

All three reorder routes — categories, subcategories, prompts — take the same
body and answer the complete new order (deviation D3):

```json
{ "sourceId": 12, "targetId": 9 }
```

- [x] Replace `ReorderAdminPromptsRequest { sourcePromptId, targetPromptId }`,
  `ReorderAdminPromptCategoriesRequest { sourceCategoryId, targetCategoryId }`,
  and `ReorderAdminPromptSubcategoriesRequest { sourceSubcategoryId,
  targetSubcategoryId }` with one `{ sourceId, targetId }` type.
- [x] Read the answer as the complete, dense list (a bare array). The prompt
  reorder answers list *rows* including the small price projection, the category
  reorder answers all categories, and the subcategory reorder answers only the
  affected category's list.
- [x] An unknown `sourceId` or `targetId` is `404` on **all three** routes. The
  legacy backend answered `409` for the categories and the subcategories, and a
  subcategory target from another category was a `409` "order conflict" as well;
  it is a `404` now.
- [x] Sending both ids equal, a missing id, or a non-positive id is
  `400 Validation failed`.
- [x] A genuinely lost race is still `409`, and it is retryable: the whole
  sequence is refused and nothing was written.

### 1.4 Admin responses are flat, the storefront keeps its nested objects

The admin contract carries ids, the storefront carries objects (deviation D4).
That is not an inconsistency: the admin client loads both category lists itself,
while the storefront has no other source for a category name.

- [x] The admin prompt list row is
  `{ id, position, title, categoryId, categoryName, subcategoryId,
  subcategoryName, exampleImageFilename, llm, active, archived, price }`. The
  nested `category` and `subcategory` objects of `AdminPromptListItemDto` are
  gone; the display names come with the row.
- [x] The admin prompt detail adds `promptText`, `slotVariantIds`, and the full
  calculated `price`, and it is flat in the same way.
- [x] The admin subcategory answers `categoryId` instead of a nested
  `promptCategory` object, in both directions — `syncSubcategory` in
  `src/stores/admin/promptCategories.ts` currently re-attaches the category
  object client-side and can resolve the name from the category list instead.
- [x] The **storefront** prompt keeps `category` and `subcategory` as
  `{ id, name, position }` objects, exactly as before.

### 1.5 The storefront contract

```jsonc
// GET /api/prompts?categoryId=1  →  200 OK, a bare array
[ { "id": 1, "position": 1, "title": "Watercolor portrait",
    "category":    { "id": 1, "name": "Portraits", "position": 1 },
    "subcategory": { "id": 2, "name": "Adults",    "position": 2 },
    "exampleImageFilename": "6f1b0f34-….webp", "llm": "gpt-image-1",
    "price": { "salesTotalNet": 419, "salesTotalGross": 499,
               "salesTotalTax": 80,  "salesVatRatePercent": 19 } } ]
```

- [x] The list is filtered by an optional `categoryId` query parameter. An
  absent or empty parameter means "no filter", a value that is not a number is
  `400 Invalid prompt category id`, and a number that names no category is an
  empty array rather than an error.
- [x] The order is `(position, id)` **with and without** the filter. The legacy
  backend sorted the filtered list by subcategory and title, so a client that
  re-sorted to compensate must stop doing that (approved deviation).
- [x] `salesVatRatePercent` is an **integer** percentage now; the legacy DTO
  declared it `decimal` and could serialize `19.0`. A client that formats it with
  decimals should be checked.
- [x] `price` may be `null` when a prompt has no price row, and `0` is a real
  price rather than a placeholder for "unknown". Do not treat `0` as "not
  buyable".
- [x] The storefront answer never contains `promptText`, and never did — the
  point is that it now cannot: the query does not even select the column.

### 1.6 The example image is a pre-upload

Uploading and saving are two requests, and the path is unchanged:

- [x] `POST /api/admin/prompts/example-images` with a `multipart/form-data` body
  containing a `file` part answers **`201`** (the legacy backend answered `200`)
  with `{ "filename": "<uuid>.webp" }`. Put that name into
  `exampleImageFilename` of the create or update body.
- [x] Every stored name is a UUID **with dashes** plus `.webp`. The backend
  always converts, so a client may no longer assume that the uploaded format or
  file name survives, and a hand-written name in `png`/`jpg` shape is rejected —
  the legacy regex accepted `png|jpe?g|webp`.
- [x] Pre-upload errors: `400 Validation failed` with the message on the `file`
  field for every rejection — no `file` part, a body above 10 MiB, or an
  unsupported or broken image. The status and the field name changed on
  2026-07-30 (Joe's decision; previously `413` for the oversized body and
  `image` as the field name), so a frontend written against the older shape
  needs adapting here.
- [x] A rejected name in a prompt write is a field error on
  `exampleImageFilename`: `Example image filename must be the name of an
  uploaded image` or `Example image does not exist`. There is no exemption for
  the name the prompt already stores — if that file is gone, the write is
  refused rather than pointing the row at a missing picture.
- [x] Send `exampleImageFilename: null` (or omit it) to remove the image; there
  is no separate remove call.

### 1.7 Fields that no longer exist

- [x] **`priceId`** is gone from the admin prompt contract in both directions.
  The request embeds `price` (a `PriceInput`), the response embeds the
  calculated price whose `id` is the only price id there is. A body that sends
  `priceId` is ignored, not honored.
- [x] **`position`** is response-only. No create or update body may carry one;
  ordering is changed exclusively through `PUT /api/admin/prompts/order`.
- [x] The six `*ListResponse` wrappers and `AdminPromptSlotTypeSummaryDto` have
  no successor at all (see 1.1 and 1.2).
- [x] There is **no delete route for prompts**, as in the legacy backend: a
  prompt is retired by setting `archived`, because carts, orders, and generated
  images keep referring to it. `DELETE /api/admin/prompts` answers `405` and
  `DELETE /api/admin/prompts/{id}` answers `404`.

### 1.8 `409` is discriminated by the message, not by a `code`

The shared error body has no `code` field. Each route has exactly one `409`
meaning, so its stable message is the discriminator. These are all of them:

| Route | Message |
| --- | --- |
| `POST /api/admin/prompts/slots`, `PUT /api/admin/prompts/slots/{id}` | `Prompt slot name already exists` |
| `DELETE /api/admin/prompts/slots/{id}` | `Prompt slot is used by slot variants and cannot be deleted` |
| `POST /api/admin/prompts/slot-variants`, `PUT .../slot-variants/{id}` | `Prompt slot variant name already exists` |
| `DELETE /api/admin/prompts/slot-variants/{id}` | `Prompt slot variant is used by prompts and cannot be deleted` |
| `POST /api/admin/prompts/categories`, `PUT .../categories/{id}` | `Prompt category name already exists` |
| `DELETE /api/admin/prompts/categories/{id}` | `Prompt category is used by subcategories or prompts and cannot be deleted` |
| `PUT /api/admin/prompts/categories/order` | `Prompt category order changed concurrently, please retry` |
| `POST /api/admin/prompts/subcategories`, `PUT .../subcategories/{id}` | `Prompt subcategory name already exists in this prompt category` |
| `DELETE /api/admin/prompts/subcategories/{id}` | `Prompt subcategory is used by prompts and cannot be deleted` |
| `PUT /api/admin/prompts/subcategories/order` | `Prompt subcategory order changed concurrently, please retry` |
| `PUT /api/admin/prompts/order` | `Prompt order changed concurrently, please retry` |

- [x] Replace the `error.details?.code` check in `src/stores/admin/prompts.ts`
  (`invalid_price_request`) and any other `code` switch with the route that
  produced the failure. A rejected price is a `400 Validation failed` with field
  errors under `price.*` — for example `price.salesVatId` — so
  `hasPriceValidationErrors` is the whole check now.
- [x] **No prompt write answers `409`** except the reorder. Every reference a
  client can get wrong is a field error.

### 1.9 Rejections that changed their status or shape

- [x] A prompt write reports every reference problem as a field error:
  `categoryId` — `Prompt category does not exist`, `subcategoryId` — `Prompt
  subcategory does not exist in this prompt category`, `slotVariantIds` — the
  variant does not exist, `exampleImageFilename` — see 1.6.
- [x] **Moving a subcategory that prompts use** is `400 Validation failed` with
  the field error `categoryId`: `Prompt subcategory is used by prompts and
  cannot be moved to another category`. An unknown category on a subcategory
  write is `400` with `categoryId`: `Prompt category does not exist`. Done by
  issue #98: `PromptCategoryValidationError` in
  `frontend/src/stores/admin/promptCategories.ts` carries the backend's field
  messages, and the subcategory dialog renders them on the matching input.
- [x] Invalid ids are `400 Invalid prompt id`, `400 Invalid prompt category id`,
  `400 Invalid prompt subcategory id`, `400 Invalid prompt slot id`, `400
  Invalid prompt slot variant id`; unknown ids are the matching `404 … not
  found`.
- [x] Every field key is the JSON path of the request body (`price.salesVatId`,
  `slotVariantIds`), never a C# member name.

### 1.10 Rules an editor screen should enforce before saving

- [x] A prompt **always** needs a price: create mints one, update rejects a body
  without one. The screen should not offer saving without it.
- [x] `title` is at most 255 characters (the legacy column was unbounded,
  deviation D5).
- [x] Slot names and slot-variant names are unique **case-insensitively**;
  variant names are unique across *all* slots, not per slot. Category names are
  unique case-insensitively, subcategory names within their category — the
  legacy subcategory rule was case-sensitive (deviation D7). Done by issue #98:
  each of these is its own `409` error type
  (`PromptSlotNameConflictError`, `PromptSlotVariantNameConflictError`,
  `PromptCategoryNameConflictError`, `PromptSubcategoryNameConflictError`), so
  the editor shows the duplicate-name message on the name field instead of a
  generic failure. The uniqueness itself stays the backend's rule; the frontend
  does not pre-check it.
- [x] `slotVariantIds` may be empty, must be positive, and is **deduplicated**
  rather than rejected: `[12, 9, 12]` comes back as `[9, 12]`. An editor should
  not warn about a repeated selection.
- [x] `title` and `llm` come back trimmed, while `promptText` keeps its
  whitespace verbatim — the composed generation text trims when it is read. A
  diff view that compares what it sent with what it got back must expect exactly
  this.

## 2. Orphaned example-image sweep (owner: Joe, separate feature)

`prompts.example_image_filename` is the third column of pre-uploaded public
images, next to the two the Article migration produced. The sweep is one
feature, not one per module, and the item therefore lives in
[`article-post-migration.md`](article-post-migration.md) section 2, which
already names the prompt column and the folder `prompt-example-images`.

- [ ] Nothing to do here; the item is only cross-referenced so that a reader of
  this file does not conclude that prompt orphans are unowned.

## 3. Open points this migration recorded and did not solve

- [ ] **The nullable `prompts.price_id` versus the required price.** A prompt
  always gets a price through the admin routes, but the column is nullable
  because `pricing-post-migration.md` asked for it, and a valid update repairs a
  prompt whose price is missing (deviation D10). Once Cart exists and it is
  clear what an unpriced prompt means to a buyer, revisit this with a CHECK
  constraint in the shape of the article module's "an active article requires a
  price". Recorded as not blocking.
- [ ] **Test doubles copied between modules.** `CountingDataSource`,
  `CountingPriceCatalog`, and `RecordingPublicImageStorage` now exist in the
  article *and* the prompt test source sets, because a module cannot depend on
  another module's test sources. Promoting them into `test-support` is a real
  option, but it touches every module that holds a copy, so it was deliberately
  not done inside a slice ticket (slice 3d, decision 4). It belongs to a
  test-support change of its own.
- [ ] **A price delete route does not exist.** The pricing module exposes no
  delete endpoint; a price is deleted by the owner that holds it, inside the
  owner's transaction. `PromptPricingRelationshipIntegrationTest` therefore
  proves the relationship through the routes that do exist plus the `RESTRICT`
  rule. If a price delete route is ever wanted, it belongs to the pricing module
  and needs its own answer for a price a prompt still holds.

## 4. Consumers of the exported capability (both bound since 2026-07-30)

The module exports `PromptCatalog`. Both halves are bound since 2026-07-30: the
Cart migration bound the price half (see
[`cart-migration.md`](cart-migration.md)) and the Generator migration bound the
text half (see [`generator-migration.md`](generator-migration.md)). Neither
consumer needed anything else from this module:

- [x] **Generator** binds `composedText(promptId)` — the prompt's text plus its
  slot-variant texts, ordered by slot and joined by a blank line, or `null` when
  the prompt is unknown, inactive, archived, or textless. The legacy
  `IPromptService.GetPromptTextAsync` threw `PromptNotFoundException` for all
  four; the Kotlin capability answers `null`, so Generator decides the status
  code its own contract needs. Bound on 2026-07-30: `GeneratorService` turns the
  `null` into its own `404` and an unexpected database failure of the lookup
  into a `500`, never into the `402` of an empty balance.
- [x] **Cart** binds `findSalesGrossPriceCents(promptIds)` — the gross sales
  amount in integer cents per usable prompt, batched. An id that is unknown,
  inactive, archived, or unpriced is **absent** from the map; it is never `0`,
  because `0` is a price a shop may legitimately charge. Legacy
  `CartService.GetPromptPriceAsync` answered `null` per prompt and the caller
  turned it into "not available"; the batched map keeps that distinction and
  removes the per-line query.
- [ ] Both lookups deliberately ignore the category and subcategory `active`
  flags that the storefront list checks (deviation D12), so a prompt in a
  deactivated category stays generatable and buyable by id. A consumer that
  wants the storefront rule must ask the storefront list, not the capability.
