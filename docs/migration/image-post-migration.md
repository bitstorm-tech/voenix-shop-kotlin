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
| Prompt | `PromptExampleImageStorage` uses shared public storage but reads `File.Exists` directly and swallows delete failures | Depend on Image's `PublicImageStorage`; create one validated `prompt-example-images` folder; use store/exists/delete without filesystem paths | Upload validation, generated WebP filename, existence validation, old-file cleanup, and chosen cleanup-failure behavior | Done (Prompt slice 3b): example images are pre-uploaded through `PublicImageStorage` into `prompt-example-images`; a submitted name is validated by shape (UUID with dashes plus `.webp`, no legacy `png|jpe?g` and no exemption for the stored value) and by existence; a replaced file is deleted after the commit only when no other prompt row references it, a failing delete is logged and never surfaced, and orphaned pre-uploads are accepted (`PromptExampleImageIntegrationTest`). The multipart reader that both consumers use was promoted from Article into the `image` module in the same slice |
| Article — subcategories | `ArticleSubcategoryService` stores and cleans `articles/subcategory-example-images` around database writes | Inject a folder-scoped use of `PublicImageStorage`; retain Article ownership of database compensation and response semantics | Successful create/update/remove plus rollback/compensation when database or image storage fails | Done (Article ticket T4): the upload became a separate pre-upload route, the subcategory write only refers to a stored file name, and obsolete files are deleted after the commit while orphans are accepted |
| Article — mug variants | `VariantExampleImageStorage` independently validates, detects format, writes original bytes, and knows Image cache layout | Remove duplicated filesystem/cache logic. Decide whether preserve-format uploads are a real requirement or whether variants normalize to WebP like other public uploads | PNG/JPEG/WebP fixtures, alpha, filename contract, replacement cleanup, and cache invalidation | Done (Article ticket T5): Joe decided on 2026-07-27 that there is one image pipeline. Variant example images are pre-uploaded through `PublicImageStorage` into `articles/mugs/variant-example-images`, always converted to WebP with a UUID-with-dashes name, so the module holds no format detection, no filesystem path, and no cache knowledge. Stored names are validated by shape and existence, replaced files are deleted after the commit, and orphans are accepted (`MugArticleAdminIntegrationTest`, `ExampleImageUploadTest`) |
| Cart | `CartService` writes guest files, stores `generated_edited_images`, accepts PNG/JPEG/WebP/GIF, and checks guest/user ownership | Cart owns the table and an ownership-aware lookup capability; use Image for safe private storage and transformed delivery; compose the guest route only after both sides exist | PostgreSQL ownership tests, guest cookie behavior, upload compensation, public denial for unowned IDs, authenticated-owner access, and full guest route response contract | Image side done (Cart ticket T2): `PrivateImageStorage` stores print images in the image-owned `print-images` folder, and `installGuestImageRoute` installs `GET /api/images/guest/{size}/{id}` against the `GuestImageResolver` port. Cart side done (Cart ticket T4, 2026-07-30): the `print_images` table, the resolver implementation, and the composition of the guest route are in place; see [`cart-migration.md`](cart-migration.md) |
| Cart | GIF is accepted at upload but rejected by current Image delivery | Decide either to reject GIF before persistence or to define static/animated conversion and delivery | End-to-end upload then `/api/images/guest` retrieval test for the selected rule | Resolved (Joe, 2026-07-29): GIF is rejected before persistence. `PrivateImageStorage` refuses it on the declared content type and again while decoding (`PrivateImageStorageTest`) |
| Order | `PdfService` combines the private image root with guest filenames | Consume an Image-owned original-read capability; the consumer constructs no path and knows no root — it hands over the file names it stored and receives ready `Path` values | PDF with present, missing, and inaccessible generated images; no root-path knowledge in Order | Done (Order ticket T7, 2026-07-31): `PrivateImageStorage.originalPaths(Set<String>): OperationResult<Map<String, Path>>` resolves stored names to readable originals in one call. The root, the image-owned `print-images` folder, and the containment check stay inside `ImageService`; the order module stores a name, hands the set over, and passes the returned `Path` to `ProductionItem.imagePath`. Set in, map out like `ArticleCatalog.find`: a name the storage cannot answer for is **absent**, which the order module turns into `imagePath = null` and production retries (`PrivateImageStorageTest`, `OrderProductionSourceTest`) |

## Operational follow-ups

After the first production rollout, compare WebP encode latency and file sizes
against the legacy system. The Kotlin encoder uses method 4 at quality 0.85,
while legacy ImageSharp used `Level0` (fastest); if derivation latency is a
problem, lower `WEBP_METHOD` in `ImageCodec`.

## Guest route composition direction

Decided and built by Cart ticket T2 (2026-07-30): **image owns the route**, and
the ownership question travels the other way through the `GuestImageResolver`
port, so there is no image-to-cart dependency. The route is installed by its own
composition step, `installGuestImageRoute(images, guestTokens, resolver)`, which
the application calls after the cart module exists. The original reasoning is
kept below because it is what the rule protects.

The `/api/images/guest/{size}/{id}` route must preserve this rule:

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

Option 2 with the route staying in Image is what was built: Cart exports the
resolver, Image keeps the transformation and the response contract.

## Completion condition

This file can be removed after Prompt, Article, Cart, and Order have migrated,
all rows above are resolved, and no module outside Image **constructs** public,
private, or cache filesystem paths or knows a storage root. That is the precise
rule, and it is not "no module ever holds a `Path`": since the Order migration
one capability, `PrivateImageStorage.originalPaths`, deliberately returns ready
`Path` values, because a consumer that has to read the bytes would otherwise
have to combine a root with a file name itself. Receiving a resolved path is
allowed; deriving one is not.

Both Article rows and the Prompt row are closed since 2026-07-28, the Cart rows
since 2026-07-30, and the Order row since 2026-07-31. Every row above is
resolved; the file is kept until the remaining migrations confirm they add no
new consumer.

One thing the Article migration deferred instead of solving belongs to Image's
neighborhood and is recorded in
[`article-post-migration.md`](article-post-migration.md): a sweep for public
image files that no article variant and no subcategory refers to any more. It
is accepted-orphan policy per legacy ADR 0001 and a separate feature, not a
missing Image capability.
