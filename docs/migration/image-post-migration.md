# Image post-migration work

This file records confirmed cross-module work that should survive the initial
Image migration. General Image behavior and decisions stay in
[`image-migration.md`](image-migration.md).

Do not create placeholder tables, routes, or consumer modules while completing
Image. Each owning migration should update this file when it integrates the
Image capability.

## Deferred consumer work

| Owning migration | Legacy dependency | Target integration | Required verification | Status |
| --- | --- | --- | --- | --- |
| Prompt | `PromptExampleImageStorage` uses shared public storage but reads `File.Exists` directly and swallows delete failures | Depend on Image's `PublicImageStorage`; create one validated `prompt-example-images` folder; use store/exists/delete without filesystem paths | Upload validation, generated WebP filename, existence validation, old-file cleanup, and chosen cleanup-failure behavior | Deferred |
| Article — subcategories | `ArticleSubcategoryService` stores and cleans `articles/subcategory-example-images` around database writes | Inject a folder-scoped use of `PublicImageStorage`; retain Article ownership of database compensation and response semantics | Successful create/update/remove plus rollback/compensation when database or image storage fails | Deferred |
| Article — mug variants | `VariantExampleImageStorage` independently validates, detects format, writes original bytes, and knows Image cache layout | Remove duplicated filesystem/cache logic. Decide whether preserve-format uploads are a real requirement or whether variants normalize to WebP like other public uploads | PNG/JPEG/WebP fixtures, alpha, filename contract, replacement cleanup, and cache invalidation | Deferred; needs Joe's format decision |
| Cart | `CartService` writes guest files, stores `generated_edited_images`, accepts PNG/JPEG/WebP/GIF, and checks guest/user ownership | Cart owns the table and an ownership-aware lookup capability; use Image for safe private storage and transformed delivery; compose the guest route only after both sides exist | PostgreSQL ownership tests, guest cookie behavior, upload compensation, public denial for unowned IDs, authenticated-owner access, and full guest route response contract | Deferred |
| Cart | GIF is accepted at upload but rejected by current Image delivery | Decide either to reject GIF before persistence or to define static/animated conversion and delivery | End-to-end upload then `/api/images/guest` retrieval test for the selected rule | Deferred; material contradiction |
| Order | `PdfService` combines the private image root with guest filenames | Consume an Image-owned original-read capability or Cart-owned resolved image content; do not import paths or Image implementation types | PDF with present, missing, and inaccessible generated images; no root-path knowledge in Order | Deferred |

## Operational follow-ups

After the first production rollout, compare WebP encode latency and file sizes
against the legacy system. The Kotlin encoder uses method 4 at quality 0.85,
while legacy ImageSharp used `Level0` (fastest); if derivation latency is a
problem, lower `WEBP_METHOD` in `ImageCodec`.

## Guest route composition direction

The future `/api/images/guest/{size}/{id}` route must preserve this rule:

```text
allow when stored guest token matches
OR when an authenticated user owns the image
otherwise return 404
```

Returning `404` instead of `403` avoids revealing whether another customer's
image ID exists. The route currently creates a guest cookie when no valid
cookie exists; Cart owns that session policy.

Avoid an Image-to-Cart compilation dependency. Cart already needs Image to
store uploads. A preferred acyclic composition is:

1. Image exports image storage/delivery capabilities.
2. Cart depends on Image and exports an ownership-aware guest-image lookup.
3. App composition installs the guest route with both concrete capabilities,
   or Cart owns the thin guest route while delegating transformation to Image.

Choose the final route owner during Cart analysis from the smallest interface;
do not add an unused placeholder port during the initial Image migration.

## Completion condition

This file can be removed after Prompt, Article, Cart, and Order have migrated,
all rows above are resolved, and no module outside Image constructs public,
private, or cache filesystem paths directly.
