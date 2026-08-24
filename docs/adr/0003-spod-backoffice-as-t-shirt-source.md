# ADR 0003 — The Spreadconnect backoffice is the source of t-shirt master data

- Status: accepted
- Date: 2026-08-24
- Deciders: three-model council (Claude orchestrator, Opus, Codex); contested
  points decided by Joe. Driving issue: #224.

## Context

A t-shirt exists twice: as an article in the Spreadconnect (SPOD) backoffice,
where the garment, its colours, and its sizes are chosen, and as an
`article_tshirts` row in this shop, where an operator retyped the partner's
product type id, appearance id, and size id into every colour × size variant
and re-uploaded the partner's mockup images. Double bookkeeping, and the main
way to produce a wrong mapping.

The following was verified against the official SPOD API docs
(https://api.spreadconnect.app/docs/):

- `GET /articles` (paged, `limit`/`offset`/`count`) lists the merchant's
  backoffice articles with, per variant, `productTypeId`, `appearanceId`,
  `sizeId`, `sku`, `appearanceColorValue`, and per article `images[]` with
  `appearanceId`, `perspective`, `imageUrl`.
- `GET /productTypes/{id}/size-chart` answers a `sizeImageUrl`.
- The API has **no** print-area geometry relative to a mockup: `printAreas`
  are millimetres of the garment, `views` carry mockup images without
  coordinates. A print frame cannot be computed from the API.
- The formats of `appearanceColorValue` and `perspective` are undocumented.

## Decisions

### 1. One SPOD article = one shop t-shirt article, synced, never typed

The backoffice is the source of truth for the garment part of a shirt. The
shop's admin never creates a t-shirt; a manual, per-destination
*Sync from Spreadconnect* reads `GET /articles` and upserts the catalog.
Manual creation, the variant matrix editor, and the example-image and
size-chart upload routes are removed. No scheduler and no `Article.*`
webhook subscription — the operator edits in the backoffice and presses the
button.

### 2. Two owners per article

SPOD-owned, overwritten on every sync, read-only in the admin: name,
descriptions (short is the same text truncated — no third "seeded once"
ownership), variants (colour name and hex, size, the three ids, sku),
the example image per colour, the size-chart image, and variant `active`
(present with usable image and colour → active; missing → inactive).
Shop-owned, never touched by the sync after creation: article `active`,
category and subcategory, display position, price with its VAT choices,
print frame, print aspect ratio, default variant. The partner's `b2bPrice`
and `d2cPrice` are not stored (Joe, D3). The sync never activates an
article; disappearance deactivates it and sets a visible missing marker;
reappearance clears the marker without reactivating.

### 3. Orders stay `oneTimeItems`; SKU ordering is forbidden

The backoffice article carries a fixed design; ours is generated per
customer. The synced article is a template whose design is a placeholder.
Ordering by `sku` would print the partner's stock design instead of the
customer's image — production keeps the ADR 0002 §4 protocol (upload design,
create `NEW` with `oneTimeItems`, confirm) unchanged.

### 4. Identity is (destination, environment, SPOD article id)

Sync scope is one production destination; its token decides what exists.
`spod_environment` is part of the unique key because the same destination
row is switched from STAGING to PRODUCTION, and the two installations'
article ids are unrelated — without the column, production article *n*
would silently merge into staging article *n* and inherit its shop-owned
data. Variants are matched by the product triple
(`productTypeId`, `appearanceId`, `sizeId`) — the key production orders by —
never by the partner's variant id. Articles and variants are deactivated on
disappearance, never deleted; the admin `DELETE` route stays as the manual
retirement of a shirt that will not come back (Joe, D4). A destination with
synced shirts can be disabled but not deleted (FK RESTRICT), its supplier is
immutable after creation, and a disabled destination may still sync
(Joe, D5) — only the channel is checked.

### 5. The client is shared, the modules stay layered

`SpodClient` moves into a leaf compile module `spod` (client, environment,
`SpodAccess`, result/error vocabulary, order and catalog DTOs). `article`
owns the sync service and exposes it as `TshirtCatalogSync`; `production`
owns the trigger route on the destination and gains the one new acyclic
edge `production → article`. One client instance per application keeps the
partner's 60 req/min budget under a single pacer. The catalog image
download is the documented exception to "base URL from the enum": the URL
comes from the partner's authenticated answer and is bounded instead —
https only, no redirects, no token, `image/*` only, 10 MiB, and unpaced
(CDN, not API).

### 6. Undocumented formats degrade, they never fail the run

An unparseable `appearanceColorValue` and a colour without a front-view
image both make the variant inactive with a bounded warning code instead of
failing the article or inventing data the customer would order by. A
listing failure writes nothing and never deactivates; only a complete
listing may sweep.

### 7. The print frame stays manual

Recorded so the spike is not reopened: the API offers no mockup-relative
geometry (see Context), so the frame calibrator stays and a new synced
shirt starts from the editor's default frame.

### 8. The name tripwire keeps firing on partner renames — accepted

ADR 0002 §8 compares the live composed variant name against the order
line's snapshot. The sync makes the partner a second author of that name,
so a backoffice rename quarantines in-flight jobs of that colour as a false
alarm. Joe decided (D1) to accept this operationally — the alert is
visible and resolved by hand — and to fix it in a follow-up that snapshots
the three SPOD ids on `production_job_items` and compares ids instead of
names (issue #225).

## Consequences

- `article_tshirts` gains `spod_destination_id`, `spod_environment`,
  `spod_article_id`, `spod_synced_at`, `spod_missing_since`,
  `spod_size_chart_url`; `supplier_id` becomes `NOT NULL`;
  `article_tshirt_variants` gain `spod_variant_id`, `sku`, `spod_image_id`
  and lose the colour/size unique rule. The migration deletes existing
  (hand-entered) t-shirt rows; there is no production data.
- The admin t-shirt API shrinks to list, read, shop-owned update, reorder,
  and delete; every sync run answers a diff report with bounded warning
  codes, and nothing the partner writes reaches a log line.
- The storefront read path is unchanged.
- An operator switches a single colour or size off in the backoffice, not
  in the admin.
