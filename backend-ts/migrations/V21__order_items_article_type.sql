-- The article type of an ordered line (issue #205, T06).
--
-- An order line already snapshots everything a later reader needs — the names, the
-- prices, the supplier article number, the five print measurements. The type of the
-- article it was placed for was missing from that list, because there was only one
-- type. With t-shirts there are two, and they are produced through different
-- channels: a mug is laid out into a PDF, a shirt is ordered from a print-on-demand
-- partner. Which of the two an order line is, must therefore be readable from the
-- line itself — long after the article it names has been edited or deleted.
--
-- It is a snapshot like every column around it and carries no foreign key at all, for
-- the same reason `article_id` and `variant_id` carry none: an order must survive the
-- deletion of the article it was placed for. The value is the name of a
-- `shop.voenix.article.ArticleType` constant, exactly as `article_identities` and
-- `article_variant_identities` store it.
--
-- Every existing line is a mug, so the backfill says 'MUG'. The DEFAULT is dropped
-- afterwards on purpose: a placement always knows the type it snapshotted, and a
-- write that says nothing about it is a bug, not a mug.
ALTER TABLE order_items
    ADD COLUMN article_type text NOT NULL DEFAULT 'MUG';

ALTER TABLE order_items
    ALTER COLUMN article_type DROP DEFAULT;
