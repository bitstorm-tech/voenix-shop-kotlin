-- The sync stores the mockup of a colour from the partner's `/views` answer (issue #260, T3).
--
-- A `LISTING` image is recognised by `spod_image_id`, so an unchanged picture is not downloaded
-- twice. A `MOCKUP` has no partner id — it is a URL on the partner's image server — so the URL it
-- was fetched from is stored instead and compared by the next run: the same URL keeps the stored
-- file, a different one is fetched and replaces it (SPOD sync decision 7).
ALTER TABLE article_spod_images ADD COLUMN source_url character varying(1024) NULL;
