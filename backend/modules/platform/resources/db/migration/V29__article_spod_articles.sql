-- The t-shirt becomes the generic SPOD article (issue #260, T1).
--
-- The article type used to name a product kind: `TSHIRT`. What actually set a shirt apart from a
-- mug was never the garment, though, but the channel it is produced through — a print-on-demand
-- partner (SPOD) instead of a PDF on an SFTP server. From here the type names that channel:
-- `SPOD`. A t-shirt, a tote bag, or any other product the partner prints one front motif on is
-- the same type; which product it is, is data (`spod_product_type_name`, the category path).
--
-- Four tables replace the two t-shirt tables, because the partner's own model is article →
-- appearance (colour) → size, and the images hang off the colour rather than off every size row:
--
-- 1. `article_spod_articles` — the article, the two owners of `V27` unchanged, plus the product
--    type it is (`spod_product_type_id`, `spod_product_type_name`) and the measured print frame
--    with the SPOD hotspot it belongs to. The frame is no longer typed by an operator: a sync run
--    measures it (issue #260, decisions 6 and 12). Until it has, frame and hotspot are `NULL`, and
--    an article without them cannot be active. `print_aspect_ratio` is gone: a SPOD article is
--    always printed square (decision 10).
-- 2. `article_spod_appearances` — one row per colour, in the partner's order.
-- 3. `article_spod_variants` — one row per colour × size, as before; the colour columns moved to
--    the appearance, the three SPOD ids the order needs stay reachable (product type and size
--    directly, appearance through its parent).
-- 4. `article_spod_images` — the pictures of a colour: every `LISTING` image the partner lists
--    for it, and at most one `MOCKUP`, the blank front image the editor lays the frame on.
--
-- The migration is destructive on purpose, which is allowed while the shop holds no production
-- data (AGENTS.md): every synced t-shirt is dropped and comes back with the next sync run of its
-- destination. Deleting the identity rows cascades into `article_variant_identities` and
-- `cart_items`; an order line references no article row, so its type is simply renamed with the
-- type registry.
DROP TABLE article_tshirt_variants, article_tshirts;

DELETE FROM article_variant_identities WHERE article_type = 'TSHIRT';
DELETE FROM article_identities WHERE article_type = 'TSHIRT';

UPDATE order_items SET article_type = 'SPOD' WHERE article_type = 'TSHIRT';
-- No identity references `TSHIRT` any more, so the restricted foreign key lets the row be renamed.
-- It stays the identity target and the ordering lock anchor of the SPOD article positions.
UPDATE article_types SET article_type = 'SPOD' WHERE article_type = 'TSHIRT';

CREATE TABLE article_spod_articles (
    id bigint NOT NULL,
    article_type text NOT NULL DEFAULT 'SPOD',
    position integer NOT NULL,
    name character varying(255) NOT NULL,
    description_short character varying(1000) NOT NULL,
    description_long character varying(5000) NOT NULL,
    active boolean NOT NULL,
    category_id bigint NULL,
    subcategory_id bigint NULL,
    supplier_id bigint NOT NULL,
    price_id bigint NULL,
    size_chart_image_filename character varying(255) NULL,
    print_frame_left_pct numeric(5, 2) NULL,
    print_frame_top_pct numeric(5, 2) NULL,
    print_frame_width_pct numeric(5, 2) NULL,
    print_frame_height_pct numeric(5, 2) NULL,
    spod_destination_id bigint NOT NULL,
    spod_environment text NOT NULL,
    spod_article_id character varying(64) NOT NULL,
    spod_synced_at timestamptz NOT NULL,
    spod_missing_since timestamptz NULL,
    spod_size_chart_url character varying(1024) NULL,
    spod_product_type_id bigint NOT NULL,
    -- The product kind as the partner names it ("Men's T-Shirt", "Tote Bag"); the storefront
    -- shows it as the subtitle. An empty string when the listing carries none.
    spod_product_type_name character varying(255) NOT NULL,
    -- The SPOD hotspot the measured frame belongs to, e.g. `MEDIUM_FRONT`.
    spod_hotspot character varying(64) NULL,
    CONSTRAINT pk_article_spod_articles PRIMARY KEY (id),
    CONSTRAINT ck_article_spod_articles_article_type CHECK (article_type = 'SPOD'),
    CONSTRAINT fk_article_spod_articles_identity
        FOREIGN KEY (id, article_type)
        REFERENCES article_identities (id, article_type)
        ON DELETE CASCADE,
    CONSTRAINT fk_article_spod_articles_category
        FOREIGN KEY (category_id)
        REFERENCES article_categories (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_article_spod_articles_subcategory
        FOREIGN KEY (subcategory_id, category_id)
        REFERENCES article_subcategories (id, category_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_article_spod_articles_supplier
        FOREIGN KEY (supplier_id)
        REFERENCES suppliers (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_article_spod_articles_price
        FOREIGN KEY (price_id)
        REFERENCES prices (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_article_spod_articles_spod_destination
        FOREIGN KEY (spod_destination_id)
        REFERENCES production_destinations (id)
        ON DELETE RESTRICT,
    -- A price belongs to exactly one article, across every article type.
    CONSTRAINT ux_article_spod_articles_price_id UNIQUE (price_id),
    CONSTRAINT ck_article_spod_articles_position_positive CHECK (position > 0),
    CONSTRAINT ux_article_spod_articles_position UNIQUE (position)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_article_spod_articles_subcategory_requires_category
        CHECK (subcategory_id IS NULL OR category_id IS NOT NULL),
    CONSTRAINT ck_article_spod_articles_spod_environment
        CHECK (spod_environment IN ('STAGING', 'PRODUCTION')),
    CONSTRAINT ck_article_spod_articles_spod_product_type_id_positive
        CHECK (spod_product_type_id > 0),
    -- The identity of the synced article, and therefore the key a sync run upserts by.
    CONSTRAINT ux_article_spod_articles_spod_article
        UNIQUE (spod_destination_id, spod_environment, spod_article_id),
    -- A frame is measured as a whole or not at all, and a measured one is a rectangle inside the
    -- mockup: no negative edge, and no edge that leaves the image. The `IS NOT NULL` half is not
    -- redundant: a comparison with `NULL` is `NULL`, and a CHECK lets `NULL` pass, so without it a
    -- half-set frame would slip through.
    CONSTRAINT ck_article_spod_articles_print_frame CHECK (
        (
            print_frame_left_pct IS NULL
            AND print_frame_top_pct IS NULL
            AND print_frame_width_pct IS NULL
            AND print_frame_height_pct IS NULL
        )
        OR (
            print_frame_left_pct IS NOT NULL
            AND print_frame_top_pct IS NOT NULL
            AND print_frame_width_pct IS NOT NULL
            AND print_frame_height_pct IS NOT NULL
            AND print_frame_left_pct >= 0
            AND print_frame_top_pct >= 0
            AND print_frame_width_pct >= 0
            AND print_frame_height_pct >= 0
            AND print_frame_left_pct + print_frame_width_pct <= 100
            AND print_frame_top_pct + print_frame_height_pct <= 100
        )
    ),
    -- The hotspot is what the frame was measured for, so the two exist together. Checking the
    -- left edge is enough: the CHECK above keeps the other three in step with it.
    CONSTRAINT ck_article_spod_articles_hotspot_with_frame
        CHECK ((spod_hotspot IS NULL) = (print_frame_left_pct IS NULL)),
    -- A visible article is a complete one: a price, a category, and a measured frame with its
    -- hotspot — the storefront cannot preview a motif without the frame.
    CONSTRAINT ck_article_spod_articles_active_requires_price_category_frame CHECK (
        NOT active
        OR (
            price_id IS NOT NULL
            AND category_id IS NOT NULL
            AND print_frame_left_pct IS NOT NULL
            AND spod_hotspot IS NOT NULL
        )
    )
);

CREATE INDEX ix_article_spod_articles_category_id ON article_spod_articles (category_id);
CREATE INDEX ix_article_spod_articles_subcategory_id
    ON article_spod_articles (subcategory_id, category_id);
CREATE INDEX ix_article_spod_articles_supplier_id ON article_spod_articles (supplier_id);

-- One colour of an article. A sync run upserts it by the partner's appearance id and never deletes
-- it: a colour the backoffice switched off keeps its row, so its variants keep their ids.
CREATE TABLE article_spod_appearances (
    id bigint GENERATED BY DEFAULT AS IDENTITY,
    article_id bigint NOT NULL,
    spod_appearance_id bigint NOT NULL,
    color_name character varying(64) NOT NULL,
    color_hex character varying(7) NOT NULL,
    -- The partner's order of the colours: the order of their first occurrence in the listing.
    position integer NOT NULL,
    CONSTRAINT pk_article_spod_appearances PRIMARY KEY (id),
    CONSTRAINT fk_article_spod_appearances_article
        FOREIGN KEY (article_id)
        REFERENCES article_spod_articles (id)
        ON DELETE CASCADE,
    CONSTRAINT ck_article_spod_appearances_spod_appearance_id_positive
        CHECK (spod_appearance_id > 0),
    CONSTRAINT ux_article_spod_appearances_spod_appearance
        UNIQUE (article_id, spod_appearance_id),
    -- Deferred like the variant rules of `V26`: two colours the partner swapped names between are
    -- written one row after the other, and only the committed state has to be legal.
    CONSTRAINT ux_article_spod_appearances_color_name
        UNIQUE (article_id, color_name)
        DEFERRABLE INITIALLY DEFERRED
);

-- One colour × size of an article. Its id is still minted by `article_variant_identities`, which is
-- what cart lines, order lines, and the production tripwire point at.
CREATE TABLE article_spod_variants (
    id bigint NOT NULL,
    article_id bigint NOT NULL,
    appearance_id bigint NOT NULL,
    size_label character varying(64) NOT NULL,
    spod_product_type_id bigint NOT NULL,
    spod_size_id bigint NOT NULL,
    spod_variant_id character varying(64) NOT NULL,
    sku character varying(128) NULL,
    is_default boolean NOT NULL,
    active boolean NOT NULL,
    CONSTRAINT pk_article_spod_variants PRIMARY KEY (id),
    CONSTRAINT fk_article_spod_variants_identity
        FOREIGN KEY (id, article_id)
        REFERENCES article_variant_identities (id, article_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_article_spod_variants_article
        FOREIGN KEY (article_id)
        REFERENCES article_spod_articles (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_article_spod_variants_appearance
        FOREIGN KEY (appearance_id)
        REFERENCES article_spod_appearances (id)
        ON DELETE CASCADE,
    CONSTRAINT ck_article_spod_variants_spod_ids_positive
        CHECK (spod_product_type_id > 0 AND spod_size_id > 0),
    -- One size of one colour is one variant. Deferred for the same reason as the colour name.
    CONSTRAINT ux_article_spod_variants_appearance_size
        UNIQUE (appearance_id, size_label)
        DEFERRABLE INITIALLY DEFERRED,
    -- Two variants of one article that resolve to the same printable product would be the same
    -- product sold twice under two names.
    CONSTRAINT ux_article_spod_variants_spod_product
        UNIQUE (article_id, spod_product_type_id, appearance_id, spod_size_id)
        DEFERRABLE INITIALLY DEFERRED
);

CREATE INDEX ix_article_spod_variants_article_id ON article_spod_variants (article_id);
CREATE INDEX ix_article_spod_variants_appearance_id ON article_spod_variants (appearance_id);

-- At most one default variant per article. The other half of the rule — an article with variants
-- has a default — is a cross-row invariant of the application write path.
CREATE UNIQUE INDEX ux_article_spod_variants_default
    ON article_spod_variants (article_id)
    WHERE is_default;

-- The pictures of one colour. `LISTING` is every image the partner lists for the colour, in its
-- order; `MOCKUP` is the one blank front image the editor lays the measured frame on.
CREATE TABLE article_spod_images (
    id bigint GENERATED BY DEFAULT AS IDENTITY,
    appearance_id bigint NOT NULL,
    role text NOT NULL,
    -- The partner's order of the images.
    position integer NOT NULL,
    -- The partner's image id of a `LISTING` image, so an unchanged picture is not downloaded twice.
    spod_image_id character varying(64) NULL,
    filename character varying(255) NOT NULL,
    -- A `MOCKUP` has no partner id — it is a URL on the partner's image server — so the URL it was
    -- fetched from is stored instead and compared by the next run: the same URL keeps the stored
    -- file, a different one is fetched and replaces it (SPOD sync decision 7).
    source_url character varying(1024) NULL,
    CONSTRAINT pk_article_spod_images PRIMARY KEY (id),
    CONSTRAINT fk_article_spod_images_appearance
        FOREIGN KEY (appearance_id)
        REFERENCES article_spod_appearances (id)
        ON DELETE CASCADE,
    CONSTRAINT ck_article_spod_images_role CHECK (role IN ('LISTING', 'MOCKUP')),
    CONSTRAINT ux_article_spod_images_position UNIQUE (appearance_id, role, position)
);

CREATE UNIQUE INDEX ux_article_spod_images_mockup
    ON article_spod_images (appearance_id)
    WHERE role = 'MOCKUP';
