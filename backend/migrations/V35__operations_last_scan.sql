-- The time of the operations worker's last finished scan (issue #282 follow-up), shown on the admin
-- Operations page so an operator sees that the scan runs at all.
--
-- The table has at most one row: the check on `id` allows only the value 1, and every scan
-- upserts that row in the same transaction that records its findings. A scan that fails or is
-- stopped before recording writes nothing, so the age of `finished_at` is what shows a worker that
-- stopped scanning. The time lives in the database, not in the worker's memory, because the admin
-- request may reach another process than the one running the worker, and because it survives a
-- restart.

CREATE TABLE operations_last_scan (
    id smallint NOT NULL DEFAULT 1,
    finished_at timestamptz NOT NULL,
    CONSTRAINT pk_operations_last_scan PRIMARY KEY (id),
    CONSTRAINT ck_operations_last_scan_single_row CHECK (id = 1)
);
