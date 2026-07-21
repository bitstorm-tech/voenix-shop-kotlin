# Image module migration

This file is the task brief and durable decision record for migrating the
legacy Image feature. General migration rules remain in
[`module-migration-guide.md`](module-migration-guide.md).

## Task parameters

Target module:

`Image`

Source project:

`/Users/joe/projects/joto-ai/voenix-shop`

Source feature:

`/Users/joe/projects/joto-ai/voenix-shop/backend/Voenix.Api/Features/Image`

Target project:

`/Users/joe/projects/joto-ai/voenix-shop-kotlin`

Target package:

`/Users/joe/projects/joto-ai/voenix-shop-kotlin/backend/modules/image/src/shop/voenix/image`

Analysis checkpoint:

`wait-for-approval`

Analysis approval:

`2026-07-16`

Known consumers:

- The Vue frontend constructs public image URLs under
  `/api/images/public/{size}/...` for prompt, article subcategory, and mug
  variant images.
- The Vue frontend uses `/api/images/guest/{size}/{id}` for cart and order
  previews.
- Legacy Prompt uses `IPublicImageStorageService` and also reads the public
  filesystem directly for `exists` checks.
- Legacy Article subcategory storage uses `IPublicImageStorageService`; mug
  variant storage independently duplicates image validation, filesystem writes,
  and cache cleanup.
- Legacy Cart writes guest uploads directly into the private image root and
  owns the `generated_edited_images` records and ownership checks.
- Legacy Order reads private image paths directly when generating PDFs.

Approved deviations from current behavior:

- Image route errors use the shared Kotlin `ApiError` contract instead of
  reproducing ASP.NET ProblemDetails.
- A derived image is regenerated when its original was updated at the same
  path and is newer than the cached file.
- Image decoding is bounded to 40 megapixels in addition to the existing
  10 MiB compressed-upload limit.

Explicitly deferred work:

- The guest-image route, guest token behavior, and `generated_edited_images`
  persistence stay with the future Cart migration.
- Prompt, Article, Cart, and Order adopt the Image capability when their owning
  modules migrate; details are recorded in
  [`image-post-migration.md`](image-post-migration.md).

## Required instructions and sources

The analysis follows
[`module-migration-guide.md`](module-migration-guide.md). The primary legacy
evidence is:

- `Features/Image/Controllers/ImageController.cs`;
- `Features/Image/ImageSize.cs`;
- `Features/Image/Services/ImageService.cs`;
- `Features/Image/Services/PublicImageStorageService.cs`;
- `Features/Image/Services/GuestImageService.cs`;
- the complete `Voenix.Api.Tests/Features/Image` test package;
- the Image mappings in `DomainExceptionHandler` and its tests;
- `ImageOptions`, application registration, and configuration;
- the Cart `GeneratedEditedImage` model, initial Entity Framework migration,
  and guest upload/ownership code; and
- all backend and frontend consumers found by repository search.

The proposed JVM implementation is based on the official Ktor file-response,
Partial Content, and Conditional Headers documentation, plus the official
Scrimage and WebP ImageIO project documentation. Dependency versions must be
rechecked when implementation starts.

## Outcome

Create one `image` Kotlin compilation module that owns image decoding,
encoding, resizing, safe local storage, derived-image caching, and the public
and authenticated private delivery routes.

Do **not** create a separate filesystem compilation module now. Local files are
a local-substitutable implementation detail and there is only one production
storage adapter. Splitting `java.nio.file` calls into another module would
expose paths, spread path-safety and cache-consistency rules across two
interfaces, and leave a shallow pass-through. The Image module should instead
hide the filesystem behind its image-oriented interface and use temporary
directories in tests. Revisit this decision only when a real second adapter,
such as S3-compatible object storage, is selected.

The first slice does not create database tables. It deliberately excludes the
guest route because that route depends on Cart-owned metadata, guest-token
policy, and ownership rules that do not yet exist in Kotlin.

## Behavior analysis

| Behavior | Evidence | Classification | Kotlin approach | Verification |
| --- | --- | --- | --- | --- |
| Parse `width` and `widthxheight`; require positive integers no greater than 4096 | `ImageSize.Parse` and `ImageSizeTests` | Required | Parse inside the operation so non-HTTP callers cannot bypass the invariant | Boundary-table unit tests, including empty, signed, malformed, and over-limit values |
| A width-only request preserves aspect ratio | `ResizeMode.Max` with height `0`; frontend uses width-only sizes | Required | Treat width-only as a fit-within width with proportional height | Assert actual output dimensions for landscape and portrait images |
| A width-and-height request fits the complete image within the box without crop or padding | `ResizeMode.Max`; source code and ImageSharp contract | Required | Use a `max`/fit-within resize operation | Assert output dimensions for mismatched aspect ratios |
| `ResizeMode.Max` may upscale a smaller source | ImageSharp `Max` behavior; source supplies no anti-upscale option | Required, but missing from legacy tests | Preserve upscaling unless Joe approves a product-level change | Resize a small source into a larger requested box |
| Public and private originals use separate configured roots | `ImageOptions`, `ImageService`, and visibility tests | Required | Resolve through normalized absolute roots held by `ImageSettings` | Temp-directory tests for correct and wrong visibility |
| Public route is anonymous; private route requires any authenticated session | `ImageController` attributes | Required | Public route outside auth; private subtree uses `authenticate(AuthRouting.PROVIDER)` | Route tests for anonymous public success and private `401`/authenticated success |
| Nested relative filenames are accepted | public tail route and subfolder test | Required | Accept normalized relative path segments below the selected root | Route and operation test for a nested image path |
| Traversal outside any configured root is rejected | explicit validation and tests | Required security invariant | Normalize and containment-check paths; resolve existing originals against their real root and reject symlink escapes | `..`, absolute path, separator, sibling-prefix, and symlink escape tests |
| Only `.jpg`, `.jpeg`, `.png`, and `.webp` delivery names are accepted | `AllowedExtensions` and tests | Required for the current routes | Keep the allowlist and map output content type from the selected encoder | One successful test per format and rejected GIF/BMP/TIFF tests |
| Missing original returns `404`, even if a derived cache file exists | original existence check occurs before cache lookup | Required | Always authorize and resolve the original before considering cache | Missing-original test with a deliberately seeded cache file |
| Cache path is partitioned by visibility, requested size, and relative filename | `ImageService` and cache tests | Required | Preserve the directory key to keep public/private and size results isolated | Cache path and cross-visibility tests |
| Concurrent misses for one key generate one complete derived file | keyed `SemaphoreSlim` and double check | Required concurrency behavior | Use keyed coroutine synchronization and atomic temporary-file replacement; do not retain unused locks forever | Parallel miss test plus validation that the final file decodes completely |
| A cache hit avoids decoding and encoding | source double-check and cache-hit test | Required performance behavior | Return the existing fresh derived file directly | Instrumented operation test or unchanged derived-file timestamp |
| Updating an original at the same path currently leaves a stale cached image | cache key omits source version and no freshness comparison exists | Approved deviation | Regenerate when the original is newer than the derived file | Replace original, preserve filename, and assert changed output |
| New derivations use the source extension and corresponding JPEG, PNG, or WebP content type | `GetEncoder` and `ContentTypes` | Required | Preserve the requested representation format and response content type | Decode generated outputs and assert response content types |
| Exact ImageSharp encoder bytes and default compression settings | framework defaults are not asserted and callers consume images, not byte identity | Incidental | Choose explicit documented encoder settings and verify visual format/dimensions, not byte equality | Format decode tests and a small representative quality/size smoke test |
| Public responses cache for 24 hours; private responses cache for one hour | `ImageController` | Required HTTP behavior | Emit `public, max-age=86400` or `private, max-age=3600` | Route header tests |
| File responses expose Last-Modified, ETag, conditional requests, and byte ranges | `File`/`PhysicalFile` response configuration | Required HTTP behavior | Return `LocalPathContent`; install Ktor Conditional Headers and Partial Content on the Image route subtree | `Last-Modified`, `ETag`, `304`, `Accept-Ranges`, and `206` tests |
| Newly generated content may be returned from memory while cache hits use a physical file | `ResizedImageResult.ImageData` | Incidental implementation detail | Atomically finish the derived file and always serve the file response | First request and cache-hit responses have the same body and headers |
| Invalid size, filename, format, and upload return `400`; missing image returns `404`; processing failure returns `500` | exception handler and tests | Required status semantics | Map `OperationResult` to the existing `ApiError` contract | Route contract tests for every outcome |
| ASP.NET ProblemDetails is the exact error payload shape | no frontend consumer reads these image error bodies; current Kotlin modules use `ApiError` | Approved deviation | Use the shared Kotlin `ApiError` JSON shape and preserve status/message semantics | JSON route assertions |
| Public storage accepts declared JPEG, PNG, or WebP uploads up to 10 MiB and rejects empty, oversized, unsupported, or undecodable input | `PublicImageStorageService` and tests | Required | Validate declared type and bounded bytes before decoding; verify actual decoded format | Complete upload validation matrix |
| Public storage ignores the source filename, creates a random name, preserves dimensions, and normalizes to `.webp` | service and parameterized tests | Required | Generate a lowercase UUID-like basename and encode WebP | Store each accepted source format and inspect stored output |
| Public delete is idempotent for missing files | service and test | Required | Missing file is a successful no-op | Delete existing then delete again |
| Public folder and filename input cannot escape the public root | resolver checks | Required security invariant | Use a validated `PublicImageFolder` plus safe filename resolution | invalid folder, absolute path, traversal, separator, and symlink tests |
| Deleting a public original does not centrally clear all derived cache entries | variant storage attempts its own cleanup; shared storage does not | Required target-architecture improvement, not an external contract | Image module deletes its own derived files so consumers do not know cache layout | Seed multiple sizes, delete once, and assert originals and derivations are gone |
| Upload decoding has no explicit decoded-pixel ceiling | 10 MiB byte limit only | Approved deviation | Retain the 10 MiB compressed-byte limit and reject decoded images above 40 megapixels | Header-first fixtures immediately below, at, and above 40 megapixels |
| Guest image is readable when either guest token or authenticated user owns its Cart record | `GuestImageService` and tests | Required, deferred | Cart will export an ownership-aware image lookup capability; no placeholder Cart schema or interface now | Future Cart PostgreSQL and route tests |
| Guest GET creates a guest cookie when none exists | controller calls `GetOrCreateGuestToken` | Required, deferred | Keep the behavior with Cart-owned guest-session policy | Future Cart route test |
| Cart accepts GIF uploads while Image delivery rejects `.gif` | `CartService.AllowedImageContentTypes` contradicts `ImageService.AllowedExtensions` | Unclear and materially broken for guest delivery | Cart migration must either reject GIF at upload or define animated/static GIF conversion; Image does not silently choose | Future Cart decision and end-to-end test |

## Operation contract

| Operation | Required input | Required success value | Required errors | Authorization, ordering, or caching |
| --- | --- | --- | --- | --- |
| `GET /api/images/public/{size}/{filename...}` | valid size and safe nested filename under the public root | encoded image bytes in the filename's supported format | `400` invalid size/name/format, `404` missing, `500` processing | Anonymous; public cache for 86400 seconds; conditional and range requests |
| `GET /api/images/private/{size}/{filename...}` | valid size and safe nested filename under the private root | encoded image bytes in the filename's supported format | `401` unauthenticated, then the same image outcomes as public | Authenticated session; private cache for 3600 seconds; conditional and range requests |
| `PublicImageStorage.store` | validated public folder plus JPEG/PNG/WebP upload of 1 byte through 10 MiB | generated `.webp` filename | invalid upload/format, unexpected processing or I/O failure | Cross-module capability; no HTTP or Ktor types in its interface |
| `PublicImageStorage.exists` | validated public folder and safe filename | Boolean existence | unexpected I/O failure | Resolves only inside the public original root |
| `PublicImageStorage.delete` | validated public folder and safe filename | `Unit`, including already-missing files | unexpected I/O failure | Removes original and every derived cache entry owned by Image |
| `GET /api/images/guest/{size}/{id}` | size, image ID, guest session, optional authenticated user | owned private guest image | `404` absent/not owned plus normal image outcomes | Deferred to Cart; private cache policy |

There is no ordering contract and no database transaction in the first Image
slice.

## Proposed Kotlin interface

The external seam is image-oriented. It does not expose `Path`, `File`, Ktor
multipart types, cache directories, or a generic filesystem abstraction.

```kotlin
public interface PublicImageStorage {
    public suspend fun store(
        folder: PublicImageFolder,
        upload: ImageUpload,
    ): OperationResult<StoredPublicImage>

    public suspend fun exists(
        folder: PublicImageFolder,
        filename: String,
    ): OperationResult<Boolean>

    public suspend fun delete(
        folder: PublicImageFolder,
        filename: String,
    ): OperationResult<Unit>
}

internal interface ImageOperations {
    suspend fun get(
        visibility: ImageVisibility,
        requestedSize: String,
        filename: String,
    ): OperationResult<ImageResource>
}
```

`ImageUpload` carries bounded bytes and a declared content type. A future
multipart route must enforce the request limit while reading and must close the
Ktor part before calling this interface. At 10 MiB, a byte-array seam is
deliberately simpler than leaking Ktor channels or blocking stream suppliers
across compilation modules; image decoding already requires a bounded in-memory
representation.

`PublicImageFolder` validates one fixed relative collection path when a
consumer is assembled. It prevents every store/delete call from passing an
unchecked arbitrary folder while avoiding an Image-owned enum of Prompt and
Article concepts.

## Production type map

| File and top-level type | Visibility | Independent meaning |
| --- | --- | --- |
| `ImageCodec.kt` / `ImageCodec` | internal | Concrete JPEG/PNG/WebP inspection, decoding, and encoding collaborator |
| `ImageFiles.kt` / `ImageFiles` | internal | Concrete safe local-path, cache-file, and atomic-publication collaborator; not a generic storage adapter |
| `ImageSettings.kt` / `ImageSettings` | public | Validated absolute public, private, and cache roots read by app composition |
| `ImageModule.kt` / `ImageModule` | internal | Runtime handle; owns the Image object graph while the installation function exports only public image storage |
| `ImageVisibility.kt` / `ImageVisibility` | internal | Selects public versus private root and cache policy |
| `ImageSize.kt` / `ImageSize` | internal | Parsed positive fit-within dimensions with the 4096 bound |
| `ImageUpload.kt` / `ImageUpload` | public | Ktor-independent bounded image upload input |
| `PublicImageFolder.kt` / `PublicImageFolder` | public | Validated public collection path |
| `StoredPublicImage.kt` / `StoredPublicImage` | public | Generated filename returned to the owning domain module |
| `PublicImageStorage.kt` / `PublicImageStorage` | public | Cross-module store/exists/delete capability |
| `ImageOperations.kt` / `ImageOperations` | internal | Route-test seam for transformed image retrieval |
| `ImageResource.kt` / `ImageResource` | internal | Safe derived local resource, media type, and version metadata for HTTP delivery |
| `ImageService.kt` / `ImageService` | internal | Deep implementation of retrieval, conversion, caching, and public storage |
| `ImageRoutes.kt` / `ImageRoutes` | internal | Thin public/private HTTP mapping and route-scoped file plugins |

No source exception hierarchy, `ResizedImageResult`, generic filesystem
interface, repository, database table, or module-specific result hierarchy is
planned. Internal helper keys and format enums should be nested or private
members rather than additional top-level types unless implementation proves an
independent meaning.

## Runtime composition

- `ImageModule` owns one `ImageService` instance. That implementation satisfies
  both internal `ImageOperations` and public `PublicImageStorage`.
- `createImageModule(settings)` constructs the object graph without Ktor or
  filesystem details escaping to app composition.
- `Application.installImageModule(settings)` installs public/private routes and
  returns `PublicImageStorage` for later Prompt and Article composition.
- An internal `Application.installImageModule(images: ImageOperations)` overload
  remains the route-test seam.
- The runtime handle and factory stay internal. Future compilation modules use
  only the public installation function and returned `PublicImageStorage`.
- Application composition reads `ImageSettings`, installs authentication first,
  then installs Image so the private route can use the shared auth provider.

Ktor `PartialContent` and `ConditionalHeaders` are installed only on the Image
route subtree. They are not hidden application-wide policy. Ktor's local path
response remains the only HTTP/file adapter.

## Dependency and runtime decision

The preferred implementation spike is:

- Scrimage Core for tested immutable decoding and `max` fit-within resizing;
- WebP ImageIO for JPEG/PNG/WebP decoding and WebP writing through Java ImageIO;
  and
- Ktor Conditional Headers and Partial Content for HTTP file semantics.

The implemented versions, rechecked against their official release records on
2026-07-16, are Scrimage `4.6.6` and
`com.github.usefulness:webp-imageio` `0.11.0`. WebP ImageIO bundles native
libwebp code. JPEG, PNG, WebP read/write, and Scrimage fit-within resizing pass
on macOS ARM. The built Image module JAR and the resolved native WebP artifact
also pass the isolated Linux ARM64/glibc smoke described below.

Approved on 2026-07-16 and verified: standardize the Kotlin runtime on a
glibc-based JDK image and use the native libwebp dependency. The repository now
contains `backend/scripts/smoke-image-runtime-linux-arm64.sh`. It mounts only
isolated built/runtime artifacts, never the repository, and calls the
production `ImageCodec` plus `ImageSize` from the built module JAR. The check
decodes and re-encodes JPEG, PNG, and WebP and verifies a 300x200 source fits to
120x80. The successful check used
`eclipse-temurin@sha256:3eb81ed94d8c1a34422f19f8188548bdf02cae69c91d0328afdbb7abed90f617`
with `--platform linux/arm64` and printed
`Linux aarch64 production-codec-jpeg-png-webp-fit-ok`. JDK 25 runs the native
dependency with `--enable-native-access=ALL-UNNAMED`.

## Application-composition and configuration changes

Implementation will:

1. register `modules/image` in `backend/project.yaml`;
2. add the Image module and Ktor/file/image dependencies to the shared catalog
   and module manifest;
3. add Image as an app dependency;
4. add `Image.PublicRoot`, `Image.PrivateRoot`, and `Image.CacheRoot` environment
   backed configuration;
5. validate, normalize, and make the three roots absolute at startup;
6. install Image after HTTP runtime and authentication; and
7. update the module graph and beginner-oriented Image documentation in
   `docs/dev/backend`.

Relative configured roots are resolved once against the application working
directory. Production should use absolute mounted paths. Startup creates
missing roots but rejects a configured file, overlapping roots, or roots that
cannot be made writable.

## Flyway and data ownership

There is no Image Flyway migration. Original and derived images are filesystem
artifacts, not database rows.

The legacy `generated_edited_images` table belongs to Cart because Cart creates
the metadata, owns guest/user access, and holds the relationships from cart and
order items. It must not be partially recreated by Image. The early-development
clean-target rule means no legacy filesystem adoption or cache migration is
required; configured original directories may be copied deliberately for local
testing, while the derived cache may be deleted and rebuilt.

## Test plan

### Pure and settings tests

- Complete `ImageSize` parse matrix, including 4096 and overflow.
- Fit-within dimension examples for width-only, width-and-height, portrait,
  landscape, same-size, and upscale cases.
- `PublicImageFolder` validation for nested safe paths and every unsafe form.
- `ImageSettings` missing, relative, absolute, overlapping, file-instead-of-dir,
  and unwritable-root behavior.

### Image operation tests with temporary directories

- JPEG, PNG, and WebP decode, resize, encode, and media types.
- Public/private isolation, nested names, missing original, wrong visibility,
  unsupported extension, traversal, sibling-prefix, and symlink escape.
- Cache miss, hit, visibility/size separation, original-newer invalidation, and
  missing-original-with-cache behavior.
- Concurrent same-key misses, independent keys, atomic completed output, lock
  cleanup, cancellation propagation, and unexpected codec/I/O failure mapping.

### Public storage tests

- Empty, one-byte-invalid, 10 MiB, 10 MiB plus one, unsupported declared type,
  mismatched/invalid bytes, and the 40-megapixel decoded boundary.
- JPEG, PNG, and WebP normalization to a generated `.webp` while preserving
  dimensions and alpha where applicable.
- Exists, idempotent delete, derived-cache cleanup, safe folder/filename rules,
  and cleanup after failed encoding.

### Route tests

- Public anonymous access and private authenticated access with anonymous
  rejection before Image operations run.
- Tail filename routing, all status mappings, and shared `ApiError` JSON.
- Correct public/private `Cache-Control`, media type, `Content-Length`,
  `Last-Modified`, ETag, `304 Not Modified`, `Accept-Ranges`, and `206 Partial
  Content` behavior.

### Runtime and quality verification

- A packaged-artifact smoke test on macOS ARM and the selected Linux container
  must read and write WebP; unit tests on only one developer OS are insufficient.
- Update `docs/dev/backend/module-architecture.md` and add an Image package guide
  for Kotlin beginners.
- Run `./kotlin do ktfmt`, `./kotlin check`, and a final stable
  `./kotlin do ktfmt` from `backend/`.

## Deviation and uncertainty log

| Behavior or contract | Source evidence | Proposed Kotlin behavior | Classification | Approval or owner | Follow-up |
| --- | --- | --- | --- | --- | --- |
| Generic filesystem compilation module | Source uses direct local filesystem calls from several features | Keep filesystem implementation internal to Image; extract only for a real second adapter | Architecture decision | Approved by Joe — 2026-07-16 | Implement inside Image |
| JVM image/WebP dependency and Linux base | ImageSharp is fully managed; JVM candidates use bundled/native libwebp; legacy runtime is Alpine | Scrimage plus WebP ImageIO on a proven glibc JDK runtime | External dependency/runtime decision | Approved by Joe — 2026-07-16 | Verified on macOS ARM and isolated Linux ARM64/glibc; repeatable script added |
| Exact ASP.NET ProblemDetails payload | Legacy exception handler; frontend does not inspect Image error bodies | Shared `ApiError` with preserved status/message semantics | Approved deviation | Approved by Joe — 2026-07-16 | Verified by route contract tests |
| Stale cache after same-name original update | Legacy cache ignores original modification | Rebuild when original is newer | Approved deviation | Approved by Joe — 2026-07-16 | Verified, including replacement during queued processing |
| Decoded-pixel ceiling | Legacy only caps compressed bytes at 10 MiB | Keep the 10 MiB upload limit and add a 40-megapixel decoded-image limit | Approved deviation | Approved by Joe — 2026-07-16 | Verified below, at, and above the pixel boundary and at/above the byte boundary |
| Native WebP output quality/settings | ImageSharp encoder defaults are not asserted | Set and document explicit codec settings after a representative fixture check | Incidental source behavior with product-quality impact | Joe for visual trade-off if materially different | Compare fixtures during spike |
| Guest image route and table | Image controller depends on Cart token, entity, and ownership query | Defer to Cart and compose through an ownership-aware capability | Required deferred behavior | Cart migration | See post-migration file |
| GIF guest upload | Cart accepts GIF; Image rejects it | No silent choice; decide reject versus supported conversion in Cart | Unclear contradiction | Cart migration with Joe | Add end-to-end fixture |
| Mug variant uploads preserve source format while shared public storage normalizes WebP | `VariantExampleImageStorage` versus `PublicImageStorageService` | Do not widen Image's first storage interface speculatively | Required difference, deferred | Article migration | Decide whether Article needs preserve-format storage |

## Analysis approval — 2026-07-16

Joe approved all five analysis decisions:

1. filesystem handling remains internal to the Image module;
2. Image uses a native libwebp-backed implementation on a glibc-based Kotlin
   runtime;
3. routes return the shared Kotlin `ApiError` instead of reproducing ASP.NET
   ProblemDetails;
4. the cache regenerates a derived image when an original with the same filename
   is newer; and
5. decoding is limited to 40 megapixels while the independent 10 MiB compressed
   upload limit remains in force.

The fifth decision deliberately keeps both protections. The byte limit bounds
request size, while the pixel limit bounds decoded memory and processing work.
At 40 megapixels, a 32-bit pixel buffer alone can require about 160 MB before
codec and processing overhead, so concurrency limits and a representative
print-workflow test remain required.

The analysis stop condition was resolved, and Joe requested implementation via
the Image migration goal on 2026-07-16. The codec/runtime spike completed before
the production slice was assembled.

## Implementation

Implemented on 2026-07-16 as one `modules/image` compilation module. The slice
contains validated root configuration, JPEG/PNG/WebP codecs, fit-within
resizing, visibility/size-partitioned derived caching, atomic publication,
public storage normalization to WebP, and public/authenticated-private Ktor
routes with conditional and partial file responses.

Application composition registers Image after authentication and exports only
`PublicImageStorage` to future Prompt and Article consumers. The dependency
catalog pins Scrimage `4.6.6` and WebP ImageIO `0.11.0`; app configuration adds
environment-backed public, private, and cache roots. The module graph, local
server guide, dedicated beginner package guide, development-data ignore rule,
and post-migration consumer record are current.

The implementation deliberately still excludes Cart persistence, guest-token
policy, the guest route, and deferred consumer adoption. No Flyway migration or
compatibility layer was introduced.

## Completion report

Completed and verified on 2026-07-16.

- The preserved external behavior covers public/private delivery, size
  parsing, fit-within resizing with upscaling, JPEG/PNG/WebP representation,
  cache and HTTP metadata, upload normalization, exists, and idempotent delete.
- The approved Kotlin deviations use shared `ApiError`, regenerate cache after
  source updates, and bound decoding to 40 megapixels as well as 10 MiB.
- The target architecture additionally makes cache deletion symlink-safe,
  coordinates deletion with derivation publication, revalidates concurrently
  replaced originals, bounds full decode/encode concurrency, and requires
  atomic filesystem publication.
- Deferred Cart/guest/database ownership, Prompt/Article adoption, GIF policy,
  and mug preserve-format behavior remain recorded in
  [`image-post-migration.md`](image-post-migration.md).
- `./kotlin test -m image -m app` passed 22 Image tests and 3 app tests,
  including PostgreSQL startup/migration composition. The final
  `./kotlin check` passed every backend test, Detekt, Ktlint, and tooling check.
  The final `./kotlin do ktfmt` reported every Kotlin source already canonical.
- The macOS ARM codec smoke and the pinned Linux ARM64/glibc built-artifact
  smoke both passed. No Flyway migration was needed.

The simplification review found no compatibility layer, generic filesystem
interface, copied result/exception hierarchy, or unused public assembly type.
`ImageService` now orchestrates two concrete internal collaborators:
`ImageCodec` for image formats and `ImageFiles` for safe local artifacts.

## Migration retrospective

| Finding | Evidence | Scope | Earlier signal or check | Destination and action |
| --- | --- | --- | --- | --- |
| Ktor conditional metadata must live on the outgoing file content | A custom provider did not add ETag to transformed `LocalPathContent`; assigning `content.versions` produced ETag, Last-Modified, `304`, and `206` in route tests | Image HTTP adapter | Route-level protocol assertions, not unit assumptions | Keep in this record; promote only if another file-serving module repeats it |
| Cache deletion needs the same real-path discipline as reads | Independent review found that a lexical cache cleanup path could traverse an intermediate symlink | Image filesystem boundary | The migration plan called out symlink-safe reads but the first delete test used only normal directories | Added real-root resolution and an adversarial intermediate-symlink deletion test; no guide change because the existing security rule already requires it |
| Generation and deletion share an original lifecycle | Independent review found an in-flight derivation could publish after deletion, and replacement during queued processing could publish stale pixels | Image cache concurrency | Same-key miss tests did not exercise mutations of the original | Added a per-original publication/delete lock, source identity revalidation, retry, and deterministic delete/replacement race tests |
| The processing limit must include full decode | Review found upload decoding happened before the semaphore and could allocate multiple 40 MP buffers concurrently | Image memory safety | The documented limit named decode but the first code shape acquired it only around encode | Moved decode through encode under the shared permit; oversized `ImageUpload` no longer creates a defensive copy |
| Cancellation tests must enter the service | The first cancelled-context test was cancelled before `get` executed | Image coroutine behavior | Test passed without observing lock or cleanup state | Replaced it with a queued in-service cancellation test that asserts propagation, temporary cleanup, and keyed-lock removal |
| Native verification should execute the built production codec | The initial isolated test exercised the native dependency but not `image-jvm.jar`; the first script loaded the JAR but still used raw ImageIO | Image runtime packaging | Independent review compared the durable requirement with both actual commands | `smoke-image-runtime-linux-arm64.sh` now calls production `ImageCodec` and `ImageSize` for JPEG/PNG/WebP and fit-within using the built module JAR and pinned glibc image digest |
| Simplification review kept the filesystem inside Image | The final implementation has one production adapter, a narrow `PublicImageStorage` capability, concrete internal `ImageCodec`/`ImageFiles` collaborators, no repository/result/exception duplicates, and no generic filesystem seam | Image module design | Approved analysis architecture plus production type-map and Detekt review | Keep the deep module; revisit only for a selected second storage adapter |
