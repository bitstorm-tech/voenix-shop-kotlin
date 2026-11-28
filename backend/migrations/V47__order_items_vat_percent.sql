-- The sales VAT rate of each ordered line, snapshotted at placement for the customer invoice.
-- The prompt price on the line is an ancillary service and takes the article's rate. Earlier
-- lines are backfilled with the standard rate of 19 %.
ALTER TABLE order_items ADD COLUMN vat_percent integer NOT NULL DEFAULT 19
    CONSTRAINT ck_order_items_vat_percent_range CHECK (vat_percent >= 0 AND vat_percent <= 100);
ALTER TABLE order_items ALTER COLUMN vat_percent DROP DEFAULT;
