-- Deferred uniqueness for the two variant rules of a t-shirt (issue #205, phase-3 review).
--
-- Both constraints describe a legal *end state* of a write, not a legal state after every single
-- row of it. The variant array of an update is applied row by row, so swapping the size labels of
-- two existing variants — "M" becomes "L" and "L" becomes "M", a perfectly ordinary correction —
-- makes the first `UPDATE` collide with the row the second one is about to change. The write then
-- fails with a `23505` that names a constraint the service must not read (see
-- `docs/dev/backend/persistence-error-handling.md`), and the client sees a 500 for a request that
-- is entirely valid.
--
-- Deferring the check to `COMMIT` is the same fix `ux_article_tshirts_position` already carries for
-- the same reason: the transaction may pass through states the rule forbids, as long as the state it
-- commits obeys it. Nothing about the rule itself changes — a duplicate that is still there at
-- `COMMIT` still fails, and the input validation of `TshirtArticleInput` catches the duplicates a
-- client actually sends before a row is written at all.
--
-- PostgreSQL cannot make an existing constraint deferrable, so each one is dropped and re-added.
-- That rebuilds its unique index; the table is small master data, so it costs nothing worth naming.

ALTER TABLE article_tshirt_variants
    DROP CONSTRAINT ux_article_tshirt_variants_color_size;

ALTER TABLE article_tshirt_variants
    ADD CONSTRAINT ux_article_tshirt_variants_color_size
        UNIQUE (article_id, color_name, size_label)
        DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE article_tshirt_variants
    DROP CONSTRAINT ux_article_tshirt_variants_spod_product;

ALTER TABLE article_tshirt_variants
    ADD CONSTRAINT ux_article_tshirt_variants_spod_product
        UNIQUE (article_id, spod_product_type_id, spod_appearance_id, spod_size_id)
        DEFERRABLE INITIALLY DEFERRED;
