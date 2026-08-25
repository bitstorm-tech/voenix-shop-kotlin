# Voenix Shop

An e-commerce shop for personalized print products (mugs and t-shirts), being
migrated module by module from a legacy .NET backend to Kotlin. One bounded
context; the Kotlin modules share this language.

## Language

**Category**:
The top level of the shared, article-type-agnostic structure that groups
articles for the storefront navigation and the admin.
_Avoid_: taxonomy, Taxonomie

**Subcategory**:
The second and lowest level of that structure; always belongs to exactly one
category.
_Avoid_: taxonomy, Taxonomie

**Category structure**:
Categories and subcategories together, when both levels are meant. Package,
table, and prose say "category" — never "taxonomy"; the term was used during
the Article migration and is retired (decision by Joe, 2026-07-28).
_Avoid_: taxonomy, Taxonomie, classification

**Article type**:
The kind of product an article is (today: mug or t-shirt). Each type owns its
own tables and admin routes; the category structure is shared across all types.
It is also the discriminator the frontend switches on: the storefront, the cart,
and the placed order carry `articleType` per article or per line, and on an
ordered line it is snapshotted like every other line field, so a later type
change in the catalog cannot rewrite what was bought.

**Synced t-shirt article**:
A t-shirt article, and since ADR 0003 there is no other kind: it comes into
being when a **sync run** reads the merchant's Spreadconnect (SPOD) backoffice
for one production destination, and it has two owners for life. The backoffice
owns the garment — name, descriptions, the variants with their colours, sizes,
SKUs and the three partner ids, the mockup image per colour, the size chart —
and every run overwrites that half. The shop owns what the shop decides —
`active`, category and subcategory, display position, price, print frame and
print aspect ratio, default variant — and no run ever touches it. A run never
activates an article; an article the backoffice stopped listing is deactivated
and marked *missing at Spreadconnect*, never deleted, and comes back inactive.
Its identity is (destination, environment, backoffice article id), and a
variant's is the product triple (product type, appearance, size).
_Avoid_: creating a t-shirt in the admin (there is no create route), variant
matrix (the editor that typed one is gone), importing (a sync is a
reconciliation, not a one-time import)

**Price discount / Preisrabatt**:
An optional reduction stored on one Price: either a percentage or a fixed
number of cents, always applied to the gross sales total. Mugs, t-shirts, and
prompts share it, because all three own a Price. It has no code, no activity
window, and no name — it is on whenever it is configured and off when it is
cleared — and the amount everything charges (`salesTotal`) is already reduced
by it.
_Avoid_: coupon, promotion (those apply to the cart total and are entered by
the customer), sale (no campaign object exists)

**Regular price / Regulärer Preis**:
The sales total before the discount (`regularSalesTotal`), and what the
storefront strikes through next to the effective price. It is the price an
operator configured, not a price the shop observed over time: the system does
not check it against the lowest price of the previous 30 days (see ADR 0004).
_Avoid_: list price (reads as a supplier's list in this shop), original price

**Prompt slot**:
A named position in a prompt (e.g. a style or background axis) that groups
interchangeable slot variants. Replaces the legacy term "slot type"
(decision by Joe, 2026-07-28).
_Avoid_: slot type

**Prompt slot variant**:
One concrete option of a prompt slot; its text is appended to the prompt's
own text when the final generation prompt is composed. Variant names are
globally unique across all slots.

**Composed prompt text**:
The generation text a prompt produces: its own text followed by the text of
every slot variant it uses, ordered by slot and joined by a blank line. It is
composed while reading, never stored, and it is what the `PromptCatalog`
capability hands to the image generator.
_Avoid_: final prompt, full prompt

**Placed order / Bestellung**:
The immutable record of what a customer bought: addresses, amounts, and one
line per ordered article, all snapshotted at placement so that a later change
to the catalog, the account, or the promotion cannot rewrite it. It is
`PENDING` until its payment is confirmed, then `PAID`, or `CANCELLED` when the
payment could never be started.
_Avoid_: checkout (the checkout is the flow that produces an order, not the
order itself)

**Reorder**:
Putting an already ordered line back into the cart. It is an ordinary
add-to-cart of one line at today's price that reuses the ordered line's
article, variant, prompt, and print image — never a replay of the old order.

**Print image / Druckbild**:
The image a customer uploads for one cart line, that an ordered line keeps
referring to, and that is printed on the article. It is registered in
`print_images`, stored privately as WebP, and delivered only to its owner. Who
that owner is depends on how it was uploaded, and it never changes afterwards:
an image uploaded anonymously is identified by the guest token it was uploaded
with, an image uploaded while signed in belongs to its **user** (the row carries
the token too, but the token stops identifying it), so a shared browser cannot
pick up what the previous customer uploaded while signed in. The legacy name
`generated_edited_images` is
retired: the image is neither always generated nor always edited (decision by
Joe, 2026-07-29).
_Avoid_: generated edited image, guest image

**Article identity**:
The type-independent registration of an article (and its variants) that gives
carts and orders one foreign-key target across per-type tables. Carries no
business data.

**Cart identity**:
Who a cart belongs to, and it is never two things at once: an anonymous cart is
identified by the guest session token, a signed-in cart by the **user id**. A
signed-in request therefore finds its cart by the account and not by the cookie
it happens to carry. A cart keeps the identity it was created with for life —
nothing moves a cart from the one half to the other (issue #110 removed the one
thing that once did). A database rule on each half allows at most one active
cart per owner (decision by Joe, 2026-08-04, superseding deviation 14 of the
cart migration).
_Avoid_: session cart, token cart (for a cart that belongs to an account)

**Order access token / permanent order link**:
The random 256-bit token every placed order carries, and the link built from it.
The confirmation mail sent at placement points at `/order/{token}`, which the
frontend resolves through the anonymous read `GET /api/order-lookup/{token}`.
It is the durable handle on one order for a customer who has no account or is
not signed in: read-only, that one order, never account access. The token is
stored on the order, is never part of any API response, and the lookup answers
every unknown or malformed token with the same `404` (issue #110).
_Avoid_: order secret, magic link (that name belongs to sign-in links)

**Checkout**:
The flow that turns a filled cart into a placed order and, unless the order is
free, into a payment the customer is sent to. It is a journey, never a thing
that is stored: the rows it leaves behind belong to the cart, the promotion, the
order, and the payment.
_Avoid_: checkout as a synonym for the placed order

**Production job / Produktionsauftrag**:
One supplier's share of one placed order: the row the split worker creates per
involved supplier, with the item snapshot stored next to it. It is the unit a
supplier sees, prints, packs, and ships — an order with three suppliers is three
jobs, and nobody ever ships "an order". Its lines come from
`production_job_items`, written in the same transaction as the job itself, never
from today's catalog. What the job is prepared *into* depends on its
**fulfillment channel**: an immutable PDF for an SFTP job, a remote order at the
print-on-demand partner for a SPOD job.
_Avoid_: production request (that is the one durable row per order that triggers
the split), production delivery (that is the push of a finished PDF to one SFTP
destination), order (a job is a part of one)

**Fulfillment channel**:
The way a production job reaches its producer, decided from the supplier's
enabled destination when the split worker creates the job and then frozen on the
job (`production_jobs.fulfillment_channel`). `SFTP` renders one immutable PDF and
pushes it to every enabled SFTP destination; `SPOD` renders no PDF at all and
instead uploads the print images and creates, confirms, and follows one order at
the print-on-demand partner. Both channels meet again in `prepared_at` — the
channel-neutral "this job may now be shipped" mark — and in the ship transaction.
_Avoid_: supplier type, provider (a supplier may have destinations of one channel
only, but the channel is a property of the job, not of the supplier)

**Production delivery**:
One machine-to-machine handover of a finished job to its producer: the file
transfer of the production PDF to one enabled SFTP destination on an SFTP job,
the submission of the remote order on a SPOD job. It has nothing to do with the
parcel that reaches the customer: an SFTP delivery ends when the supplier's
server accepted the file, a SPOD submission when the partner confirmed the order.
Only the SFTP channel keeps `production_deliveries` rows — one per enabled
destination; the SPOD channel records its submission in `production_spod_orders`.
_Avoid_: delivery for the parcel to the customer — that is a **shipment**

**Shipped / als versendet markieren**:
The state a **production job** enters when the package is reported to have left
the workshop, optionally with a carrier from the fixed list and a tracking
number. Who reports it depends on the job's **fulfillment channel**: a human —
the supplier, or an admin on its behalf — or the configured provider's webhook,
which goes through the very same guarded transaction; `shipped_by_channel` says
which of the two it was (`shipped_by_user_id` stays `NULL` for a channel report).
Tracking links are always built by the shop from its bounded carrier list, never
taken from what a provider sent. It is per job, never per order, and it is final:
there is no un-ship endpoint, because the shipping notification to the customer
leaves the same transaction and a sent mail cannot be taken back. A job can only
be shipped once it is prepared (`prepared_at`). "The order is fully shipped" is a
*derived* statement — every job of that order is shipped — that no column, API
field, or mail claims; the shipping mail deliberately says that one package is
on its way, never that the order is complete.
_Avoid_: delivered (nothing in this shop observes that a parcel arrived),
un-ship, ship an order

**Supplier login**:
A `users` row that acts for exactly one supplier: role `SUPPLIER`, `supplier_id`
pointing at that supplier, created by an admin and invited by mail. It is an
account, while a **supplier** is master data — a supplier may have several
logins or none, and a login belongs to one supplier for life. Revocation is the
hard delete of that row, and it takes effect on the very next request, because
every supplier route re-asks `SupplierAccounts.supplierIdOf(userId)` instead of
trusting the role its session cookie froze at login time. It is a full shop
account and passes every route that only asks for an authenticated user; its
extra privilege is the `/api/supplier` subtree and nothing else.
_Avoid_: supplier account, supplier user (a supplier is not an account and has
no login of its own)

**Shipping notification / Versandbenachrichtigung**:
The mail the customer receives when a production job is reported shipped: one
per job, enqueued inside the ship transaction, supplier-neutral (the customer
never learns which workshop packed the box), listing the shipped article,
variant, and quantity without any price, plus the tracking link when the carrier
allows one and the permanent order link.
_Avoid_: order confirmation (that mail is sent once, at placement), producer
notification (that one goes to the workshop after a successful SFTP delivery)

**Promotion reservation**:
The in-flight half of a coupon's usage limit: while a checkout runs, the
capacity it is about to spend is held in `promotion_reservations`, keyed on the
cart. It is counted next to the recorded redemptions, and it ends only when the
payment redeems it, when the order is cancelled, or when that payment ends —
never by itself, because a reservation has no expiry.
_Avoid_: redemption (a redemption is recorded and permanent; a reservation is
in flight and given back)
