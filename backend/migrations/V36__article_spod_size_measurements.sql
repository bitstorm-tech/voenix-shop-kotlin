-- The measurements of a SPOD article's size chart (issue #286).
--
-- Spreadconnect answers the size chart of a product type as a picture with the letters A, B, C and,
-- per size, the value of each letter in millimetres. The picture was stored since V29; these are
-- the values. The partner never names a letter: what A means is shown only by the picture, so the
-- letter is stored as it is answered.
--
-- One row per size × letter, per article like the picture, and only for the sizes the article's
-- variants sell (`article_spod_variants.spod_size_id`). The size label is not stored again: it
-- comes from the variants. A sync run replaces an article's rows as a whole when the answered set
-- differs, and keeps them when the partner did not answer.
CREATE TABLE article_spod_size_measurements (
    article_id bigint NOT NULL,
    spod_size_id bigint NOT NULL,
    -- The letter of the size chart picture, e.g. `A`.
    name character varying(32) NOT NULL,
    value_mm numeric(6, 1) NOT NULL,
    CONSTRAINT pk_article_spod_size_measurements PRIMARY KEY (article_id, spod_size_id, name),
    CONSTRAINT fk_article_spod_size_measurements_article
        FOREIGN KEY (article_id)
        REFERENCES article_spod_articles (id)
        ON DELETE CASCADE,
    CONSTRAINT ck_article_spod_size_measurements_spod_size_id_positive CHECK (spod_size_id > 0),
    CONSTRAINT ck_article_spod_size_measurements_name_not_empty CHECK (name <> ''),
    CONSTRAINT ck_article_spod_size_measurements_value_mm_positive CHECK (value_mm > 0)
);
