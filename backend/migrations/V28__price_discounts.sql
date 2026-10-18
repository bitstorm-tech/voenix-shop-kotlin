ALTER TABLE prices
    ADD COLUMN discount_type text NULL,
    ADD COLUMN discount_value numeric(12, 2) NULL,
    ADD CONSTRAINT ck_prices_discount_pair
        CHECK ((discount_type IS NULL) = (discount_value IS NULL)),
    ADD CONSTRAINT ck_prices_discount_type
        CHECK (discount_type IN ('PERCENTAGE', 'FIXED_AMOUNT')),
    ADD CONSTRAINT ck_prices_discount_value_positive
        CHECK (discount_value > 0),
    ADD CONSTRAINT ck_prices_discount_percentage_max
        CHECK (discount_type <> 'PERCENTAGE' OR discount_value <= 100),
    ADD CONSTRAINT ck_prices_discount_fixed_whole_cents
        CHECK (discount_type <> 'FIXED_AMOUNT' OR discount_value = trunc(discount_value));
