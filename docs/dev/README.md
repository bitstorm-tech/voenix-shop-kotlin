# Developer documentation

This folder is the developer documentation of the Kotlin backend and the Vue
frontend. It is written for developers who are still learning Kotlin and Ktor:
every guide explains what the code does, which files do it, and why it is built
that way. Start here; the reading order below takes you from an empty machine to
a running shop to one package's internals.

## Where to start

1. [Running the development server](getting-started/running-the-development-server.md)
   — start backend and frontend with one script; the layered configuration and
   the secrets file.
2. [Seeding the development catalog](getting-started/seeding-the-development-catalog.md)
   — a fresh database has no catalog; this script enters a small one through the
   admin API.
3. [Backend compile-time modules](backend/conventions/module-architecture.md)
   — how the backend is cut into Kotlin modules and what one module may import
   from another. Read this before opening any package guide.
4. [Kotlin source file organization](backend/conventions/source-file-organization.md)
   — how declarations are grouped into files; the standard module shape every
   package follows.
5. One package guide of your choice, for example
   [The Cart package](backend/packages/cart-package.md). Every package guide has
   the same skeleton: what it does, a five-minute mental model, the file map, the
   HTTP API, the topics that make this package special, composition, tests.

## Folders

| Folder | What you find there |
| --- | --- |
| [`getting-started/`](getting-started/) | Running, seeding, importing data, building the deployable image. |
| [`backend/conventions/`](backend/conventions/) | Cross-cutting rules and shared mechanisms of the backend: modules, file layout, code quality, results and errors, validation, limits, authentication. |
| [`backend/packages/`](backend/packages/) | One guide per backend package (`shop.voenix.<name>`), plus the SPOD channel guide that belongs to the Production package. |
| [`frontend/`](frontend/) | How the Vue frontend talks to the backend and which routes it calls. |
| [`guides/`](guides/) | Operator how-tos that cross backend, frontend, and admin UI. |

The folders `docs/adr` (architecture decision records), `docs/migration` (the
record of the .NET → Kotlin migration) and `docs/agents` (instructions for
coding agents) sit next to this one and are not developer documentation in this
sense; the guides link into them where a decision or a migration record is the
reason for a design.

## Getting started

| Guide | What it answers |
| --- | --- |
| [Running the development server](getting-started/running-the-development-server.md) | How do I start backend and frontend, and where does the configuration come from? |
| [Seeding the development catalog](getting-started/seeding-the-development-catalog.md) | How do I get a demo catalog into an empty database? |
| [Importing catalog data from a legacy backend](getting-started/importing-legacy-catalog-data.md) | How do I copy the real catalog from the live Go backend (or the .NET one) into my database? |
| [The full-stack image](getting-started/full-stack-image.md) | How is the one container built that serves backend and frontend together, and how does the backend serve the Vue build? |

## Backend conventions

| Guide | What it answers |
| --- | --- |
| [Backend compile-time modules](backend/conventions/module-architecture.md) | Which Kotlin modules exist, what may depend on what, and how a module exports a capability. |
| [Kotlin source file organization](backend/conventions/source-file-organization.md) | Which declarations go into one file; the standard module shape. |
| [Kotlin code quality](backend/conventions/kotlin-code-quality.md) | ktfmt, Ktlint, Detekt: how to run them and what the gate enforces. |
| [Shared operation results](backend/conventions/operation-results.md) | `OperationResult<T>`: how a service reports success, not-found, conflict, and validation failure without HTTP types. |
| [Backend persistence error handling](backend/conventions/persistence-error-handling.md) | How a PostgreSQL constraint violation becomes a typed result instead of a 500. |
| [Request validation](backend/conventions/request-validation.md) | How a request body is checked before a service sees it, and how a rejected body becomes a `400`. |
| [Request size limits](backend/conventions/request-size-limits.md) | The application-wide transfer bound versus a module's own processing limit. |
| [Rate limiting](backend/conventions/rate-limiting.md) | The one rate-limited endpoint (image generation) and how the limit is enforced. |
| [Decimal columns in Exposed](backend/conventions/exposed-decimal-columns.md) | A research note (in German): why `decimal()` still needs precision and scale in Exposed 1.3.1. |
| [Authentication and authorization](backend/conventions/authentication-and-authorization.md) | Who a caller is, what they may do, and when a state-changing request is safe: sessions, guest tokens, roles, CSRF. |

## Backend packages

One guide per package. The storefront's order of events — catalog, prompt,
generated image, cart, checkout, payment, order, production — is a good reading
order once you know the conventions.

| Guide | Package | What it owns |
| --- | --- | --- |
| [The Account package](backend/packages/account-package.md) | `account` | Registration, login, sessions, password reset, the first administrator. |
| [The Country package](backend/packages/country-package.md) | `country` | The shipping countries and their admin CRUD. |
| [The VAT package](backend/packages/vat-package.md) | `vat` | VAT rates. |
| [The Supplier package](backend/packages/supplier-package.md) | `supplier` | Suppliers (printers and the print-on-demand partner). |
| [The Pricing package](backend/packages/pricing-package.md) | `pricing` | Prices, VAT calculation, the `PriceCatalog` capability. |
| [The Article package](backend/packages/article-package.md) | `article` | Articles (mugs, t-shirts), variants, categories, the storefront catalog. |
| [The Prompt package](backend/packages/prompt-package.md) | `prompt` | Generation prompts, slots, prompt categories, the storefront prompt list. |
| [The Image package](backend/packages/image-package.md) | `image` | Image storage, delivery routes, decode and upload limits. |
| [The Generator package](backend/packages/generator-package.md) | `generator` | One image generation: upload, fal.ai call, who pays. |
| [The Magic Coins package](backend/packages/magic-coins-package.md) | `magiccoins` | The generation credits a user or guest spends. |
| [The Promotion package](backend/packages/promotion-package.md) | `promotion` | Promotion codes and their validation. |
| [The Cart package](backend/packages/cart-package.md) | `cart` | The cart, its lines, totals, and the promotion applied to it. |
| [The Checkout package](backend/packages/checkout-package.md) | `checkout` | Turning a cart into an order: address, country, the retry route. |
| [The Payment package](backend/packages/payment-package.md) | `payment` | Mollie payments and the webhook. |
| [The Order package](backend/packages/order-package.md) | `order` | Placed orders, the access token, cancellation, the order history. |
| [The Production package](backend/packages/production-package.md) | `production` | Producing an order: destinations, the production PDF, the durable worker, delivery, fulfillment. |
| [SPOD fulfillment](backend/packages/spod-fulfillment.md) | `production` | The print-on-demand channel of the Production package: submission, webhook, ops alert, runbook. |
| [The SPOD package](backend/packages/spod-package.md) | `spod` | The shared client of the print-on-demand partner: the eight calls, the pacer, the bounded errors, the catalog answers. |
| [The Email package](backend/packages/email-package.md) | `email` | Direct user emails and the durable email outbox. |

## Frontend

| Guide | What it answers |
| --- | --- |
| [How the frontend talks to the backend](frontend/frontend-api-conventions.md) | The one `fetch` wrapper, the error shape, and the conventions the Pinia stores follow. |
| [API contract map](frontend/api-contract-map.md) | Every `/api/…` literal in the frontend and the Kotlin route behind it. |
| [Campaign landing pages](frontend/campaign-landing-pages.md) | How a marketing landing page is added and how it feeds the wizard funnel. |
| [Form controls in the frontend](frontend/form-controls.md) | `Input` versus `PasswordInput`, how attributes reach the real `<input>`, and the UI boundary that bans raw form tags outside `components/ui`. |

## Guides

| Guide | What it answers |
| --- | --- |
| [Configuring a t-shirt article](guides/configuring-t-shirt-articles.md) | The operator's manual: everything that has to exist before a shirt can be sold and produced. |

## Writing a new guide

- Put it into the folder whose question it answers; a new backend package gets
  `backend/packages/<name>-package.md` with the skeleton above.
- Write for a reader who knows Java or another typed language but is new to
  Kotlin and Ktor: name the files, show the code path, explain the *why*.
- Link to code with relative paths so the link is clickable in the repository.
- Add the guide to this index.
