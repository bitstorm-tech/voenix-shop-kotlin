ALTER TABLE countries
    ADD COLUMN shipping_cost_cents integer NOT NULL DEFAULT 490
        CONSTRAINT ck_countries_shipping_cost_non_negative CHECK (shipping_cost_cents >= 0),
    ADD COLUMN free_shipping_threshold_cents integer NULL DEFAULT 5000
        CONSTRAINT ck_countries_free_shipping_threshold_positive CHECK (
            free_shipping_threshold_cents IS NULL OR free_shipping_threshold_cents > 0
        );

ALTER TABLE countries
    ALTER COLUMN shipping_cost_cents DROP DEFAULT,
    ALTER COLUMN free_shipping_threshold_cents DROP DEFAULT;
