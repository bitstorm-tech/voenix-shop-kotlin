-- The t-shirt becomes a synced article (issue #224, ADR 0003).
--
-- Until now an operator typed a shirt into the admin: its name, its texts, and one
-- row per colour × size with the three ids the print-on-demand partner identifies a
-- printable product by. The same shirt already existed in the Spreadconnect
-- backoffice, so the shop kept a second copy of somebody else's master data — and
-- retyping the three ids is the main way to produce a wrong mapping.
--
-- From here the backoffice is the source of truth for the garment
-- (docs/adr/0003-spod-backoffice-as-t-shirt-source.md). A shirt has two owners:
--
-- 1. SPOD owns the garment — name, descriptions, the variant matrix with its colours,
--    sizes, the three ids, the sku, the example images, and the size chart. A sync run
--    overwrites all of it on every pass.
-- 2. The shop owns what the shop decides — `active`, the category path, the display
--    position, the price, the print frame, the print aspect ratio, and which variant is
--    the default one. A sync never touches those.
--
-- The columns below are the identity of the first owner. `spod_environment` is part of
-- the unique key because one destination row is switched from STAGING to PRODUCTION,
-- and the two installations number their articles independently: without the column,
-- production article *n* would silently merge into staging article *n* and inherit its
-- shop-owned data. `spod_destination_id` is `ON DELETE RESTRICT`, so a destination that
-- synced shirts can be disabled but not deleted out from under them.
--
-- Every existing t-shirt was hand-entered, and none of them carries the identity the
-- new columns require, so this migration starts by deleting them. That is allowed
-- because the shop is not in production and holds no real order data (CLAUDE.md); the
-- development database may be rebuilt when a local cart or order refuses the delete.
-- Deleting the identity row is enough: `article_tshirts`, `article_variant_identities`,
-- `article_tshirt_variants`, and `cart_items` all cascade from it, and an order line is
-- a snapshot that references no article row at all. The price rows the deleted shirts
-- owned stay behind as unreferenced master data.
DELETE FROM article_identities WHERE article_type = 'TSHIRT';

ALTER TABLE article_tshirts
    ADD COLUMN spod_destination_id bigint NOT NULL,
    ADD COLUMN spod_environment text NOT NULL,
    ADD COLUMN spod_article_id character varying(64) NOT NULL,
    ADD COLUMN spod_synced_at timestamptz NOT NULL,
    -- Set when a sync run no longer finds the article, cleared when it comes back. A
    -- disappeared shirt is deactivated and marked, never deleted: it may return, and
    -- the shop-owned half of the row would be gone for good.
    ADD COLUMN spod_missing_since timestamptz NULL,
    -- The partner's URL the stored size chart image was downloaded from, so a later run
    -- can tell an unchanged chart from a new one.
    ADD COLUMN spod_size_chart_url character varying(1024) NULL,
    ADD CONSTRAINT fk_article_tshirts_spod_destination
        FOREIGN KEY (spod_destination_id)
        REFERENCES production_destinations (id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT ck_article_tshirts_spod_environment
        CHECK (spod_environment IN ('STAGING', 'PRODUCTION')),
    -- The identity of the synced article, and therefore the key a sync run upserts by.
    ADD CONSTRAINT ux_article_tshirts_spod_article
        UNIQUE (spod_destination_id, spod_environment, spod_article_id);

-- The supplier is no longer optional: a shirt is produced by the supplier the
-- destination it was synced from belongs to, so every shirt has one.
ALTER TABLE article_tshirts
    ALTER COLUMN supplier_id SET NOT NULL;

ALTER TABLE article_tshirt_variants
    -- The partner's own variant id. It is stored because a report reads better with it,
    -- and it is deliberately *not* the match key: a variant is matched by the product
    -- triple below, which is the key production orders by.
    ADD COLUMN spod_variant_id character varying(64) NOT NULL,
    ADD COLUMN sku character varying(128) NULL,
    -- The image of the colour, as the partner names it, so an unchanged picture is not
    -- downloaded twice.
    ADD COLUMN spod_image_id character varying(64) NULL;

-- The colour and the size no longer decide what a variant *is*. The partner may rename
-- a colour, and the renamed variant is the same printable product, so it has to be able
-- to take the place of its predecessor within one sync run. The triple below is the
-- rule that stays: two variants of one article that resolve to the same SPOD product
-- would be the same garment sold twice.
ALTER TABLE article_tshirt_variants
    DROP CONSTRAINT ux_article_tshirt_variants_color_size;
