# Article post-migration to-do list

This file owns the work the Article migration deliberately left to somebody
else. The migration itself, with every decision behind the changes below, is
recorded in [`article-migration.md`](article-migration.md); the implemented
backend is described in
[`article-package.md`](../dev/backend/article-package.md).

The largest item is the frontend. The Kotlin backend does **not** serve the
legacy article contract, and there is no compatibility layer — legacy is dead
(see the change-freedom rules in `CLAUDE.md`). Everything the Vue frontend in
`../voenix-shop/frontend` must change is listed below, item by item, with the
file that holds it today.

## 1. Frontend adaptation (owner: Joe / frontend follow-up)

### 1.1 Route paths name the article type

The admin article routes moved under a type segment, because there is one table
and one route family per article type now.

| Legacy path | Kotlin path |
| --- | --- |
| `GET/POST /api/admin/articles` | `GET/POST /api/admin/articles/mugs` |
| `GET/PUT/DELETE /api/admin/articles/{id}` | `.../mugs/{id}` |
| `PUT /api/admin/articles/order` | `PUT /api/admin/articles/mugs/order` |
| `POST /api/admin/articles/mug-variant-example-images` | `POST /api/admin/articles/mugs/variant-example-images` |
| `GET /api/articles/categories` | `GET /api/articles/mugs/categories` |

- [x] Retarget every URL in `src/stores/admin/articles.ts`,
  `src/stores/shop/articleCategories.ts`, and their `__tests__` specs. The
  category and subcategory paths (`/api/admin/articles/categories`,
  `/api/admin/articles/subcategories`) are unchanged, as is
  `GET /api/articles/mugs`.

### 1.2 Every list is a bare JSON array

No endpoint answers `{ "items": [...] }` any more.

- [x] `fetchArticles`, `reorderArticles` (`src/stores/admin/articles.ts`),
  `fetchSubcategories`, `reorderSubcategories`
  (`src/stores/admin/articleSubcategories.ts`), `fetchCategories`,
  `reorderCategories` (`src/stores/admin/articleCategories.ts`), and
  `fetchMugs` (`src/stores/shop/mugs.ts`) must read the response body itself
  instead of `data.items`.

### 1.3 The reorder request and its answer

Every reorder — categories, subcategories, mugs — takes the same body and
answers the complete new order:

```json
{ "sourceId": 12, "targetId": 9 }
```

- [x] Replace `ReorderAdminArticlesRequest { sourceArticleId, targetArticleId }`,
  `ReorderAdminArticleCategoriesRequest { sourceCategoryId, targetCategoryId }`,
  and `ReorderAdminArticleSubcategoriesRequest { sourceSubcategoryId,
  targetSubcategoryId }` with one `{ sourceId, targetId }` type.
- [x] Read the answer as the complete, dense list (a bare array). The mug
  reorder answers list *rows* (`MugArticleListItem`), the category and subcategory reorders
  answer their full representation. The subcategory reorder answers only the
  affected category's list.
- [x] An unknown `sourceId` or `targetId` is now `404` on **all three** routes.
  The legacy backend answered `409` for the two structure reorders. A
  subcategory target from another category is also `404`, where the legacy
  backend answered `409` "order conflict".
- [x] Sending both ids equal, a missing id, or a non-positive id is
  `400 Validation failed`.

### 1.4 Subcategories are plain JSON plus a pre-upload

The subcategory CRUD is no longer `multipart/form-data`.

- [x] Delete `toSubcategoryFormData` and the `fetchForm` calls in
  `src/stores/admin/articleSubcategories.ts`; `POST` and `PUT` now send JSON.
- [x] Upload the image first:
  `POST /api/admin/articles/subcategories/example-images` with a
  `multipart/form-data` body containing a `file` part answers
  `201 { "filename": "<uuid>.webp" }`. Put that name into
  `exampleImageFilename` of the create or update body.
- [x] `removeExampleImage` is gone. Send `exampleImageFilename: null` (or omit
  the field) to remove the image.
- [x] The request field is `categoryId`, not `articleCategoryId`.
- [x] The response carries a flat `categoryId` instead of a nested
  `articleCategory` object. `syncSubcategory` currently re-attaches the
  category object client-side; the category list is already in the store, so
  resolve the name from there.
- [x] Pre-upload answers: `201` with the file name, and `400 Validation failed`
  with the message on the `file` field for every rejection — no `file` part, a
  body above 10 MiB, or anything the storage refuses. The status and the field
  name changed on 2026-07-30 (Joe's decision; previously `413` for the
  oversized body and `image` as the field name), so a frontend written against
  the older shape needs adapting here.

### 1.5 The mug variant example image works exactly the same way

- [x] `uploadVariantExampleImage` posts to
  `/api/admin/articles/mugs/variant-example-images` and gets
  `{ "filename": … }` back; the name goes into
  `mugVariants[i].exampleImageFilename` of the mug body. Same three error
  answers as above.
- [x] Every stored image name is a UUID with dashes plus `.webp` — the backend
  always converts. A client may no longer assume the uploaded format survives.

### 1.6 Fields that no longer exist

- [x] **`priceId`** is gone from the admin article contract in both
  directions. The request embeds `price` (a `PriceInput`), the response embeds
  the calculated price whose `id` is the only price id there is. A body that
  sends `priceId` is ignored, not honored.
- [x] **`articleType`** is gone from the article detail *and* from the list
  item. The route path names the type.
- [x] The public mug never carried them either, and additionally has **no
  supplier fields** (`supplierId`, `supplierArticleName`,
  `supplierArticleNumber`) and **no `active` flags** — neither on the mug nor on
  its variants. The list contains only visible mugs, and only their active
  variants.
- [x] The public categories answer is a **bare array of categories with nested
  subcategories**, not `{ "categories": { "MUG": [...] } }`. Remove the
  `allCategories['MUG']` lookup in `src/stores/shop/articleCategories.ts`; the
  route path carries the type.
- [x] In the public mug, `categoryId`, `mugDetails`, and `price` are **always
  present**: an active mug has a price row, and the database enforces it.
  `price` is the gross sales total in integer cents. The legacy `price: 0`
  placeholder for a *missing* price cannot occur any more, so a client-side "not
  really buyable" check on `price === 0` has no successor and can be deleted. A
  `0` that does arrive is a real calculated price — pricing accepts a zero
  amount and rejects only negative ones — and must be displayed as such.

### 1.7 The admin mug list is per type and per-type ordered

- [x] `GET /api/admin/articles/mugs` lists mugs only, in the display order of
  the mug type. Positions count per article type, not globally, so a screen
  that mixed article types has to become one screen per type (today only mugs
  exist).
- [x] List rows carry `categoryName`, `subcategoryName`, and `supplierName`
  next to the ids. `supplierName` is `null` when no supplier is set *and* when
  the supplier module does not answer for the id.

### 1.8 `409` is discriminated by the message, not by a `code`

The shared error body has no `code` field. Each route has exactly one `409`
meaning, so its stable message is the discriminator. These are all of them:

| Route | Message |
| --- | --- |
| `POST /api/admin/articles/categories`, `PUT /api/admin/articles/categories/{id}` | `Article category name already exists` |
| `DELETE /api/admin/articles/categories/{id}` | `Article category is used by subcategories or articles and cannot be deleted` |
| `PUT /api/admin/articles/categories/order` | `Article category order changed concurrently, please retry` |
| `POST /api/admin/articles/subcategories`, `PUT /api/admin/articles/subcategories/{id}` | `Article subcategory name already exists in this article category` |
| `DELETE /api/admin/articles/subcategories/{id}` | `Article subcategory is used by articles and cannot be deleted` |
| `PUT /api/admin/articles/subcategories/order` | `Article subcategory order changed concurrently, please retry` |
| `PUT /api/admin/articles/mugs/order` | `Article order changed concurrently, please retry` |

- [x] Replace the `error.details?.code` switch in
  `src/stores/admin/articleSubcategories.ts`
  (`ARTICLE_SUBCATEGORY_IN_USE_CODE`, `ARTICLE_SUBCATEGORY_NAME_CONFLICT_CODE`)
  with the route that produced the failure: a `409` from `DELETE` is "in use", a
  `409` from `POST` on the collection or `PUT .../{id}` is a name conflict, a
  `409` from `.../order` is the retryable order conflict. The message may be
  shown as it is.
- [x] No mug route except the reorder answers `409` at all.

### 1.9 Rejections that changed their status or shape

- [x] **Moving a subcategory that articles use** is now
  `400 Validation failed` with the field error `categoryId`: `Article
  subcategory is used by articles and cannot be moved to another category`.
  The legacy backend answered `409`. `DELETE` keeps its `409`.
- [x] **An unknown category** on a subcategory create or update is
  `400 Validation failed` with `categoryId`: `Article category does not exist`.
- [x] A mug write reports every reference problem as a field error, never as a
  conflict: `categoryId` — `Article category does not exist`, `subcategoryId` —
  `Article subcategory does not exist in this article category`, `supplierId` —
  `Supplier does not exist`, `mugVariants` — `One or more variants do not
  belong to this article`, `price` — `An active article requires a price`.
- [x] Example-image field errors sit on the JSON path of the request body:
  `exampleImageFilename` for a subcategory,
  `mugVariants[0].exampleImageFilename` for a variant, with `Example image
  filename must be the name of an uploaded image` or `Example image does not
  exist`. The legacy messages contained the file name and used C# member names
  (`MugVariants[0].Name`); every field key is now the JSON path.
- [x] Invalid ids are `400 Invalid article id`, `400 Invalid article category
  id`, `400 Invalid article subcategory id`; unknown ids are `404 Article not
  found`, `404 Article category not found`, `404 Article subcategory not
  found`.

### 1.10 Rules an editor screen should enforce before saving

The database refuses these, so the backend answers a field error rather than
storing a half-valid article. The admin form should not let a user get there:

- [x] An **active** mug needs a price, a category, and complete mug details
  (the legacy backend allowed an active article without a category).
- [x] Mug details are all-or-nothing: the four measurements are stored
  together, and the optional ones may only be set when they are present.
- [x] At most one variant may be the default, and the variant array is the
  complete intended state — a stored variant the array omits is deleted
  together with its example image.
- [ ] Category and subcategory names are unique **case-insensitively**
  (subcategory names within their category). The legacy subcategory rule was
  case-sensitive.

## 2. Orphaned example-image sweep (owner: Joe, separate feature)

A pre-upload that is never saved leaves a file nobody refers to. That is the
accepted-orphan policy of legacy ADR 0001 and it stays.

- [ ] Build a cleanup job that deletes public image files older than a
  threshold which are referenced by neither
  `article_mug_variants.example_image_filename` nor
  `article_subcategories.example_image_filename`. It is a separate feature, not
  part of any article write path; the write paths only delete files that a row
  *stopped* referring to, after the commit, best effort.
- [ ] The Prompt migration added a third column of the same kind,
  `prompts.example_image_filename` (folder `prompt-example-images`), with the
  same accepted-orphan policy and the same shared-file check. The sweep has to
  cover it too; the item stays here because the job is one feature, not one per
  module.

### 2.1 One file name, two rows

Nothing in the schema says that an example image belongs to exactly one row.
The pre-upload answers with a file name, and a client may put that same name
into two variants or two subcategories. The write paths therefore ask, inside
their own transaction, whether any other row of the owning table still names
the file before they report it as obsolete — so dropping one of two references
no longer deletes the picture the other one shows. That check answers for the
moment of the commit; a row written afterwards can name the file again.

- [ ] The better long-term answer is to let the database own the rule: a
  partial unique index on `example_image_filename WHERE example_image_filename
  IS NOT NULL`, on **both** tables. Then a name belongs to one row by
  construction, the reference check becomes unnecessary, and a client that
  submits a name twice gets a conflict instead of a shared file. It is a
  migration plus one new failure answer per write, which is why it was not part
  of the Article migration itself.
- [ ] The subcategory half is a consequence of the approved
  multipart→pre-upload deviation. The legacy subcategory endpoints took the
  file itself, so no client could ever name a file another subcategory already
  used; with the pre-upload it can. The mug variants had the same gap in the
  legacy backend already.

## 3. Order snapshot of production data (owner: Order migration)

Decided by Joe on 2026-07-27 and delivered by the Order migration on
2026-07-31 (see [`order-migration.md`](order-migration.md)): `order_items`
carries the `supplier_article_number` and the five measurement columns,
`OrderService.place` fills them from one `ArticleCatalog.find` call, and
`OrderProductionSourceTest` proves that a catalog change afterwards leaves them
untouched. One half was decided differently and is deviation D24: the
**supplier id** is deliberately *not* snapshotted but resolved live at
production time, so an article whose supplier is assigned late keeps its
production request repairable.

- [x] `order_items` must snapshot the supplier article number and the mug
  layout measurements **at checkout** instead of reading mutable article master
  data at production time. Legacy `PdfService` read them live
  (`db.Articles.Include(a => a.MugDetail)`), so editing an article silently
  changed the layout of orders placed before the edit.
- [x] The data comes from `ArticleCatalog.find(references)`: a batch of
  `ArticleVariantReference(articleId, variantId)` pairs in, a map of
  `CatalogVariant` out (article type, article and variant name, `purchasable`,
  gross price in cents, supplier id and article number, and the five layout
  measurements a `ProductionItem` needs). Both halves of the reference are part
  of the key — a variant that belongs to another article is *unknown*, not
  resolved.
- [x] Order owns the adapter to `ProductionItem`; `article` deliberately does
  not depend on `production`. `OrderService.productionData` is that adapter.

## 4. Legacy ADR 0007 is superseded

The legacy repository documents the global article display order in ADR 0007
(`../voenix-shop`). That decision no longer describes the system: positions are
dense and unique **per article type table**, and the two-phase position rewrite
was replaced by a single-phase rewrite under a deferrable unique constraint.
The legacy repository is read-only for this migration, so the supersession is
recorded here and in [`article-migration.md`](article-migration.md) instead of
in the legacy file.
