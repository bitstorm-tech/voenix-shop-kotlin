# Developer documentation

This folder is the developer documentation of the backend and the Vue
frontend. The backend is the TypeScript backend in `backend/` (see
[its technology-stack guide](backend/conventions/technology-stack.md) for
the decision and [the port record](../migration/typescript-port.md) for what
the port changed); the Kotlin backend it replaced, and its guides, are kept
in the Git tag `kotlin-backend`. Every guide is written for
developers who are still learning the language and the framework: it explains
what the code does, which files do it, and why it is built that way. Start
here; the reading order below takes you from an empty machine to a running
shop to one module's internals.

## Where to start

1. [Running the development server](backend/getting-started/running-the-development-server.md)
   — start backend and frontend with one script; the environment files and
   the development database.
2. [Seeding the development catalog](backend/getting-started/seeding-the-development-catalog.md)
   — a fresh database has no catalog; this script enters a small one through the
   admin API, once the first administrator exists.
3. [Backend modules](backend/conventions/module-architecture.md)
   — how the backend is cut into modules, what one module may import from
   another, and how ESLint enforces it. Read this before opening any package
   guide.
4. [Source file organization](backend/conventions/source-file-organization.md)
   — the five files of a module, factory functions instead of classes, tests
   next to the code.
5. [The Country package](backend/packages/country-package.md), the reference
   module every other module copies, then one package guide of your choice,
   for example [The Cart package](backend/packages/cart-package.md). Every
   package guide has the same skeleton: what it does, a five-minute mental
   model, the file map, the HTTP API, the topics that make this package
   special, composition, tests.

## Folders

| Folder | What you find there |
| --- | --- |
| [`backend/getting-started/`](backend/getting-started/) | Running, seeding, importing legacy catalog data, building the deployable image. |
| [`backend/conventions/`](backend/conventions/) | Cross-cutting rules and shared mechanisms of the backend: the technology stack, modules, file layout, code quality, results and errors, validation, limits, authentication. |
| [`backend/packages/`](backend/packages/) | One guide per backend module (`backend/src/modules/<name>`), plus the SPOD channel guide that belongs to the production module. |
| [`frontend/`](frontend/) | How the Vue frontend talks to the backend and which routes it calls, plus frontend conventions such as landing pages and form controls. |
| [`guides/`](guides/) | Operator how-tos that cross backend, frontend, and admin UI. |

The folders `docs/migration` (the records of the .NET → Kotlin migration and of
the Kotlin → TypeScript port) and `docs/agents` (instructions for coding
agents) sit next to this one and are not developer documentation in this
sense; the guides link into them where a migration record is the reason for a
design. There are no separate decision records: the reason for a design, and
the alternatives it beat, are written into the guide of the package it shapes.

## Backend: getting started

| Guide | What it answers |
| --- | --- |
| [Running the development server](backend/getting-started/running-the-development-server.md) | How do I start the backend and the frontend, and which environment variables configure the backend? |
| [Seeding the development catalog](backend/getting-started/seeding-the-development-catalog.md) | How do I get a demo catalog into an empty database? |
| [Importing catalog data from a legacy backend](backend/getting-started/importing-legacy-catalog-data.md) | How do I copy the real catalog from the live Go backend (or the .NET one) into a database? |
| [The full-stack image](backend/getting-started/full-stack-image.md) | How is the one container built that serves the backend and the frontend build together, and how is it configured and deployed? |

## Backend: conventions

| Guide | What it answers |
| --- | --- |
| [The technology stack](backend/conventions/technology-stack.md) | Why TypeScript on Bun with Hono, Zod, and Kysely, and which alternatives lost. |
| [Backend modules](backend/conventions/module-architecture.md) | The module graph, how ESLint enforces it, the module shape, and the one `Tables` type. |
| [Source file organization](backend/conventions/source-file-organization.md) | The five files of a module, factory functions instead of classes, tests next to the code. |
| [Code quality](backend/conventions/code-quality.md) | `bun run check`: what the type checker, ESLint, Prettier, and `bun test` enforce. |
| [Shared operation results](backend/conventions/operation-results.md) | `OperationResult<T>` as a union, and `respondResult` with the per-route messages. |
| [Request validation](backend/conventions/request-validation.md) | Zod at the route: `jsonBody`, `formBody`, the `400` contract, strict objects. |
| [Backend persistence error handling](backend/conventions/persistence-error-handling.md) | `executePostgresWrite`, `read`/`write`, and the statement bounds. |
| [Request size limits](backend/conventions/request-size-limits.md) | The 30,000,000-byte transfer bound, how Hono applies it, and the webhook caveat. |
| [Rate limiting](backend/conventions/rate-limiting.md) | The per-IP limit of the image generation. |
| [Authentication and authorization](backend/conventions/authentication-and-authorization.md) | Sealed cookies, the principal, the four protections, CSRF, guests. |

## Backend: packages

One guide per module. The storefront's order of events — catalog, prompt,
generated image, cart, checkout, payment, order, production — is a good reading
order once you know the conventions. A new module starts from the country
package guide and the conventions it links.

| Guide | Module | What it owns |
| --- | --- | --- |
| [The Country package](backend/packages/country-package.md) | `country` | The shipping countries and their admin CRUD; the reference module. |
| [The Account package](backend/packages/account-package.md) | `account` | Registration, login, sessions, password reset, supplier logins, the first administrator. |
| [The Supplier package](backend/packages/supplier-package.md) | `supplier` | Suppliers (printers and the print-on-demand partner). |
| [The Pricing package](backend/packages/pricing-package.md) | `pricing` | Prices, VAT rates, the price calculation, the `priceCatalog` capability. |
| [The Article package](backend/packages/article-package.md) | `article` | Articles (mugs and SPOD articles), variants, categories, the storefront catalog, the SPOD catalog sync. |
| [The Prompt package](backend/packages/prompt-package.md) | `prompt` | Generation prompts, slots, prompt categories, the storefront prompt list. |
| [The Image package](backend/packages/image-package.md) | `image` | Image storage, delivery routes, decode and upload limits. |
| [The Generator package](backend/packages/generator-package.md) | `generator` | One image generation: upload, fal.ai call, and the Magic Coins it is paid with. |
| [The Promotion package](backend/packages/promotion-package.md) | `promotion` | Promotion codes, their validation, reservation, and redemption. |
| [The Cart package](backend/packages/cart-package.md) | `cart` | The cart, its lines, print images, totals, and the promotion applied to it. |
| [The Checkout package](backend/packages/checkout-package.md) | `checkout` | Turning a cart into an order in two transactions: address, country, the retry route. |
| [The Order package](backend/packages/order-package.md) | `order` | Placed orders, the access token, cancellation, the order history, and the Mollie payment with its webhook. |
| [The Production package](backend/packages/production-package.md) | `production` | Producing an order: destinations, the production PDF, the durable worker, delivery, fulfillment. |
| [SPOD fulfillment](backend/packages/spod-fulfillment.md) | `production` | The print-on-demand channel of the Production package: submission, webhook, ops alert, runbook. |
| [The SPOD package](backend/packages/spod-package.md) | `spod` | The shared client of the print-on-demand partner: the ten calls, the pacer, the bounded errors, the catalog and calibration answers. |
| [The Email package](backend/packages/email-package.md) | `email` | Direct user emails and the durable outbox of rendered mails. |
| [The Operations package](backend/packages/operations-package.md) | `operations` | The findings: each module's stalled rows, recorded by a worker every five minutes and listed on the admin Operations page; the admin order list and the timeline of one order with its history, and the two admin actions that replace hand-typed SQL, the payment recheck and the release of a quarantined SPOD job. |

## Frontend

| Guide | What it answers |
| --- | --- |
| [How the frontend talks to the backend](frontend/frontend-api-conventions.md) | The one `fetch` wrapper, the error shape, and the conventions the Pinia stores follow. |
| [API contract map](frontend/api-contract-map.md) | Every `/api/…` literal in the frontend and the backend route behind it. |
| [Campaign landing pages](frontend/campaign-landing-pages.md) | How a marketing landing page is added and how it feeds the wizard funnel. |
| [Form controls in the frontend](frontend/form-controls.md) | `Input` versus `PasswordInput`, how attributes reach the real `<input>`, and the UI boundary that bans raw form tags outside `components/ui`. |
| [Long admin forms on one page](frontend/admin-form-sections.md) | How the route-level editors (articles, prompts) stack `AdminFormSection` cards instead of tabs, and how a stopped save marks every problem and scrolls to the first. |
| [Image uploads and pasting from the clipboard](frontend/image-uploads.md) | Where images are uploaded, how `useClipboardImagePaste` lets the user paste one with Ctrl+V, and what a new upload spot has to do. |

## Guides

| Guide | What it answers |
| --- | --- |
| [Configuring a SPOD article](guides/configuring-spod-articles.md) | The operator's manual for print-on-demand products (t-shirt, tote bag, …): partner account, destination, sync, the measured print frame, and activation. |

## Kotlin backend (removed)

The Kotlin backend was replaced by the TypeScript backend in issue #262 and
then removed. Its code and its guides are kept in the Git tag
[`kotlin-backend`](https://github.com/bitstorm-tech/voenix-shop-kotlin/tree/kotlin-backend/docs/dev);
the package guides above link into that tag where a decision was taken there
and still holds.

## Writing a new guide

- Put it into the folder whose question it answers; a new backend module
  gets `backend/packages/<name>-package.md` with the skeleton above.
- Write for a reader who knows another typed language but is new to
  TypeScript, Hono, and Kysely: name the files, show the code path, explain
  the *why*.
- Link to code with relative paths so the link is clickable in the repository.
- Add the guide to this index.
