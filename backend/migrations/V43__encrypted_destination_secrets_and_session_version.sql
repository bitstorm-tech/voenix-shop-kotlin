-- Go-live packet 8 (B13): destination secrets at rest and revocable sessions.
--
-- 1. The SFTP password and the SPOD access token are stored AES-256-GCM sealed with
--    PRODUCTION_SECRETS_KEY (`enc:v1:…`, src/platform/secret-box.ts). The sealed form is longer
--    than the plaintext, so both columns become `text`. SQL cannot seal (the key is not in the
--    database), so every plaintext value is blanked here instead of being kept: a dump or backup
--    taken from now on holds no usable credential. A blank value fails to open, so each existing
--    destination fails its deliveries or submissions until an admin enters the secret again.
-- 2. `users.session_version` is copied into the session cookie at login and compared on every
--    request; a password change or reset increments it, which ends every older session of the
--    user at once.

ALTER TABLE production_destination_sftp
    ALTER COLUMN password TYPE text;

ALTER TABLE production_destination_spod
    ALTER COLUMN access_token TYPE text;

UPDATE production_destination_sftp SET password = '' WHERE password NOT LIKE 'enc:v1:%';

UPDATE production_destination_spod SET access_token = '' WHERE access_token NOT LIKE 'enc:v1:%';

ALTER TABLE users
    ADD COLUMN session_version integer NOT NULL DEFAULT 0;
