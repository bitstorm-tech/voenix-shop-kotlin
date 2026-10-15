# TypeScript port record: Kotlin backend → `backend-ts`

Driving issue: #262 (tickets #263–#278). Working branch `t3code/aa6df461`,
one commit per ticket. Decided on 2026-10-15, implemented the same day by the
council workflow; this record was written by the last ticket (T15, #278) after
the smoke run against the real frontend.

The port is a **big bang into a new folder**: `backend-ts/` serves the same
HTTP API on the same PostgreSQL schema, the Vue frontend was not changed, and
the Kotlin backend in `backend/` stays in the repository, untouched, until Joe
removes it. The reasons for the stack are in
[the technology-stack guide](../dev/backend-ts/conventions/technology-stack.md);
this file records what was ported, what changed on the way, what was dropped,
what the smoke run observed, and what is still open.

## What was ported, ticket by ticket

| Ticket | Commit | Module(s) | What landed |
| --- | --- | --- | --- |
| T0 #263 | `ed5cfab` | foundation | `backend-ts/` project (Bun, Hono, Zod 4, Kysely, pino, ESLint strict-type-checked, Prettier, `eslint-plugin-boundaries`), the 30 Flyway files copied unchanged to `migrations/` with a ~50-line runner, the platform (settings from environment variables, pool, `read`/`write`, `executePostgresWrite`, `OperationResult`, `ApiError`, `jsonBody`, `integerParam`, sealed-cookie auth with the four protections and CSRF, guest cookie, per-IP rate limiter, 30,000,000-byte body limit), the test support (one Testcontainer per run, template database, one copy per file, `app.request()` without a socket), `country` as the reference module, the convention guides |
| T1 #264 | `a60fadf` | `pricing` (with the former `vat`) | VAT CRUD, `POST /api/admin/prices/calculate`, `GET /api/admin/prices/default`, the calculation table, `priceCatalog.{prepare, storeInTransaction, replaceInTransaction, deleteInTransaction, find}` on the caller's transaction |
| T2 #265 | `0e9d855` | `supplier` | Supplier CRUD, `find(ids)`, the in-use delete |
| T3 #266 | `1b3264e` | `email` | A true leaf: `userEmails.send`, the outbox of **rendered** mails (`V32__email_jobs_payload.sql`), the ten templates, the Sweego delivery, the worker |
| T4 #267 | `237ea9c` | `image` | Public, private, and guest delivery (ETag, 304, byte ranges, cache rules), sharp codec with the 10 MiB limit, `publicStorage`, `privateStorage`, `readUploadedImage`, `exampleImages` |
| T5 #268 | `0ec027e` | `spod` | The partner client: the ten calls, the 1050 ms pacer, the bounded `SpodError` vocabulary, nothing foreign logged |
| T6 #269 | `3114762` | `promotion` | Promotion CRUD, `promotionCodes.{validate, reserve, redeem, release, find}` with `reserve`/`redeem`/`release` on the caller's transaction |
| T7 #270 | `cfa7b58` | `account` | The fifteen `/api/auth` and supplier-login routes, PBKDF2 hashes byte-compatible with the Kotlin ones, lockout, single-use hashed tokens |
| T8 #271 | `39b507e` | `article` | Categories, subcategories, mugs, SPOD articles (five routes), the storefront, `articleCatalog`, the SPOD catalog sync with calibration |
| T9 #272 | `65ff0d0` | `prompt` | Prompts, slots, slot variants, categories, the anchored ordering, `promptCatalog` |
| T10 #273 | `279b64c` | `generator` (with the former `magic-coins`) | `POST /api/generator/generate` with dummy mode and the fal.ai adapter, the Magic Coin balance and spend |
| T11 #274 | `7917dad` | `production` | Destinations and the catalog sync trigger, the durable request, split, PDF generation (pdf-lib), SFTP delivery (`ssh2`), the SPOD submission, fulfillment for supplier and admin, the SPOD webhook, the worker |
| T12 #275 | `440dd66` | `order` (with the former `payment`) | Placement with the confirmation mail in the same transaction, the paid and cancelled transitions, the three order reads, the three production reads, the Mollie adapter, `startPayment`, the Mollie webhook |
| T13 #276 | `abad54a` | `cart` | The eight cart routes, print-image upload with compensation, reorder, the guest image resolver, `checkoutCarts` |
| T14 #277 | `ecbaf5c` | `checkout`, composition root, `main.ts`, launcher, Dockerfiles | The checkout in two transactions, `createApplication`, static serving of the frontend build, `scripts/start-dev-server.sh` on `bun run dev`, `backend-ts/Dockerfile`, the root `Dockerfile` |
| T15 #278 | this change | smoke run, docs | The oracles below, the fix list, this record, the index and rule updates |

The schema is the same: `backend-ts/migrations/` holds `V1`–`V29`, `V31`
(unchanged copies) and the one new file `V32__email_jobs_payload.sql`. The
Flyway history table is not reused; the runner records in
`schema_migrations`, which is why a database the Kotlin backend created has to
be dropped once (the smoke run did that to `voenix_kotlin`).

## Decisions that changed with the port

Each change is written into the guide of the package it shapes; this is the
list.

| Issue | Decision before | Decision now | Guide |
| --- | --- | --- | --- |
| #247 | Checkout as five commits in five modules with compensations and a unique-violation retry | One checkout in **two transactions**: TX1 locks the cart row, reserves the coupon, places the order with its mail, confirms a free order; the Mollie call runs outside any transaction; TX2 closes the cart. A provider refusal cancels the order and releases the reservation inside `startPayment` | [checkout](../dev/backend-ts/packages/checkout-package.md#decision-one-checkout-two-transactions) |
| #248 | Reference-only email outbox with resolvers; `payment` as its own module; late-bound order sources for production | The outbox stores the **rendered** mail at enqueue time inside the caller's transaction, so `email` is a leaf; `payment` is a subfolder of `order`; `production` declares its order read contracts as types and the composition root injects `order.orders`, so there is no `production → order` import and no late binding | [email](../dev/backend-ts/packages/email-package.md), [order](../dev/backend-ts/packages/order-package.md), [production](../dev/backend-ts/packages/production-package.md#the-one-way-edge-to-the-order-module) |
| #251 | `*Operations` interfaces, repository result types, `UnexpectedFailure`, `validate()` on inputs, the price CRUD routes | Validation once at the route with Zod; no interface with a single implementation; exceptions propagate to `onError` (`500`); the standalone price routes are gone; `respondResult`/`respondFailure`, the one `ApiError` shape, and the SQLSTATE mapping stay | [operation results](../dev/backend-ts/conventions/operation-results.md), [request validation](../dev/backend-ts/conventions/request-validation.md) |
| #252 | `vat` and `magic-coins` as modules | `vat` is part of `pricing`, `magic-coins` part of `generator`; the routes are unchanged | [pricing](../dev/backend-ts/packages/pricing-package.md), [generator](../dev/backend-ts/packages/generator-package.md) |
| #258 | Tests per module repeat the platform cases | Platform behaviour is tested once in platform; a module keeps one happy path per route, its own error cases, its service and repository behaviour, and every unique-conflict concurrency test; schemas are unit-tested once | [code quality](../dev/backend-ts/conventions/code-quality.md), every package guide's "Tests" |

Decisions taken during the run (recorded on #262 and the tickets):

- The email migration is `V32` (the directory already held `V29` and `V31`
  from #260; there is no `V30`).
- `promotionCodes.release(trx, cartId)` joins the caller's transaction like
  `reserve` and `redeem`; the order module releases inside its cancel and
  payment-ended transactions.
- An unsafe image filename that reaches the handler answers `400` with a
  `filename` field error, as the Kotlin `ImageService` did, not `404` as the
  ticket said (the code wins); a literal `..` never reaches the handler.
- The payload outbox removes the `(email_kind, source_id)` uniqueness the SPOD
  webhook relied on for "one ops alert per job"; the idempotency moved into the
  guarded remote-state transition (`UPDATE … WHERE remote_state IS DISTINCT
  FROM $1`, alert only when a row changed), which yields one alert per
  distinct transition.
- The order confirmation mail is enqueued **at placement**, in TX1, as the
  Kotlin code has done since #110; the paid transition enqueues no mail. The
  accepted edge: an order cancelled right after placement because Mollie
  refused still has its confirmation mail (the template is payment-neutral and
  the link shows the real status). The smoke run saw exactly this for its
  refused order.
- `409 CART_IMAGE_UNAVAILABLE` is not implemented: `cart_items.print_image_id`
  is `ON DELETE RESTRICT` and TX1 places from the locked cart's own lines, so
  the case is unreachable.
- One merged Kysely `Tables` interface, augmented per module (Kysely is
  invariant in its table type); "a module queries only its own tables" is a
  review rule.
- Unknown JSON properties answer `400 Unknown property` (the Kotlin backend
  ignored them); the trailing slash under a protected subtree answers
  `401`/`404` (fails closed); the cookie format is new, so Kotlin-era sessions
  are invalid.
- `@hono/bun` is the Bun adapter (Hono deprecates `hono/bun`'s `getConnInfo`
  and `serveStatic`).
- The cart snapshot of a checkout is read before TX1's lock, as in Kotlin;
  the gap is recorded in the checkout guide as a known limitation, not closed.

## What was dropped

- Tests that pinned Kotlin mechanics: visibility and `internal` seams, Ktor
  plugin configuration, migration-state tests that migrate to an older
  version, PDF pixel comparisons, the `CountingDataSource`, the route tests
  against stub operations, the resolver tests (resolvers no longer exist), the
  composition lifecycle tests.
- `frontImage()` of the SPOD client (#260 deleted it before the port).
- The standalone price CRUD routes `POST /api/admin/prices`,
  `GET /api/admin/prices/{id}`, `PUT /api/admin/prices/{id}` (#251; no caller).
- The late-bound sources `LateBoundProductionSource`, `ProductionOutbox`,
  `OrderItemReader`, `OrderPlacement`, `FulfillmentOrderSource`,
  `ShippingNotificationOrderSource`, `ProductionSource`,
  `OrderPaymentGateway`, `OrderPaymentStatusSource`: one exported `orders`
  object and the injected `ProductionOrderReads` replace them.
- `UnexpectedFailure`, the `Unreachable` handling modes, `Logger.databaseOperation`.
- `EmailActionUrl`/`EmailRecipient` and the `SOURCE_*`/`RENDERING_FAILED`
  outbox codes (a stored payload can only fail at delivery).
- The `readChunks` helper of the body limit (Hono buffers before the handler).
- The image module's processing semaphore, the version snapshot with retries
  of a derived file, multi-range answers, `412` preconditions.
- The Kotlin print-image pre-check of a placement (unreachable, see above),
  the "belongs to another order" guard before cancelling at Mollie, the
  `PlaceOrderInput.validate()`.
- Kotlin's serializable retry loop of the single-default VAT (the partial
  unique index is the guarantee; a racing second default answers `409`).

## The smoke run (T15, 2026-10-15)

Environment: this machine (`backend-ts` on Bun 1.4.2, Docker, the
`voenix-postgres` container on port 5432, no `backend-ts/.env.local`, no
`ngrok`), the frontend's `node_modules` installed with
`bun install --frozen-lockfile` (the lockfile did not change). The
development database `voenix_kotlin` was dropped and recreated empty before the
first start, so the TypeScript runner migrated it from `V1` to `V32`.

### The oracles

| # | Oracle | Result |
| --- | --- | --- |
| 1 | `bun run check` in `backend-ts/` | **Run.** Before the fix list: typecheck, lint (one warning: a stale `eslint-enable` directive, removed by this ticket), format clean; 613 tests pass, 0 fail, 73 files, 40 s. After the fix list: see the end of this section |
| 2 | `scripts/start-dev-server.sh` starts `backend-ts` and the Vite frontend; the frontend works against it with no change to `frontend/` | **Run with a caveat.** Both processes started (`[backend] Voenix backend listening`, `[frontend] VITE v8.0.0 ready`); `/api/countries` answered `200` on 8080 and `/` on 5173. The UI itself was **not driven**: the only browser available to the run (the T3 Code preview) runs on another machine and cannot reach this one's loopback. Every route the frontend calls (every row of [the API contract map](../dev/frontend/api-contract-map.md)) was exercised with `curl` instead, with the bodies the stores send; the observations are below |
| 3 | First admin bootstrapped and `scripts/seed-catalog.mjs` complete on a fresh database | **Run.** `POST /api/auth/register` → `204`; the address confirmed in SQL (with `EMAIL_ENABLED=false` nothing is mailed, and the token is stored only as a hash, so the link cannot be read from the database; the account guide now says so); `ADMIN` granted with the guide's `INSERT`; login `204`, `GET /api/auth/me` lists `["ADMIN","CUSTOMER"]`; the seed created one VAT, one supplier, one category, two subcategories, three mugs, one prompt category, one subcategory, three prompts and reported `3 mugs and 3 prompts`; a second run reused everything |
| 4 | The customer journey; Mollie through ngrok | **Partly run.** Everything up to the payment: see the journey below. The free order (100 % coupon) went through TX1 to `PAID` with a production request and ended as a shipped job with a delivered PDF; the paid order reached `502 PAYMENT_NOT_STARTED` with the placeholder key (Mollie answered `400` to the create call, the order is `CANCELLED`, the cart stays `ACTIVE`). The Mollie webhook was **not run**: no test key, no tunnel on this machine. Joe's steps are below |
| 5 | SPOD staging sync and order of a synced article | **Not run**: no SPOD staging token on this machine. The SPOD paths that need no credential were exercised: a SPOD destination is refused with the `channel` field error while `PRODUCTION_SPOD_*` is unset, `sync-articles` on an SFTP destination answers `409 CHANNEL_WITHOUT_CATALOG`, `GET /api/articles/spod` answers `[]`. Joe's steps are below |
| 6 | The full-stack image builds from the root `Dockerfile` and serves the frontend build | **Run.** `docker build -t voenix-shop-t15 .` succeeded (829 MB); run with `--network host` on `PORT=8081` against the dev database: `/`, `/cart`, `/admin/orders` answer `index.html` with `Cache-Control: no-cache, no-store`, `/assets/index-….js` with `max-age=31536000, public, immutable`, `/sw.js` with `no-cache, no-store`, `/api/countries` and `/api/articles/mugs` the JSON, `/api/nowhere` a `404`. Image and container removed afterwards |

### The journeys, step by step

Admin (session of `admin@example.com`, CSRF token from
`GET /api/antiforgery/token`):

1. `POST /api/admin/promotions` `{name, couponCode: "SMOKE100", discountType: "PERCENTAGE", discountValue: 100, isActive: true}` → `201`.
2. The seed script assigns **no supplier** to its mugs (`supplierId: null`, as
   the Kotlin seed did), so the production split would stop with
   `ITEM_WITHOUT_SUPPLIER`. `PUT /api/admin/articles/mugs/1` with
   `supplierId: 1`, `supplierArticleNumber: "DEV-001"` and the existing
   variants by id → `200`. This is the admin's job, not a port issue.
3. `POST /api/admin/supplier-logins` `{supplierId: 1, email}` → `201` (the
   invitation is a no-op with mail disabled; the password was set through
   `createPasswordHasher(600000).hash(…)` in SQL, as the account guide now
   describes). `GET /api/supplier/me` as that login → `200 {supplierId: 1, …}`.
4. An SFTP server was started from the production test fixture
   (`startSftpServer()`, `ssh2`, password `producer`/`secret-word`, a fresh
   ed25519 host key) and `POST /api/admin/production/destinations` with
   `channel: "SFTP"`, its port and `SHA256:` fingerprint, `remotePath: "/"`,
   a `notificationEmail` → `201`; `GET …/destinations/1` answers it without
   the password.

Customer (`erika@example.com`, registered, confirmed in SQL, logged in):

5. `GET /api/magic-coins/balance` → `{balance: 10}`.
6. `POST /api/generator/generate` (multipart `image` PNG 1600×900, `promptId=1`,
   `articleId=1`) → `200 image/png`, the uploaded bytes back (dummy mode);
   balance `9` afterwards.
7. `POST /api/cart/images` with that blob as part `file` → `201 {id: 1}`. A
   first attempt with `curl`'s default `application/octet-stream` part type
   answered `400 file: Only JPEG, PNG, and WebP uploads are supported`; the
   frontend sends the blob with its real type (`design.png`), so this is the
   documented declared-type check, not a mismatch.
8. `POST /api/cart/items` `{articleId: 1, variantId: 1, quantity: 2, promptId: 1, imageId: 1}` → `200`, the `CartView` with `price 1490`, `promptPrice 499`, `subtotal 3978`, `shippingCost 490`.
9. `POST /api/cart/promotion` `{promotionCode: "SMOKE100"}` → `200`, `discountAmount 4468`, `total 0`; `PATCH /api/cart/items/1` `{quantity: 1}` → `200`; `GET /api/images/guest/400/1` → `200 image/webp`.
10. `POST /api/checkout` (shipping address with `email`, `phone: ""`, `billingAddress: null`) → `201 {orderId: 1, checkoutUrl: null}`, `Location: /api/orders/1`, `Cache-Control: no-store`. Rows afterwards: `orders.status = PAID`, `promotion_redemptions` one row, `promotion_reservations` empty, `production_requests` one open row, `email_jobs` one `Bestellbestätigung #1`, cart 1 `CHECKED_OUT`, `GET /api/cart` the empty view.
11. `GET /api/orders/1` and `GET /api/orders` → the `PAID` order with `total 0`; `GET /api/order-lookup/{access_token}` (token read from the row; it is never in a response) → the same order; a bogus token → `404`.
12. `POST /api/cart/order-items/1` (reorder) → `200`, a new cart with the line at today's price.
13. `POST /api/checkout` without a coupon → `502 {"code":"PAYMENT_NOT_STARTED"}`; the log shows `Mollie refused creating the payment of order 2 with status 400` (the placeholder key reaches Mollie's API); `GET /api/orders/2` → `CANCELLED`; `POST /api/checkout/orders/2/payment` → `409 ORDER_NOT_PAYABLE`; `POST /api/checkout/orders/1/payment` → `409 ORDER_ALREADY_PAID`; the cart stays `ACTIVE` with its line.

Production (the worker runs every 60 s; nothing was triggered by hand):

14. Within one scan: `Production request split` (1 job), `Production artifact generated` (job 1), `Production delivery accepted` (delivery 1). Rows: job `SFTP`, `ORD-1.pdf`, `generated_at` and `prepared_at` set; the SFTP server holds `/ORD-1.pdf` (14,368 bytes); `email_jobs` gained `Neue Bestellung #1 – ORD-1.pdf` for `producer@example.com`.
15. Supplier: `GET /api/supplier/production-jobs?status=OPEN` → the job with the snapshotted line and `pdfAvailable: true`, `Cache-Control: no-store`; `GET …/1/pdf` → `200 application/pdf`, 2 pages (677×281 pt and 567×255 pt by pdf-lib), byte-identical (SHA-256) to the artifact on disk and to the admin download.
16. Admin: `GET /api/admin/production/jobs?status=OPEN&supplierId=1` → the job with `supplier`, `fulfillmentChannel: "SFTP"`, `generationAttemptCount: 1`; `GET …/jobs/1/pdf` → the same bytes; `GET /api/admin/orders/1/production-pdfs` → `[{supplierId: 1, fileName: "ORD-1.pdf"}]` and `…/production-pdfs/1` → a PDF generated on demand.
17. `POST /api/supplier/production-jobs/1/ship` `{carrier: "DHL", trackingNumber}` → `200` with `shippedAt`; a second ship → `409 ALREADY_SHIPPED`; the admin ship → `409 ALREADY_SHIPPED`; both `SHIPPED` lists show the job; `email_jobs` gained `Dein Paket ist unterwegs – Bestellung ORD-1`. With `EMAIL_ENABLED=false` every job stays open with `attempt_count 0`, as designed.

Every other row of the contract map (admin CRUD of suppliers, VAT,
promotions, countries list, article categories and subcategories with the
example-image upload, mugs with the variant-example-image upload and the
reorder, the five SPOD routes, prompt categories, subcategories, slots, slot
variants, prompts with the example-image upload and the reorder, destinations,
supplier logins, the profile, change-password, change-email, the three link
flows with a bogus token (`400 INVALID_LINK`), resend-confirmation and
forgot-password (`204`), logout and re-login, the public image routes with
`ETag` and `Cache-Control: public, max-age=86400`, the guest image as a blob
with `private, max-age=3600`, the anonymous `401`/`403`/`404` cases, a
traversal name (`400`), a missing CSRF token (`400 Invalid CSRF token`))
answered the documented status and shape. Observations that are not
mismatches but worth knowing:

- `DELETE /api/admin/articles/mugs/{id}` answers `204` for a mug that was
  ordered (the order lines are snapshots and survive); the cart lines holding
  it vanish with the article. Same schema, same behaviour as Kotlin.
- The Vite dev server binds `[::1]:5173` only; a browser on the same machine
  reaches it through `localhost`, a tool on `127.0.0.1` does not. Frontend
  configuration, untouched by the port.
- An unknown `/api/…` path answers Hono's plain-text `404 Not Found`, not the
  JSON `ApiError`; no frontend code reads a 404 body on an unknown path.
- The confirmation mail of the Mollie-refused order 2 is queued (the accepted
  edge of #110, see above).

### Fix list

One mismatch class was found (by T9 during the port, confirmed in the run) and
fixed; nothing else the frontend sends was answered differently from the
contract map.

| Route(s) | Cause | Fix | Test |
| --- | --- | --- | --- |
| `POST /api/admin/prompts/example-images`, `POST /api/admin/articles/subcategories/example-images`, `POST /api/admin/articles/mugs/variant-example-images`, `POST /api/cart/images`, `POST /api/generator/generate` | A body that is not a multipart form makes `c.req.formData()` throw a `TypeError`; the prompt route answered `500`, the four others had each caught it and read an empty form (`400` on the `file`/`image` part). The Kotlin backend answered `415` through Ktor's `UnsupportedMediaTypeException`. | One `formBody` middleware in `platform/http/respond.ts`, the multipart counterpart of `jsonBody`: `415 Unsupported media type` for any other content type, `400 Malformed request body` when the announced form cannot be parsed, otherwise the handler reads the form Hono parsed and cached. The five routes install it; the per-route `catch` is gone. | `src/platform/http/app.test.ts`, "a multipart form body" (three tests); the module tests of prompt, article, cart, and generator pass unchanged |

Also in this change: the stale `/* eslint-enable boundaries/dependencies */`
in `src/modules/checkout/test-fixtures.ts` that `bun run check` warned about.

### Open point (a): settled above. Open point (b): the chunked webhook body

T11 recorded that the platform body limit buffers a chunked body (no
`Content-Length`) before any route runs, so "the secret is compared before the
body is read" holds for the two webhooks only when the request announces its
length. Decision of this ticket: **keep the middleware, record the caveat.**
Exempting `/api/payments/webhook/*` and `/api/production/webhooks/spod/*` from
`bodyLimit` would leave the two routes with no transfer bound at all while
their handlers read the whole body (`parseBody()`, `text()`); both providers
send small bodies with a `Content-Length`; and a per-route limit is more code
than a 30,000,000-byte buffer per unauthenticated request is worth today. The
caveat is written into
[request size limits](../dev/backend-ts/conventions/request-size-limits.md#the-two-webhooks),
[the order guide](../dev/backend-ts/packages/order-package.md#the-webhook), and
[SPOD fulfillment](../dev/backend-ts/packages/spod-fulfillment.md#the-webhook-how-a-shipment-comes-back).

### How Joe runs oracle 4 (Mollie through ngrok)

1. Put into `backend-ts/.env.local` (never into `.env.development`):

   ```sh
   MOLLIE_API_KEY=test_…                       # Mollie dashboard → Developers → API keys → test key
   MOLLIE_WEBHOOK_SECRET=<at least 16 characters>
   MOLLIE_WEBHOOK_URL=https://<your-domain>.ngrok.app/api/payments/webhook/<the same secret>
   ```

   `MOLLIE_REDIRECT_URL` keeps the `.env.development` value
   (`http://localhost:5173/checkout`). The webhook URL is a **startup**
   setting (the settings refuse to start when it does not end in the secret),
   so the tunnel's domain must be known before the backend starts: either use
   a reserved ngrok domain (`ngrok http 8080 --url=<your-domain>.ngrok.app`),
   or start `ngrok http 8080` first, read the `url=` line, write it into
   `.env.local`, then start the backend. Nothing is configured in the Mollie
   dashboard: the backend passes `webhookUrl` with every payment it creates.
2. Start the stack: `scripts/start-dev-server.sh --with-ngrok` (or the plain
   launcher next to a separately started tunnel).
3. In the shop: generate an image, add a mug, no coupon (or a partial one),
   check out → `201 {orderId, checkoutUrl}`; open `checkoutUrl`, choose a
   test method and the status **Paid** on Mollie's test page.
4. Mollie calls `POST /api/payments/webhook/<secret>` through the tunnel
   (`[ngrok]` log line); the backend fetches the payment, the order becomes
   `PAID`, the redemption and the production request are written in the same
   transaction, and the confirmation page (`GET /api/orders/{id}`) shows
   `paymentStatus: "PAID"`. Within a minute the worker splits the request;
   the job, the PDF, and the ship flow are then what steps 14–17 above show.
5. Also worth one try: pick **Failed** or **Canceled** on the Mollie page: the
   webhook stores the status, the reservation is released, and
   `POST /api/checkout/orders/{id}/payment` starts a new payment for the same
   order.

### How Joe runs oracle 5 (SPOD staging sync and order)

1. Put into `backend-ts/.env.local`:

   ```sh
   PRODUCTION_SPOD_WEBHOOK_SECRET=<at least 32 characters>
   PRODUCTION_SPOD_ALERT_EMAIL=<the ops address>
   ```

   Both or neither; without them every SPOD destination write is refused
   with a `channel` field error, and a start with an existing SPOD
   destination refuses.
2. Create (or reuse) the print-on-demand supplier under
   `/admin/suppliers`, then the destination,
   `POST /api/admin/production/destinations` (the destinations page does the
   same):

   ```json
   {
     "supplierId": <that supplier>,
     "channel": "SPOD",
     "label": "Spreadconnect staging",
     "enabled": true,
     "spod": { "environment": "STAGING", "accessToken": "<token from the Spreadconnect backoffice>", "timeoutSeconds": 30 }
   }
   ```

3. Press *Sync from Spreadconnect* on the destination
   (`POST …/destinations/{id}/sync-articles`); the `200` answer is the
   `SpodSyncReport`. The run never activates: open the synced article under
   `/admin/articles/spod`, give it a category, a price, a default variant, and
   set it active (an article whose frame could not be measured answers `400`
   on `active`).
4. Order it in the shop: a SPOD line makes the phone required
   (`shippingAddress.phone`). The payment needs oracle 4, **or** a 100 %
   coupon makes it a free order that is `PAID` in TX1 without Mollie.
5. The worker's submission stage uploads the design, creates and confirms the
   partner order and stores `external_reference`; the admin job row shows
   `fulfillmentChannel: "SPOD"`, `remoteState`, and no PDF.
6. For the shipment coming back, register the webhook in the Spreadconnect
   backoffice as
   `https://<your-domain>.ngrok.app/api/production/webhooks/spod/<PRODUCTION_SPOD_WEBHOOK_SECRET>`
   (the same tunnel as oracle 4); a `Shipment.sent` event ships the job by
   channel and queues the shipping notification.

### `bun run check` after the fix list

The implementer's final run of this ticket, with the fix list applied and the
smoke run's servers stopped: `tsc --noEmit`, `eslint .` (no warning left),
`prettier --check .` clean; **616 tests pass, 0 fail**, 2867 `expect()`
calls, 73 files, 41 s. The orchestrator's acceptance run repeats it.

## Open points after the port

- **#249** (category model) and **#250** (shared articles table): out of scope
  of the port by decision; they change schema and admin API and therefore the
  frontend.
- **Splitting the workers** into a second process: `main.ts` runs the HTTP
  server and both workers in one process as before; no flag was built.
- **Removing `backend/`**: Joe's call. Until then the Kotlin guides stay
  indexed under "Kotlin backend (retained until removed)" in `docs/dev/README.md`,
  `backend/AGENTS.md` and the Kotlin rules in the root `AGENTS.md` stay, and
  `backend/Dockerfile` still builds the Kotlin image.
- **The pre-lock snapshot gap** of the checkout: the cart snapshot is read
  before TX1 locks the cart row, so a line added in between is not in the
  order (as in Kotlin). Recorded in the checkout guide with the alternative (a
  `trx`-taking `activeCart`).
- **The chunked webhook body**: see open point (b) above.
- **Oracles 4 and 5**: not run on this machine; the steps above.
- **The seed script and suppliers**: `scripts/seed-catalog.mjs` creates a
  supplier but assigns it to no mug, so a seeded shop cannot produce an order
  until an admin sets the supplier on each mug. Pre-existing; worth one line
  in the script when it is touched next.
