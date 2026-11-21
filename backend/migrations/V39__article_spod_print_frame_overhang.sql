-- Allow all-over SPOD print frames whose bleed extends beyond the mockup canvas edges.
--
-- A full-bleed hotspot (`OVERALL_FRONT` on a poster, button, or phone case) can lay the square
-- design slightly past the edges of the 1000 × 1000 mockup picture while the product shows only
-- the part inside it (`print_mask`). The frame still has to be positive, bounded to `numeric(5,2)`,
-- and overlap the `[0, 100] × [0, 100]` mockup canvas.
ALTER TABLE article_spod_articles
    DROP CONSTRAINT ck_article_spod_articles_print_frame,
    ADD CONSTRAINT ck_article_spod_articles_print_frame CHECK (
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
            AND print_frame_width_pct > 0
            AND print_frame_height_pct > 0
            AND print_frame_width_pct <= 400
            AND print_frame_height_pct <= 400
            AND print_frame_left_pct >= -300
            AND print_frame_top_pct >= -300
            AND print_frame_left_pct < 100
            AND print_frame_top_pct < 100
            AND print_frame_left_pct + print_frame_width_pct > 0
            AND print_frame_top_pct + print_frame_height_pct > 0
        )
    );
