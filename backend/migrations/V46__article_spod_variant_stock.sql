-- The partner's stock of one SPOD variant, read by SKU from `GET /stock` every 15 minutes and once
-- more for the lines of a cart right before its checkout. A variant without stock is neither shown
-- nor sold, and comes back by itself once the partner restocks it, which is why this is a column of
-- its own and not `active`: a sync never activates. A SKU the partner's complete stock listing no
-- longer contains is stored as 0, so an article taken out of the range disappears before the next
-- manual sync marks it missing. `NULL` is "never read" and does not hide the variant.
ALTER TABLE article_spod_variants ADD COLUMN spod_stock integer NULL;
