-- The print aspect ratio of an article (issue #205, T01).
--
-- Until now the image generator asked fal for one fixed 16:9 image, because a mug
-- was the only article there was. A t-shirt is printed square, so the ratio stops
-- being a constant of the generator and becomes what it always was: a property of
-- the article that is printed.
--
-- Mugs keep the ratio they were generated with, so every existing row is backfilled
-- with '16:9'. The DEFAULT stays after the backfill: it is the ratio of a mug, and a
-- write that says nothing about the ratio means exactly that.
--
-- The CHECK is the pair `shop.voenix.article.PrintAspectRatio` knows, and the pair fal
-- is asked for. A unit test compares the two lists, so a third ratio cannot be added
-- on one side alone.
ALTER TABLE article_mugs
    ADD COLUMN print_aspect_ratio text NOT NULL DEFAULT '16:9';

ALTER TABLE article_mugs
    ADD CONSTRAINT ck_article_mugs_print_aspect_ratio
        CHECK (print_aspect_ratio IN ('16:9', '1:1'));
