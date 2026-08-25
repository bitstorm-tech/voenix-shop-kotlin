# ADR 0004 — The struck-through price is the configured regular price

- Status: accepted
- Date: 2026-08-25
- Deciders: three-model council (Claude orchestrator, Opus, Codex); contested
  points decided by Joe (decision E1). Driving issue: #238.

## Context

A Price can carry a **price discount**: a percentage or a fixed number of
cents that reduces its gross sales total (see
[the Pricing package](../dev/backend/packages/pricing-package.md)). The
storefront advertises that reduction — it strikes an amount through, shows the
effective price next to it, and puts a saving badge on the card. The moment a
shop announces a price reduction that way, German law has an opinion about
which amount may be struck through:

- § 11 Preisangabenverordnung (PAngV) requires that a trader who announces a
  price reduction states the **lowest price applied within the previous 30
  days** — not simply the price that was configured yesterday.
- This shop stores no price history at all. A `prices` row holds the current
  calculation inputs, and an update overwrites them; nothing records that an
  article cost 24,90 € three weeks ago.
- Prices are not even stable snapshots of themselves: net, tax, and gross are
  derived on every read from the VAT entries a Price references, so changing a
  VAT percentage silently changes what an older "price" would have been. A
  history built from today's rows would be a reconstruction, not a record.

So the shop cannot compute the lawful reference price, and building the
machinery that could — a price-history table written on every price change,
plus the rule that reads it — is a feature of its own, not a detail of the
discount field.

## Decision

### 1. Version 1 strikes through the configured regular price

`regularSalesTotal` — the sales total before the discount, exactly as the
operator configured it — is what the storefront shows crossed out. The backend
does not look for a lower price in the past, because it has no past to look
in.

### 2. The 30-day rule stays with the operator

The operator decides whether a discount is lawful to advertise, in the same
way they already decide whether the price itself is right. The admin discount
card carries that hint in plain words, so the person configuring a discount
reads it at the moment they configure one, not in a document.

### 3. No price-history table in this feature

Nothing is written on a price change, and no column, API field, or admin view
claims to know a 30-day low. Half a history — one that starts today and is
blind to every earlier change, or one reconstructed from rows that VAT changes
already moved — would be worse than none, because a wrong reference price is
the exact failure the rule is about.

## Consequences

- The shop **does not enforce** § 11 PAngV. A discount configured right after
  a price increase advertises a reference price that the rule would not allow,
  and neither validation nor the admin UI stops it.
- This is acceptable while the shop is operated by one owner who sets the
  prices personally and can be told the rule once. It stops being acceptable
  as soon as discounts are used at scale, on many articles, or by more than
  one operator.
- Issue **#239** (automated price history and a derived 30-day reference
  price) is therefore the **prerequisite** for that step, and it is a
  prerequisite rather than a nice-to-have: it has to be built before the
  discount is used that way, not after.
- Nothing in the discount data model blocks it. A history table is written
  next to `prices`; the storefront contracts already have exactly one field
  for the crossed-out amount (`regularPrice`, `regularSalesTotalGross`), so
  the day the reference price is derived instead of configured, only the
  producer of that number changes and no consumer does.

## Alternatives rejected

- **Build the price history now.** Rejected as scope: it needs a write on
  every price change, a decision about what a VAT change means for a
  historical amount, a backfill for the rows that already exist, and an admin
  view to explain the number it produces. That is a feature next to the
  discount, and it would have delayed the discount itself, which one operator
  can use lawfully today.
- **Calculate the discount but never highlight it.** Showing only the reduced
  price avoids the announcement, and with it the rule — but it also removes
  the reason for the feature. A silent price cut is what the shop already had:
  edit the sales price. The crossed-out amount and the saving badge *are* the
  requirement.
