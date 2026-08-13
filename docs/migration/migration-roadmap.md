# Migration roadmap

This file tracks which legacy .NET features are still waiting for migration
and in which order they should be migrated. The order is derived from the
`using Voenix.Features.*` dependencies in the legacy source at
`/Users/joe/projects/joto-ai/voenix-shop/backend/Voenix.Api/Features`.

General migration rules remain in
[`module-migration-guide.md`](module-migration-guide.md). Each migration still
gets its own record copied from [`migration-base.md`](migration-base.md).

## Already migrated

| Kotlin module | Legacy source |
| --- | --- |
| `country` | `Features/Country` |
| `vat` | `Features/Vat` |
| `supplier` | `Features/Supplier` |
| `pricing` | `Features/Pricing` |
| `email` | `Features/Email` |
| `image` | `Features/Image` |
| `magic-coins` | `Features/MagicCoins` |
| `production` | `Features/SftpUpload` plus `Order/PdfDocument.cs`, `Order/Services/PdfService.cs`, `Order/Services/PaidOrderProcessor.cs` |
| `platform` (auth) | Session, CSRF, and guest-token infrastructure, including the `Features/Antiforgery` endpoint (`GET /api/antiforgery/token`) |
| `account` | `Features/Auth` plus `Configuration/AuthConfiguration.cs` and the Auth mappings in the exception handler; the guest-data claim is deferred to the Cart migration (see [`account-migration.md`](account-migration.md)) |
| `article` | `Features/Article` plus the article-owned parts of `Features/Pricing` and the two example-image pipelines; exports the `ArticleCatalog` capability that Cart, Order, and the production adapter consume. The Vue frontend adaptation and the orphaned example-image sweep are deferred; the order snapshot of production data was delivered by the Order migration (see [`article-post-migration.md`](article-post-migration.md)). The implementation is complete; the council verification of the module record is still open |
| `prompt` | `Features/Prompt` plus the prompt-owned parts of `Features/Pricing` and the example-image pipeline; exports the `PromptCatalog` capability that Generator (composed generation text) and Cart (gross sales price in cents) consume. The Vue frontend adaptation and a few smaller follow-ups are deferred (see [`prompt-post-migration.md`](prompt-post-migration.md)). The implementation is complete; the council verification of the module record is still open |
| `promotion` | `Features/Promotion`; exports the `PromotionCodes` capability. The usage-limit check that `Order/Services/PaidOrderProcessor.cs` duplicated now lives only in this module's `redeem`, which the Order migration made transactional and gave the deferred `promotion_redemptions.order_id` column. The customer-facing shape of the `PROMOTION_*` errors was delivered by Cart and moved into this module as the public `toApiError()` by Checkout; the capacity reservation by in-flight orders was the last deferred item and was delivered by the Checkout migration of 2026-08-02 as the module-owned `promotion_reservations` table with `reserve`/`release` (see [`promotion-migration.md`](promotion-migration.md) and [`promotion-post-migration.md`](promotion-post-migration.md)) |
| `cart` | `Features/Cart` plus the guest-data claim deferred by Account (`Auth/Services/GuestDataClaimService.cs`), the guest image service and route from `Features/Image`, and the Cart/Promotion mappings in the exception handler. It owns the print-image registry (`print_images`, legacy `GeneratedEditedImage`) that Order depends on — the Generator turned out not to need it, because it stores nothing (see [`generator-migration.md`](generator-migration.md)) — exports the `CartGuestImages` and `CartGuestData` capabilities, and fixes the customer-facing wire format of the `PROMOTION_*` failures. The reorder endpoint and the order claims were delivered by the Order migration, the `CHECKED_OUT` write path by the Checkout migration through the exported `CheckoutCarts` capability; the Vue frontend adaptation and the MagicCoins guest-balance claim remain open (see [`cart-migration.md`](cart-migration.md)) |
| `order` | `Features/Order` (the `Order`/`OrderItem` domain, `Domain/OrderStatus.cs`, the EF configurations, and `Controllers/PdfController.cs`) plus the order-owned parts of `Features/Checkout` (`CheckoutService`'s order creation and the customer read paths), the order branch of `Auth/Services/GuestDataClaimService.cs`, and `CartService.ReorderOrderItemAsync`. It binds the four ports earlier migrations left open — Production's `ProductionSource`, Email's order-confirmation branch, Account's order claim, and Cart's reorder — adds `promotion_redemptions.order_id` and the `production_requests` order foreign key, and makes `PromotionCodes.redeem` transactional. The payment went to the Payment migration of 2026-08-01, which consumed the exported `OrderPaymentGateway` and the declared `OrderPaymentStatusSource`; the checkout orchestration went to the Checkout migration of 2026-08-02, which consumes the `OrderPlacement` capability this module gained for it. Only the frontend adaptation is still recorded as open in [`order-post-migration.md`](order-post-migration.md). The implementation is complete; the council verification of the module record is still open |
| `payment` | `Features/Payment` (controller, service, domain, DTOs, exceptions) plus `Configuration/MollieOptions.cs`, the payment branches of `Features/Checkout/Services/CheckoutService.cs` (payment creation, the create-failure compensation, the `paymentStatus` joins), and the payment rows of the exception handler. It owns the `payments` table with its one-live-payment-per-order index, a hand-written Ktor Mollie adapter, and one route — the webhook, protected by a secret path segment. It binds the third late-bound port (`OrderPaymentStatusSource`), so `paymentStatus` is back in both order responses, and it takes over the order-cancellation compensation on a failed payment creation. The two legacy endpoints are deliberately not migrated (deviation D1), and there is no dummy mode (D16). The retry-payment flow and the caller of `start` were delivered by the Checkout migration of 2026-08-02, which consumes the `PaymentStarter` capability this module gained for it; the admin anomaly page, the reconciliation sweep, the frontend adaptation, and the local dev setup are recorded in [`payment-post-migration.md`](payment-post-migration.md). All four tickets are implemented; the council verification of the module record is still open (see [`payment-migration.md`](payment-migration.md)) |
| `generator` | `Features/Generator` plus `Configuration/GeneratorOptions.cs`, `Configuration/GeneratorOptionsValidator.cs`, and the Generator/MagicCoins branches of the exception handler. The module is stateless: it answers raw image bytes and stores nothing. It consumes `PromptCatalog.composedText` and the `GenerationCoins` capability that this migration made MagicCoins export, and it hardens the legacy endpoint with CSRF protection, a 10 MiB upload limit, and a guarded result download. Abuse protection of the anonymous, cost-incurring endpoint and the `16:9` aspect ratio are open product decisions (see [`all-post-migration.md`](all-post-migration.md)); the council verification, simplification review, and retrospective are complete (see [`generator-migration.md`](generator-migration.md)) |
| `checkout` | `Features/Checkout` (controller, service, DTOs, exceptions), the checkout rows of the exception handler, and the checkout half of `Features/Promotion/Services/PromotionApplicationService.cs`. The module is stateless: it owns no table, opens no transaction, and exports nothing — it is the one place where cart, promotion, order, and payment meet. It brought each of those four modules a capability of its own (`CheckoutCarts`, the promotion reservation lifecycle, `OrderPlacement`, `PaymentStarter`), replaced the legacy in-flight query with the promotion-owned `promotion_reservations` table (`V18`), and added the retry route `POST /api/checkout/orders/{orderId}/payment` that the legacy shop never had. The two legacy read routes were already delivered as `/api/orders` by the Order migration. The Vue frontend adaptation and the shipping-country policy remain open (see [`checkout-migration.md`](checkout-migration.md) and [`all-post-migration.md`](all-post-migration.md)) |

`Features/Antiforgery` therefore needs no migration of its own.

## Remaining features and their blockers

None. Checkout was the last legacy feature and was migrated on 2026-08-02, so
this table is empty and the dependency graph with it.

The legacy backend at `/Users/joe/projects/joto-ai/voenix-shop/backend` has no
feature left that this backend does not own. What is still open is not a
migration: the Vue frontend has to be pointed at the Kotlin API, and the
deferred product decisions and operational tools of the individual records are
collected in the `<module>-post-migration.md` files and in
[`all-post-migration.md`](all-post-migration.md).

## Migration order

Waves group features whose blockers are all migrated once the previous wave
is done. Features inside a wave are independent of each other and may be
migrated in any order, or in parallel worktrees.

### Wave 1 — no open blockers

Seven Wave-1 items are done and the wave is empty. Auth (module `account`,
2026-07-24); Promotion (module `promotion`, 2026-07-26), which exports the
`PromotionCodes` capability Order redeems and Checkout re-checks;
Article (module `article`, 2026-07-28), which exports the `ArticleCatalog`
capability; Prompt (module `prompt`, 2026-07-28), which exports the
`PromptCatalog` capability; Cart (module `cart`, 2026-07-30), which picked up
Auth's deferred guest-data claim and whose `print_images` table promoted both
remaining Wave-1 items into this wave; Generator (module `generator`,
2026-07-30), which bound the second half of `PromptCatalog` and redeemed the
coin capability MagicCoins had deferred; and Order (module `order`,
2026-07-31), which bound the real `ProductionSource`, the order-confirmation
branch of the app-owned `AggregatedQueuedEmailSource`, the order half of the
guest-data claim, and the cart's reorder route, and which made
`PromotionCodes.redeem` transactional and gave it the deferred
`promotion_redemptions.order_id` column (see
[`promotion-post-migration.md`](promotion-post-migration.md) and
[`cart-migration.md`](cart-migration.md)).

The guest-data claim named twice above is superseded by issue #110
(2026-08-11): it was removed from cart, order, and account again, and a guest
now keeps their order through the permanent link of the confirmation mail.

Wave 1 has no remaining items.

### Wave 2

Wave 2 is empty as well. Payment (module `payment`, 2026-08-01) was its single
item: it confirms an order through the `OrderPaymentGateway` the order module
exports, owns the `payments` table with `payments.order_id`, returns
`paymentStatus` to both order responses through the late-bound
`OrderPaymentStatusSource`, took over the order cancellation on a failed payment
creation, and made a second **live** payment for one order impossible through the
partial unique index `ux_payments_live_order` rather than through provider
idempotency alone — a retry after a payment that ended is a second payment row
for the same order, which is the point of the index being partial.

### Wave 3

Wave 3 is empty. Checkout (module `checkout`, 2026-08-02) was its single item and
the last legacy feature of all: it composes four finished modules instead of
stubs, places the order through `OrderPlacement`, closes the cart through
`CheckoutCarts.markCheckedOut`, reserves the coupon through the promotion
module's new `reserve` — the pre-payment re-check and the capacity reservation in
one operation — and starts the payment through `PaymentStarter`. The retry-payment
journey for an order whose payment ended terminally came with it (see
[`checkout-migration.md`](checkout-migration.md),
[`promotion-post-migration.md`](promotion-post-migration.md),
[`order-post-migration.md`](order-post-migration.md), and
[`payment-post-migration.md`](payment-post-migration.md)).

With Wave 3 empty, every wave is empty: the legacy backend has no remaining
features and can be retired.

## Keeping this file current

When a migration lands, move its row into the "Already migrated" table and
remove it from the waves. If a migration discovers a new dependency, update
the graph and the waves instead of working around it.

Since 2026-08-02 there is nothing left to move: every legacy feature is
migrated. Keep the table itself current when a module's deferred work is
delivered, so a reader can still see what each module owes and to whom.
