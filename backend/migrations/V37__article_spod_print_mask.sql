-- Which part of a SPOD article's print frame the product shows.
--
-- The frame is where the whole square design lies on the mockup. A product that is not a rectangle
-- shows less of it: an all-over print on a round button puts the design over the whole face and a
-- little beyond its rim. The sync measures the visible part with the frame, from the same preview,
-- and stores it here as a small PNG data URL, opaque where the design is seen, stretched over the
-- frame. It is measured data like the four percentages beside it, not a picture anybody uploads,
-- so it lives in the row and not in the image storage.
--
-- `NULL` means the whole frame is visible (a shirt, a bag), or that no frame was measured.
ALTER TABLE article_spod_articles
    ADD COLUMN print_mask text NULL,
    ADD CONSTRAINT ck_article_spod_articles_print_mask_with_frame CHECK (
        print_mask IS NULL OR print_frame_left_pct IS NOT NULL
    );
