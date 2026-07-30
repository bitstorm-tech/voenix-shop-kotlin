# Generator module migration

Module-specific record for migrating the legacy .NET Generator feature into
the Kotlin `generator` module. General rules live in
[`module-migration-guide.md`](module-migration-guide.md); the workflow lives in
the `migrate-dotnet-feature` skill. This migration runs as a migration-council
task (`.agents/skills/migration-council/SKILL.md`); this file is the durable
plan decided in the Phase-1 brainstorming of 2026-07-30.

## Status

`implementation` — implementation complete, verification pending.

Phase 1 (council brainstorming) is complete; all contested points were decided
by Joe on 2026-07-30. Phase 2 is complete as well: the four sub-tickets #46
(`GenerationCoins` export in `magic-coins`), #47 (module core: settings, upload
reader, service, outcome, routes), #48 (fal.ai adapter, configuration, app
composition), and #49 (this documentation sweep) are implemented on the
`generator-migration` branch.

The status stays `implementation` rather than moving to `complete`, because
[`migration-base.md`](migration-base.md) knows only the four values
`analysis | awaiting-approval | implementation | complete` and `complete` is the
end of phase 3. Open before it may be set: the phase-3 council verification, the
post-migration simplification review, and the retrospective table at the end of
this file.

## Task parameters

Target module:

`generator`

Source feature:

`../voenix-shop/backend/Voenix.Api/Features/Generator` plus
`Configuration/GeneratorOptions.cs`, `Configuration/GeneratorOptionsValidator.cs`,
and the Generator/MagicCoins branches of `ErrorHandling/DomainExceptionHandler.cs`

Target package:

`backend/modules/generator/src/shop/voenix/generator`

Analysis checkpoint:

`wait-for-approval` — approval obtained 2026-07-30 (decision log)

Known consumers:

- Vue frontend store `frontend/src/stores/shop/imageGeneration.ts` (multipart
  `image` + `promptId`, reads the response as a `Blob`, reads `details.code`
  on errors)
- No backend consumer; the module exports no capability

Approved deviations from current behavior:

- See the deviation log below; all rows approved by Joe on 2026-07-30.

Explicitly deferred work:

- Abuse/rate limiting of the anonymous, cost-incurring endpoint (guest-cookie
  deletion grants a fresh starting balance) — product decision, recorded in
  `all-post-migration.md`, owner Joe.
- `OperationResult.UpstreamFailure` as a shared variant — deferred until the
  Payment migration provides the second consumer (legacy maps
  `PaymentApiException` to 502 as well).
- Shared size-limited multipart chunk reader in `platform` — the reader now
  exists twice (image module and generator, ~45 lines); promotion is a
  retrospective finding with owner Joe, not part of this migration.
- Aspect ratio `16:9` is kept as legacy behavior but is questionable for mug
  printing — open product question, owner Joe.
- Optional generation audit trail (owner, promptId, timestamp) — only if the
  product ever needs traceability; explicitly not print-image registration.

## Analysis deliverable

### Behavior matrix

| Behavior | Evidence | Classification | Kotlin approach | Verification |
| --- | --- | --- | --- | --- |
| `POST /api/generator/generate`, multipart part `image` + form field `promptId`, anonymous or authenticated | `GeneratorController.cs:33-38`; frontend sends exactly these names | Required | Same path and part names; generator-local chunked multipart reader | Route test: parts in either order, missing/duplicate parts |
| Empty or missing image → 400 | `GeneratorController.cs:40-41` | Required | `GenerationUpload.MissingImage` → 400 `ApiError` with field `image` | Route test |
| Content type must be image/jpeg, image/png, or image/webp (case-insensitive) → else 400 | `GeneratorController.cs:24-30,43-46` | Required | Allowlist check in the service (single validation implementation) | Service test with `image/JPEG`, `text/html` |
| Coin owner: authenticated user, else guest token (get-or-create) | `GeneratorController.cs:69-77` | Required | Shared `ApplicationCall.magicCoinsOwner(guestTokens)` exported by magic-coins (rule exists today in `MagicCoinsRoutes.owner`) | Route test: session cookie vs. guest cookie |
| Insufficient coins → 402 with `code = INSUFFICIENT_MAGIC_COINS` | `GeneratorController.cs:49-50`; `DomainExceptionHandler.cs:139-146`; store reads `details.code` | Required | `GenerationOutcome.InsufficientCoins` → 402 `ApiError(message, code)` | Route test asserting status and code string |
| Coin check happens before prompt loading | `GeneratorController.cs:49-52` order | Required | Same order in `GeneratorService` | Service test with recording fakes |
| Prompt unknown/inactive/blank → 404 | `PromptService.GetPromptTextAsync` throws `PromptNotFoundException` → 404 (`DomainExceptionHandler.cs:253`) | Required | `PromptCatalog.composedText(promptId)` returns `null` → `PromptUnavailable` → 404 | Service + route test |
| Prompt lookup database failure → 500, never 402/404 | `PromptDatabaseException` → 500 | Required | catch around `composedText` (its KDoc lets DB failures escape), rethrow `CancellationException`, else `UnexpectedFailure` → 500 | Service test |
| DummyMode returns the uploaded image unchanged, content type preserved; coin check and spend still run | `GeneratorService.cs:30-36` | Required | Dummy `ImageGenerator` lambda chosen at the composition seam; service has no flag | Service test with dummy; app composition test |
| fal.ai call: POST `https://fal.run/fal-ai/nano-banana-2/edit`, header `Authorization: Key <ApiKey>`, snake_case JSON `image_urls` = [`data:<contentType>;base64,<...>`], `prompt`, `num_images` = 1, `aspect_ratio` = "16:9" | `GeneratorService.cs:16,58-77`; `FalRequest.cs` | Required | `FalImageGenerator` with Ktor client; wire DTOs nested `private @Serializable` | `MockEngine` test asserting URL, header, exact JSON keys and values |
| Non-2xx from fal.ai, empty image list, undeserializable response → 502 "Generator API error" | `GeneratorService.cs:79-97`; handler line 130 | Required | Adapter returns `null` → `UpstreamFailure` → 502 | MockEngine tests per case |
| Result image downloaded from the returned URL; its `content_type` used, fallback `image/jpeg` | `GeneratorService.cs:41-45,100-118` | Required | Same in adapter; see also hardening deviations D-A6/D-A7 | MockEngine test |
| Download failure → 502 | `GeneratorImageDownloadException` → 502 | Required (status), message merged per D-A3 | Adapter returns `null` → `UpstreamFailure` → 502 | MockEngine test |
| Spend after successful generation; spend failure only logs a warning, response stays successful | `GeneratorController.cs:60-61` | Required | `withContext(NonCancellable) { trySpendForGeneration(owner) }`; `false` → warn log | Service test: `false` does not change the outcome; no spend on any failure path |
| Response: raw image bytes with the result content type, no JSON envelope, no Content-Disposition | `File(result.ImageData, result.ContentType)` | Required | `call.respondBytes(bytes, contentType)` | Route test |
| Startup fails when DummyMode is off and ApiKey is blank | `GeneratorOptionsValidator.cs` | Required | `require` in `GeneratorSettings` init, read before Flyway | Settings test |
| ASP.NET ProblemDetails error bodies | handler | Incidental | Shared `ApiError` shape (already the repo-wide contract; Cart precedent) | Route tests assert `ApiError` |
| Exception hierarchy `GeneratorException` etc. | source tree | Incidental | No exceptions; sealed `GenerationOutcome` | — |
| Transport error during fal call → 500 (uncaught `HttpRequestException`) | absence of catch in `CallFalApiAsync` | Incidental (handler artifact) | Every upstream failure → 502 (D-A4) | MockEngine timeout/IO test |
| No CSRF protection on `/api/generator` | `MutationAntiforgeryConvention` covers only admin + cart | Incidental gap | Guest-capable CSRF subtree protection (D-B) | Route test: request without token rejected, operations stub not invoked |
| No upload size limit beyond ASP.NET defaults | absence | Incidental | 10 MiB, chunked, aborted while arriving (D-A5) | Route test at limit + 1 byte |

### Operation contract

One operation; the module is stateless (no list/get/create/update/delete).

| Operation | Required input | Required success value | Required errors | Ordering |
| --- | --- | --- | --- | --- |
| Generate | multipart part `image` (jpeg/png/webp, ≤ 10 MiB) + form field `promptId` (positive long); coin owner from session or guest cookie | 200, raw image bytes, `Content-Type` of the generated image | 400 invalid input, 402 insufficient coins (`code`), 404 prompt unavailable, 502 upstream failure, 500 unexpected | n/a |

Example request (multipart/form-data, exactly what the frontend sends):

```
POST /api/generator/generate
Content-Type: multipart/form-data; boundary=----X
X-XSRF-TOKEN: <token>

------X
Content-Disposition: form-data; name="image"; filename="cropped.png"
Content-Type: image/png

<binary bytes>
------X
Content-Disposition: form-data; name="promptId"

42
------X--
```

Example success response: status 200, `Content-Type: image/jpeg` (or the
type fal.ai reports), body = raw image bytes. No JSON wrapper.

Example error response (402):

```json
{ "message": "Not enough Magic Coins", "code": "INSUFFICIENT_MAGIC_COINS" }
```

The 400 shape uses the shared field-error form:

```json
{ "message": "Validation failed", "errors": { "image": ["Image must be a JPEG, PNG, or WebP file"] } }
```

### Kotlin type map (module `generator`, flat package, 10 top-level types)

| File / type | Visibility | Justification (deletion test) |
| --- | --- | --- |
| `GeneratorModule.kt` — `GeneratorModule` + `createGeneratorModule` + `installGeneratorModule` | internal handle/factory, public install | Guide-mandated composition boundary; also owns closing the HTTP client on `ApplicationStopped` (EmailModule pattern) |
| `GeneratorSettings.kt` — `GeneratorSettings` | public | Replaces `GeneratorOptions` + validator; enforces "ApiKey required unless DummyMode" at startup; `apiUrl` is a constructor override only (EmailSettings.sendUrl precedent), never a config key |
| `GeneratorOperations.kt` — `fun interface GeneratorOperations` | internal | Ktor-free operation seam and route-test stub point |
| `GeneratorService.kt` — `GeneratorService` | internal | Owns the business order: validate → coins → prompt → generate → spend |
| `GenerationOutcome.kt` — sealed `GenerationOutcome` (`Generated`, `Invalid`, `InsufficientCoins`, `PromptUnavailable`, `UpstreamFailure`, `UnexpectedFailure`) | internal | Carries the outcomes `OperationResult` does not have (402/404/502); no `NotFound`/`Conflict`/`Success<T>` duplication (CartPromotionResult precedent) |
| `GenerationUpload.kt` — sealed `GenerationUpload` + `ApplicationCall.receiveGenerationUpload()` | internal | What the multipart body carried (`Received(image, promptId)`, `MissingImage`, `TooLarge`, `MissingPromptId`); the only place that knows Ktor multipart; reads chunked, aborts past 10 MiB |
| `GeneratorRoutes.kt` — `GeneratorRoutes` | internal | The one `when` from outcome to HTTP status/body |
| `ImageGenerator.kt` — `fun interface ImageGenerator` + `dummyImageGenerator()` | internal | Port keeping the network out of the service; `null` = upstream failure; dummy is a lambda, not a type |
| `RawImage.kt` — `RawImage(bytes, contentType)` | internal | "Bytes plus media type", used by upload, port, and success result; plain class (ByteArray equals), not data class |
| `FalImageGenerator.kt` — `FalImageGenerator` (+ nested private `@Serializable` wire DTOs) | internal, implements `ImageGenerator`, `AutoCloseable` | The only place that knows fal.ai; no `private companion object` in serializable types (Article T3) |

Explicitly not created: `GeneratedImageResult`, exception types, repository,
Flyway migration, Exposed table, list/response wrappers,
`validateGeneratorRequests` (no JSON body), top-level Fal wire DTO files.

### Changes in module `magic-coins`

Redeems the export deferred by `magic-coins-migration.md`:

- New public `GenerationCoins.kt`:
  `suspend fun hasEnoughForGeneration(owner): OperationResult<Boolean>`
  (an infrastructure failure must never read as "no balance" → 402) and
  `suspend fun trySpendForGeneration(owner): Boolean` (the caller can only
  log; a richer result would be a distinction without a consequence).
- `MagicCoinsOperations : GenerationCoins` — one implementation
  (`MagicCoinsService`), `balance` stays internal.
- `MagicCoinsOwner` becomes public (capability parameter type);
  `logDescription` moves to an internal extension.
- New public `ApplicationCall.magicCoinsOwner(guestTokens)` — the owner rule
  currently lives as `private MagicCoinsRoutes.owner` and again in
  `CartRoutes.currentUserId`; the generator must not add a third copy.
- `installMagicCoinsModule(database, guestTokens)` returns `GenerationCoins`
  instead of `Unit`.

A combined check-and-spend operation was rejected: the expensive external call
sits between check and spend, so combining them would either pull fal.ai into
magic-coins or span a transaction across a long network call. Exact accounting
under concurrency would need a reserve/commit model — out of scope (see the
abuse deferral).

### Runtime composition

- `GeneratorModule` (internal) assembles service, routes, and the generator
  port; `createGeneratorModule(...)` is the factory; public
  `Application.installGeneratorModule(settings, prompts, coins, guestTokens)`
  chooses `dummyImageGenerator()` or `FalImageGenerator(settings)` and installs
  routes under guest-capable CSRF protection. The handle closes the adapter's
  HTTP client on `ApplicationStopped`.
- `module.yaml`: depends on `platform`, `prompt` (exported), `magic-coins`
  (exported), Ktor server core, Ktor client core/CIO/content-negotiation/JSON,
  SLF4J. Registered in `backend/project.yaml` and `backend/app/module.yaml`.
- `Application.kt`: read `GeneratorSettings.from(environment.config)` with the
  other settings before `connectAndMigrate()`; capture the return of
  `installMagicCoinsModule`; add `installGeneratorModule(...)` after it (the
  second consumer of the existing `prompts` variable).
- Configuration (`app/resources/application.yaml`):
  `Generator.DummyMode: "$GENERATOR_DUMMY_MODE:false"`,
  `Generator.ApiKey: "$FAL_API_KEY:"`. Default `false` is deliberate: a
  deployment without a key fails loudly at startup instead of silently serving
  dummy images (decision log).
- No Flyway change.
- HTTP client: connect timeout 10 s, request/socket timeout 120 s,
  `expectSuccess = false`, redirects allowed for the CDN download; no
  automatic retry of the fal POST (a retry doubles provider cost).

### Test plan

| Test | Kind | Proves |
| --- | --- | --- |
| `GeneratorSettingsTest` | pure | ApiKey required unless DummyMode; no secret in `toString()` |
| `GeneratorServiceTest` | unit, fakes for `GenerationCoins`/`PromptCatalog`/`ImageGenerator` | order coins→prompt→generate→spend via recording fakes; no spend on `Invalid`/`InsufficientCoins`/`PromptUnavailable`/`UpstreamFailure`; spend `false` keeps success; `hasEnough` failure → `UnexpectedFailure` (never 402); content-type allowlist incl. case-insensitivity; prompt DB exception → `UnexpectedFailure`; `CancellationException` rethrown |
| Cancellation spend test | unit | job cancelled after successful generation → spend still executed (`NonCancellable`); the coins fake must suspend like the real one (`withContext(Dispatchers.IO)` + `yield`) or the test proves nothing (guide, Cart finding) |
| `GenerationUploadTest` / route test | `ktor-server-test-host` + operations stub | part order `promptId` before/after `image`; missing/empty image; missing/non-numeric `promptId`; 10 MiB + 1 → 400 with field `image`; CSRF: request without token rejected **and stub not invoked** |
| `GeneratorRoutesTest` | route | outcome → status/body table incl. 402 `code` string and raw-bytes 200 |
| `FalImageGeneratorTest` | `MockEngine` | sent request (URL, `Authorization: Key`, snake_case keys, data URI, `num_images`, `aspect_ratio`); non-2xx, empty images list, malformed JSON, timeout, IO error → `null`; download flow; exotic result content type → `image/jpeg`; non-HTTPS result URL → `null`; oversized result → `null`; `CancellationException` rethrown |
| `GenerationCoinsIntegrationTest` (magic-coins) | Testcontainers | the exported capability spends exactly one coin and refuses at zero; existing magic-coins integration tests stay green |
| `GeneratorCompositionIntegrationTest` (app) | Testcontainers, DummyMode | real wiring: multipart in → identical bytes out, guest cookie set, balance 10 → 9 in the real database (Cart/Email composition-test precedent) |

No test may ever call the real fal.ai API: the URL is not a config key, and
composition tests run with DummyMode. Testcontainers only where PostgreSQL
matters (capability + composition); the generator module itself has no table.

Known quality-gate traps recorded up front: `ByteArray` in a `data class`
(use a plain class), `private companion object` in `@Serializable` types
(Article T3), `createGeneratorModule` parameter count near Detekt's limit.

## Decision log

### 2026-07-30 — Phase-1 council brainstorming and Joe's decisions

Three independent proposals (Claude orchestrator, Opus, Codex) converged on
all core points: stateless module, wire format kept, port + dummy at the
composition seam, module-local sealed outcome, spend-after-success kept,
two-method coin capability, fal parameters as code constants. No rebuttal
round was needed. Orchestrator decided the capability name `GenerationCoins`
(over Codex's `GenerationCredits`) — closer to the Magic Coins domain term.

Joe approved (each explicitly, via decision checklist):

1. **Deviation package** (rows D-A1…D-A7 below).
2. **CSRF** on `/api/generator` via the guest-capable subtree protection.
3. **`NonCancellable` spend** after successful generation (D-C).
4. **DummyMode default `false`** — fail loudly at startup without a key; the
   one point where Codex dissented (wanted `true` as the safe local default).
5. **No rate limiting in this migration** — legacy state kept; abuse question
   recorded in `all-post-migration.md`, owner Joe.
6. **`aspect_ratio = "16:9"` kept** and recorded as an open product question.
7. **Stateless cut confirmed**; the roadmap note "Generator needs Cart"
   (cart-migration.md deviation log) is closed as refuted; a possible audit
   trail is a later product feature, not print-image registration.
8. **Deferrals confirmed**: `OperationResult.UpstreamFailure` waits for
   Payment (second evidenced 502 consumer); the shared limited multipart
   reader stays duplicated and goes to the retrospective.

## Deviation and uncertainty log

All approvals: Joe, 2026-07-30.

| # | Behavior or contract | Source evidence | Kotlin behavior | Classification | Approval or owner | Follow-up |
| --- | --- | --- | --- | --- | --- | --- |
| D-A1 | ProblemDetails error bodies | `DomainExceptionHandler.cs` | Shared `ApiError` (`message`, `errors`, optional `code`); 402 code string unchanged | Approved deviation | Joe 2026-07-30 | Frontend already reads `message`/`code` |
| D-A2 | 400 echoes the client content type | `GeneratorController.cs:44-46` | Fixed text under field `image`, no echo of client input | Approved deviation | Joe 2026-07-30 | none |
| D-A3 | Two distinct 502 texts (API vs. download) | handler lines 130-135 | One text "Generator API error"; cause in the log | Approved deviation | Joe 2026-07-30 | none |
| D-A4 | Transport error in fal call → 500 | uncaught `HttpRequestException` | Every upstream failure → 502 | Approved deviation | Joe 2026-07-30 | none |
| D-A5 | No upload size limit | ASP.NET defaults | 10 MiB, chunked, aborted while arriving, 400 field `image` | Approved deviation | Joe 2026-07-30 | none |
| D-A6 | Implicit 100 s HttpClient timeout | .NET default | Explicit 10 s connect / 120 s request | Approved deviation | Joe 2026-07-30 | none |
| D-A7 | Result download unrestricted; upstream content type passed through | `GeneratorService.cs:100-118` | HTTPS-only result URL, download size cap, result content type allowlisted with `image/jpeg` fallback; API key never sent to the download host | Approved deviation | Joe 2026-07-30 | none |
| D-B | No CSRF on `/api/generator` | `MutationAntiforgeryConvention.cs` | Guest-capable CSRF subtree protection | Approved deviation | Joe 2026-07-30 | Requests without token: rejected before operation invocation |
| D-C | Spend cancelled with the request coroutine | `cancellationToken` passthrough | `withContext(NonCancellable)` around the spend | Approved deviation | Joe 2026-07-30 | Warn-log semantics unchanged |
| D-D | DummyMode default (deployment) | appsettings.json `false`, Dev `true` | Config default `false`; startup requires key | Decision | Joe 2026-07-30 | Local dev sets `GENERATOR_DUMMY_MODE=true` |
| — | Guest-cookie reset grants fresh coins → anonymous provider cost | legacy gap | Unchanged | Unclear → deferred product decision | Joe (owner) | Entry in `all-post-migration.md` |
| — | `aspect_ratio = "16:9"` for mug prints | `GeneratorService.cs:64` | Unchanged constant | Required (kept), flagged | Joe (owner) | Open product question |
| — | Shared limited multipart reader | duplicated ~45 lines (image + generator) | Duplicated in this migration | Deferred refactoring | Joe (owner) | Retrospective finding; platform promotion later |
| — | `OperationResult.UpstreamFailure` | Payment maps 502 too | Module-local `GenerationOutcome` | Deferred shared-type change | Payment migration | Evidence recorded here |

## Open points recorded during implementation (input for phase 3)

Noticed while implementing tickets #46–#48 and deliberately not acted on inside
a ticket. Each is either a candidate for the retrospective below or a small
incidental deviation the verification should confirm or reject.

1. **Possible latent bug in the image module's size-limited reader.**
   `generator`'s `GenerationUpload` drains the rest of the multipart body after
   refusing an oversized image, because a client that is still sending needs a
   reader on the other end or the `400` never reaches it. The image module's
   equivalent reader — the one this migration deliberately did *not* promote to
   `platform` — enforces its size limit without that drain. Whether this really
   breaks over HTTP was never proven: no test in either module reproduces it,
   and the observation comes from reading the code, not from a failing request.
   **Retrospective candidate**, owner Joe: either prove it with a test in the
   image module and fix it, or record why the case cannot occur there. This is
   the same code the deferred `platform` promotion concerns, so both should be
   decided together.
2. **The guest cookie is issued even on a `400`.** `GeneratorRoutes` resolves
   the owner via `magicCoinsOwner(guestTokens)` before it calls the operation,
   and that helper creates the guest cookie when the request has none. A request
   that is rejected as invalid therefore still leaves with a fresh
   `voenix.guest` cookie and, with it, a balance of 10 coins. It is harmless —
   the cookie is created by the balance endpoint on first contact anyway, and no
   coin is spent — but it is an incidental deviation from an ordering where a
   rejected request changes nothing. Resolving the owner after the upload check
   would avoid it at the cost of a second `when` over the upload.
3. **The result download cap works while collecting, not before.**
   `FalImageGenerator.readLimitedBytes` stops collecting once the bytes would
   exceed 10 MiB and cancels the channel; it does **not** pre-check
   `Content-Length` and refuse the response before reading. That is deliberate —
   an announced size is provider input and must never decide how much this
   server holds — but it means a provider streaming a huge body is only bounded
   in memory, not in transfer time. The socket timeout is the second bound.

## Migration retrospective

To be completed after verification and simplification (Phase 3). The three
points recorded above are its first inputs.

| Finding | Evidence | Scope | Earlier signal or check | Destination and action |
| --- | --- | --- | --- | --- |
| _pending_ | | | | |
