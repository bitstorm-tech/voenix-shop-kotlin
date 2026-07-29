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

## Article relationship and lifecycle — closed on 2026-07-28

The Article migration is implemented
([`article-migration.md`](article-migration.md), council plan approved by Joe
on 2026-07-27). Its decisions override the wording this list was written with,
so every item below records what the approved plan does instead. Every item is
done; nothing about the price relationship waits for another module. The one
frontend consequence — the admin article contract no longer has a `priceId`
field next to the embedded price — is listed in
[`article-post-migration.md`](article-post-migration.md).

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
- [x] Public Mug list contract: expose the calculated gross sales price in
  integer cents. `GET /api/articles/mugs` answers `price` as the gross sales
  total of one batched `PriceCatalog.find` for the whole page (owner: Article
  ticket T8). The `0` fallback is gone, and not only from the contract: the
  storefront representation declares `price` non-nullable, because an active
  Article requires a Price and only active Articles reach that list (approved
  deviation).
- [x] Decide what a missing embedded price means on update. Confirmed against
  the source (`AdminArticleService.UpdateAsync` only touches the price when the
  request carries one): an omitted `price` keeps the stored row, and a submitted
  one is written over that same row, so the price id never churns. No deviation
  (owner: Article ticket T5).
- [x] Preserve the current delete lifecycle unless product requirements change:
  deleting an Article also deletes its associated Price in the same
  transaction, now through `deleteInTransaction` (owner: Article ticket T5). The
  article is removed first, because the price reference is `ON DELETE RESTRICT`.
- [x] Add PostgreSQL integration tests for Article creation with a Price,
  updating an existing Price, rollback on invalid VAT or invalid calculated
  totals, and Article deletion with Price cleanup (all done in
  `MugArticleAdminIntegrationTest`, ticket T5). The public Mug price projection
  is covered by `PublicMugIntegrationTest` (ticket T8): the gross amount in the
  documented list document, and the single batched lookup that resolves the
  prices of a whole page — none at all for an empty catalog.

- [x] Admin read contract: the detail route embeds the same `CalculatedPrice`
  the writes answer with, resolved through one `PriceCatalog.find` call — the
  batched read the capability was designed for — and recalculated from the
  current VAT entries, so a write and a later read of the same article agree.
  The admin list carries no price at all, because the overview table shows none
  (owner: Article ticket T6).

## Prompt relationship and projection — closed on 2026-07-28

The Prompt migration is implemented
([`prompt-migration.md`](prompt-migration.md), council plan approved by Joe on
2026-07-28). Every item below is done; nothing about the prompt price waits for
another module. The frontend consequences are listed in
[`prompt-post-migration.md`](prompt-post-migration.md).

- [x] Nullable `prompts.price_id` with an index and a foreign key to `prices.id`
  exists since `V14__create_prompts.sql` (slice 1). It is `ON DELETE RESTRICT`
  plus `UNIQUE (price_id)` — the article precedent (deviation D11) — so a price
  cannot be taken away from the prompt that holds it and cannot be shared with a
  second one. There is no price delete endpoint; a prompt is never deleted at
  all, only archived.
- [x] The Pricing calculator is reused through `PriceCatalog`. `prepare` runs
  before the prompt's transaction, `storeInTransaction` and
  `replaceInTransaction` join it, so a rejected price never creates a prompt and
  a failed prompt write never leaves a price row behind (slice 3a).
- [x] The small projection `PromptPrice {salesTotalNet, salesTotalGross,
  salesTotalTax, salesVatRatePercent}` stayed `internal` to the prompt module and
  is what the admin list rows and the storefront list carry; the admin detail
  answers the complete `CalculatedPrice`. The exported `PromptCatalog` does not
  export the projection either — Cart gets the gross amount in integer cents
  (rebuttal outcome R2), so a second consumer did not turn it into a shared type.
- [x] Integration tests cover a prompt with and without a linked price
  (`PromptAdminIntegrationTest` for the null-price repair, `PublicPromptIntegrationTest`
  for the `null` price that is never a `0`, `PromptCatalogIntegrationTest` for the
  absent id that is never a `0`) and the VAT change recalculated into every one
  of the three projections.
- [x] Prompt prices are created and assigned by the admin write itself: create
  always mints one, update rewrites the same row in place, a submitted `priceId`
  is ignored, and a missing request price is a `400` (brainstorming decision 4).
  An update of a prompt whose stored `price_id` is `null` creates and links one
  instead of failing (deviation D10).

## Cart and shop price consumption

- [ ] When Cart is migrated, read current Article and Prompt prices through a
  Pricing-owned calculation seam instead of duplicating calculation formulas or
  reaching into Pricing persistence details. `PriceCatalog.find(ids)` is that
  seam: it resolves a whole cart in one price query and one VAT query.
- [ ] Preserve the source eligibility rules deliberately: Article lookup uses
  the linked price, while Prompt lookup additionally requires the Prompt to be
  active and not archived. Both rules are already implemented on the owning side,
  so Cart consumes them instead of restating them:
  `ArticleCatalog.find(references)` reports `purchasable`, and
  `PromptCatalog.findSalesGrossPriceCents(promptIds)` simply leaves an
  ineligible prompt out of its answer. Neither ever answers `0` for "cannot be
  bought", so Cart must treat an absent id — not a zero amount — as the
  unavailable case.
- [ ] Continue storing integer-cent snapshots in cart items. A later VAT or Price
  edit must not silently rewrite amounts already captured in the cart.
- [ ] Add integration tests proving the selected gross sales total is used for
  Article and Prompt items and defining the behavior when no price is linked.
