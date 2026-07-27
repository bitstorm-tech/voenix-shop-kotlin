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

The Article migration is running
([`article-migration.md`](article-migration.md), council plan approved by Joe
on 2026-07-27). Its decisions override the wording this list was written with,
so every item below records what the approved plan does instead.

- [x] Export the transaction-composable capability. `PriceCatalog` (ticket T1)
  offers `prepare` for the suspending validate-resolve-calculate step and the
  non-suspending `storeInTransaction`, `replaceInTransaction`, and
  `deleteInTransaction`, which join the transaction their caller already
  opened. A PostgreSQL rollback test proves that a rolled-back caller leaves no
  price row behind. The price value types are public for the same reason; the
  table, repository, service, and routes stay internal.
- [x] Compose those writes into the Article transaction, so a failed Article or
  Price write leaves neither side partially changed (owner: Article ticket T5).
  `ArticleMugRepository` takes `PriceCatalog`, opens one transaction, and lets
  `storeInTransaction` / `replaceInTransaction` / `deleteInTransaction` join it.
  `prepare` runs before the transaction opens, so a rejected price never reaches
  the article at all.
- [x] Add the price relationship with the Article schema (owner: Article ticket
  T3, which created the whole article schema in `V13__create_articles.sql`).
  `price_id` sits on `article_mugs`, not on a shared `articles` table, with
  `ON DELETE RESTRICT` and `UNIQUE(price_id)` instead of the source's
  `ON DELETE SET NULL`. There is no price delete endpoint.
- [x] Decide whether a Price is exclusively owned by one Article. Decided by
  Joe on 2026-07-27 (article decision K2): ownership holds by construction. No
  Article contract accepts a `priceId`, ids are only minted by
  `storeInTransaction`, an update rewrites the same price row in place, and
  `UNIQUE(price_id)` backstops it. A `prices.owner_kind` discriminator was
  rejected, so `prices` needs no schema change for Article.
- [x] Admin Article contract: the request embeds a `PriceInput` and the
  response embeds the complete `CalculatedPrice`. The separate nullable
  `priceId` field next to the embedded price was dropped by Joe on 2026-07-27;
  `price.id` carries it (owner: Article ticket T5). Two tests keep it that way:
  the write contract has no `priceId` element, and a body that sends one anyway
  is ignored instead of honored.
- [ ] Public Mug list contract: expose the calculated gross sales price in
  integer cents. The `0` fallback for an Article without a Price disappears,
  because an active Article now requires a Price (approved deviation, owner:
  Article ticket T8, the public storefront endpoints).
- [x] Decide what a missing embedded price means on update. Confirmed against
  the source (`AdminArticleService.UpdateAsync` only touches the price when the
  request carries one): an omitted `price` keeps the stored row, and a submitted
  one is written over that same row, so the price id never churns. No deviation
  (owner: Article ticket T5).
- [x] Preserve the current delete lifecycle unless product requirements change:
  deleting an Article also deletes its associated Price in the same
  transaction, now through `deleteInTransaction` (owner: Article ticket T5). The
  article is removed first, because the price reference is `ON DELETE RESTRICT`.
- [ ] Add PostgreSQL integration tests for Article creation with a Price,
  updating an existing Price, rollback on invalid VAT or invalid calculated
  totals, and Article deletion with Price cleanup (all done in
  `MugArticleAdminIntegrationTest`, ticket T5). What is left is the public Mug
  price projection (owner: Article ticket T8).

- [x] Admin read contract: the detail route embeds the same `CalculatedPrice`
  the writes answer with, resolved through one `PriceCatalog.find` call — the
  batched read the capability was designed for — and recalculated from the
  current VAT entries, so a write and a later read of the same article agree.
  The admin list carries no price at all, because the overview table shows none
  (owner: Article ticket T6).

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
  reaching into Pricing persistence details. `PriceCatalog.find(ids)` is that
  seam: it resolves a whole cart in one price query and one VAT query.
- [ ] Preserve the source eligibility rules deliberately: Article lookup uses
  the linked price, while Prompt lookup additionally requires the Prompt to be
  active and not archived.
- [ ] Continue storing integer-cent snapshots in cart items. A later VAT or Price
  edit must not silently rewrite amounts already captured in the cart.
- [ ] Add integration tests proving the selected gross sales total is used for
  Article and Prompt items and defining the behavior when no price is linked.
