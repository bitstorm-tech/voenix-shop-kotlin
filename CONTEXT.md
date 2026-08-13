# Voenix Shop

An e-commerce shop for personalized print products (currently mugs), being
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
The kind of product an article is (today: mug). Each type owns its own table
and admin routes; the category structure is shared across all types.

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
involved supplier, with the immutable PDF that was rendered for it and the item
snapshot stored next to it. It is the unit a supplier sees, prints, packs, and
ships — an order with three suppliers is three jobs, and nobody ever ships "an
order". Its lines come from `production_job_items`, written in the same
transaction as the PDF's digest, never from today's catalog.
_Avoid_: production request (that is the one durable row per order that triggers
the split), production delivery (that is the push of a finished PDF to one SFTP
destination), order (a job is a part of one)

**Production delivery**:
The transport of a finished production PDF to one enabled SFTP destination of a
supplier. It is machine-to-machine and has nothing to do with the parcel that
reaches the customer: a delivery ends when the supplier's server accepted the
file.
_Avoid_: delivery for the parcel to the customer — that is a **shipment**, and
it is reported by a human

**Shipped / als versendet markieren**:
The state a **production job** enters when the supplier — or an admin on its
behalf — reports that the package left the workshop, optionally with a carrier
from the fixed list and a tracking number. It is per job, never per order, and
it is final: there is no un-ship endpoint, because the shipping notification to
the customer leaves the same transaction and a sent mail cannot be taken back.
A job can only be shipped once its PDF exists. "The order is fully shipped" is a
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
trusting the role its session cookie froze at login time.
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
