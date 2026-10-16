-- The t-shirt slice of the article schema (issue #205, T03).
--
-- It is `V13__create_articles.sql` applied a second time: the same identity
-- registries, the same constant `article_type` column, the same deferrable position
-- rule, the same "an active article is a complete article" CHECK. A t-shirt is a new
-- table, not new nullable columns in `article_mugs` — that is the one-table-per-type
-- idea the article schema was built on, and this migration is the first time it is
-- used.
--
-- Two things are genuinely new, and both come from the fulfillment channel a shirt is
-- produced through (docs/adr/0002-production-fulfillment-channels.md):
--
-- 1. A shirt is printed by SPOD, so a variant carries the three ids SPOD identifies a
--    printable product by (product type, appearance, size). They are `NOT NULL`,
--    because a shirt variant that cannot be ordered from the printer is not a shirt
--    variant. The unique rule over the triple keeps one article from offering the
--    same SPOD product twice under two names.
-- 2. A shirt has a print frame: the rectangle of the mockup image the generated
--    design is placed in, in percent of the mockup. A mug expresses the same thing in
--    millimetres because its print is laid out into a PDF; a shirt's print is placed
--    by SPOD and the frame only drives the preview, so percent is its natural unit.
--
-- A shirt variant carries no `name` column on purpose. Its name is composed from the
-- colour and the size (`"Black / M"`) in exactly one place in Kotlin, so the admin
-- list, the storefront, the catalog capability, and an order line cannot spell it
-- three different ways.

-- The second known article type. It is the foreign-key target of the identity
-- registry and the ordering lock anchor of the shirt positions: a transaction that
-- writes `article_tshirts.position` first locks this row, exactly as a mug write
-- locks the `MUG` row.
INSERT INTO article_types (article_type) VALUES ('TSHIRT');

CREATE TABLE article_tshirts (
    id bigint NOT NULL,
    article_type text NOT NULL DEFAULT 'TSHIRT',
    position integer NOT NULL,
    name character varying(255) NOT NULL,
    description_short character varying(1000) NOT NULL,
    description_long character varying(5000) NOT NULL,
    active boolean NOT NULL,
    category_id bigint NULL,
    subcategory_id bigint NULL,
    supplier_id bigint NULL,
    price_id bigint NULL,
    print_aspect_ratio text NOT NULL DEFAULT '1:1',
    size_chart_image_filename character varying(255) NULL,
    print_frame_left_pct numeric(5, 2) NOT NULL,
    print_frame_top_pct numeric(5, 2) NOT NULL,
    print_frame_width_pct numeric(5, 2) NOT NULL,
    print_frame_height_pct numeric(5, 2) NOT NULL,
    CONSTRAINT pk_article_tshirts PRIMARY KEY (id),
    CONSTRAINT ck_article_tshirts_article_type CHECK (article_type = 'TSHIRT'),
    CONSTRAINT fk_article_tshirts_identity
        FOREIGN KEY (id, article_type)
        REFERENCES article_identities (id, article_type)
        ON DELETE CASCADE,
    CONSTRAINT fk_article_tshirts_category
        FOREIGN KEY (category_id)
        REFERENCES article_categories (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_article_tshirts_subcategory
        FOREIGN KEY (subcategory_id, category_id)
        REFERENCES article_subcategories (id, category_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_article_tshirts_supplier
        FOREIGN KEY (supplier_id)
        REFERENCES suppliers (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_article_tshirts_price
        FOREIGN KEY (price_id)
        REFERENCES prices (id)
        ON DELETE RESTRICT,
    -- A price belongs to exactly one article, across every article type: the price
    -- ids of mugs and shirts come from the same sequence, so no shirt can adopt the
    -- price row of a mug either.
    CONSTRAINT ux_article_tshirts_price_id UNIQUE (price_id),
    CONSTRAINT ck_article_tshirts_position_positive CHECK (position > 0),
    CONSTRAINT ux_article_tshirts_position UNIQUE (position)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_article_tshirts_subcategory_requires_category
        CHECK (subcategory_id IS NULL OR category_id IS NOT NULL),
    -- The pair `shop.voenix.article.PrintAspectRatio` knows. A shirt is printed
    -- square by default, which is the one difference to the mug column.
    CONSTRAINT ck_article_tshirts_print_aspect_ratio
        CHECK (print_aspect_ratio IN ('16:9', '1:1')),
    -- The print frame is a rectangle inside the mockup, so no edge may be negative
    -- and neither edge may leave the image.
    CONSTRAINT ck_article_tshirts_print_frame_non_negative CHECK (
        print_frame_left_pct >= 0
        AND print_frame_top_pct >= 0
        AND print_frame_width_pct >= 0
        AND print_frame_height_pct >= 0
    ),
    CONSTRAINT ck_article_tshirts_print_frame_within_mockup CHECK (
        print_frame_left_pct + print_frame_width_pct <= 100
        AND print_frame_top_pct + print_frame_height_pct <= 100
    ),
    -- A visible article is a complete one, the same rule mugs follow. A shirt has no
    -- detail block to require: its frame columns are `NOT NULL` for every row, so
    -- price and category are the whole rule.
    CONSTRAINT ck_article_tshirts_active_requires_price_category CHECK (
        NOT active
        OR (price_id IS NOT NULL AND category_id IS NOT NULL)
    )
);

CREATE INDEX ix_article_tshirts_category_id ON article_tshirts (category_id);
CREATE INDEX ix_article_tshirts_subcategory_id
    ON article_tshirts (subcategory_id, category_id);
CREATE INDEX ix_article_tshirts_supplier_id ON article_tshirts (supplier_id);

CREATE TABLE article_tshirt_variants (
    id bigint NOT NULL,
    article_id bigint NOT NULL,
    color_name character varying(64) NOT NULL,
    color_hex character varying(7) NOT NULL,
    size_label character varying(64) NOT NULL,
    spod_product_type_id bigint NOT NULL,
    spod_appearance_id bigint NOT NULL,
    spod_size_id bigint NOT NULL,
    is_default boolean NOT NULL,
    active boolean NOT NULL,
    example_image_filename character varying(255) NULL,
    CONSTRAINT pk_article_tshirt_variants PRIMARY KEY (id),
    CONSTRAINT fk_article_tshirt_variants_identity
        FOREIGN KEY (id, article_id)
        REFERENCES article_variant_identities (id, article_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_article_tshirt_variants_article
        FOREIGN KEY (article_id)
        REFERENCES article_tshirts (id)
        ON DELETE CASCADE,
    CONSTRAINT ck_article_tshirt_variants_spod_ids_positive CHECK (
        spod_product_type_id > 0
        AND spod_appearance_id > 0
        AND spod_size_id > 0
    ),
    -- The colour and the size are what a customer picks, and together they name the
    -- variant, so one article cannot offer the same pair twice.
    CONSTRAINT ux_article_tshirt_variants_color_size
        UNIQUE (article_id, color_name, size_label),
    -- The other side of the same rule, seen from the printer: two variants of one
    -- article that resolve to the same SPOD product would be the same thing sold
    -- under two names.
    CONSTRAINT ux_article_tshirt_variants_spod_product
        UNIQUE (article_id, spod_product_type_id, spod_appearance_id, spod_size_id)
);

CREATE INDEX ix_article_tshirt_variants_article_id
    ON article_tshirt_variants (article_id);

-- At most one default variant per article, exactly as for mugs. The other half of
-- the rule — an article with variants has at least one default — is a cross-row
-- invariant of the application write path.
CREATE UNIQUE INDEX ux_article_tshirt_variants_default
    ON article_tshirt_variants (article_id)
    WHERE is_default;
