# Pricing post-migration to-do list

This list owns Pricing work that must wait for modules which consume prices.
The standalone Pricing migration is defined in
[`pricing-migration.md`](pricing-migration.md).

Do not create placeholder consumer tables or weaken Pricing boundaries merely
to complete these items early. Update this file as each owning module is
migrated.

## Admin Pricing UI

- [ ] Limit the editable `purchaseCostPercent` and `salesMarginPercent` fields
  to at most two relevant decimal places and four integer digits. Reject values
  outside the backend range with an inline validation message instead of
  silently rounding them, and cover the behavior with frontend tests.

## Article relationship and lifecycle

- [ ] When Article is migrated, add nullable `articles.price_id`, index it, and
  add the foreign key to `prices.id`. The source uses `ON DELETE SET NULL` when
  a price is deleted.
- [ ] Preserve the admin Article contract: create and update requests may embed
  a Pricing input; responses contain both nullable `priceId` and the complete
  calculated Pricing response.
- [ ] Preserve the public Mug list contract: expose the calculated gross sales
  price in integer cents for a linked Price and `0` when no Price is linked.
- [ ] Compose Pricing's transaction-composable create/update operations into
  the Article transaction so creating or updating an Article and its Price is
  atomic. A failed Article or Price write must leave neither side partially
  changed.
- [ ] Preserve the current update meaning unless an intentional deviation is
  approved: a missing embedded price leaves an existing Article price
  unchanged; it does not remove it.
- [ ] Preserve the current delete lifecycle unless product requirements change:
  deleting an Article also deletes its associated Price in the same
  transaction.
- [ ] Add PostgreSQL integration tests for Article creation with a Price,
  updating an existing Price, adding a Price to an Article that did not have
  one, rollback on invalid VAT or invalid calculated totals, and Article
  deletion with Price cleanup. Cover the public Mug price projection and its
  no-Price fallback as well.
- [ ] Decide and document whether a Price is exclusively owned by one Article.
  The source schema does not enforce exclusive ownership, although Article
  deletion treats the Price as owned. Add an invariant before relying on that
  lifecycle in Kotlin.

## Prompt relationship and projection

- [ ] When Prompt is migrated, add nullable `prompts.price_id`, index it, and add
  the foreign key to `prices.id` with the approved deletion behavior. The
  source relationship does not cascade deletion.
- [ ] Reuse the Pricing calculator for Prompt output. The Prompt contract exposes
  only sales-total net, gross, and tax cents plus the sales VAT percentage; it
  does not expose the complete admin Pricing response.
- [ ] Keep Prompt's smaller public projection in the Prompt package unless a
  second consumer establishes it as a shared domain concept.
- [ ] Add integration tests for prompts with and without a Price and for behavior
  when the referenced VAT changes.
- [ ] Establish how Prompt prices are created and assigned. The current source
  has a nullable relationship and read projection but no price field in the
  Prompt update request.

## Cart and shop price consumption

- [ ] When Cart is migrated, read current Article and Prompt prices through a
  Pricing-owned calculation seam instead of duplicating calculation formulas or
  reaching into Pricing persistence details.
- [ ] Preserve the source eligibility rules deliberately: Article lookup uses
  the linked price, while Prompt lookup additionally requires the Prompt to be
  active and not archived.
- [ ] Continue storing integer-cent snapshots in cart items. A later VAT or Price
  edit must not silently rewrite amounts already captured in the cart.
- [ ] Add integration tests proving the selected gross sales total is used for
  Article and Prompt items and defining the behavior when no price is linked.
