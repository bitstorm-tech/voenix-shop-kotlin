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
`print_images`, stored privately as WebP, and delivered only to its owner.
The legacy name `generated_edited_images` is
retired: the image is neither always generated nor always edited (decision by
Joe, 2026-07-29).
_Avoid_: generated edited image, guest image

**Article identity**:
The type-independent registration of an article (and its variants) that gives
carts and orders one foreign-key target across per-type tables. Carries no
business data.

**Checkout**:
The flow that turns a filled cart into a placed order and, unless the order is
free, into a payment the customer is sent to. It is a journey, never a thing
that is stored: the rows it leaves behind belong to the cart, the promotion, the
order, and the payment.
_Avoid_: checkout as a synonym for the placed order

**Promotion reservation**:
The in-flight half of a coupon's usage limit: while a checkout runs, the
capacity it is about to spend is held in `promotion_reservations`, keyed on the
cart. It is counted next to the recorded redemptions, and it ends only when the
payment redeems it, when the order is cancelled, or when that payment ends —
never by itself, because a reservation has no expiry.
_Avoid_: redemption (a redemption is recorded and permanent; a reservation is
in flight and given back)
