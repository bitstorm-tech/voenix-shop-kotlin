# Copying a database

[`scripts/copy-database.sh`](../../../../scripts/copy-database.sh) copies the
catalog and the operations data of one database the backend has migrated into
another, for example from production into your local development database.
Unlike [`migrate-legacy-data.sh`](importing-legacy-catalog-data.md), it maps
nothing: both sides have the same schema, so rows are copied as they are, ids
and sequence values included.

## What is copied

The script sorts every table of the `voenix` schema into one of three lists at
its top, and refuses to run while a table is in none of them. A new migration
that adds a table therefore needs a decision in the script first.

- **Copied (`COPY_TABLES`)**: articles with their variants and SPOD data,
  prompts with slots, categories and subcategories with their ordering, prices,
  VAT entries, suppliers, countries, promotions, and production destinations.
  The target's rows in these tables are replaced.
- **Emptied in the target (`WIPE_TABLES`)**: carts, orders, payments,
  promotion redemptions and reservations, production jobs, email jobs, and
  operations findings. They are not copied, and the target's rows go because
  they point to the articles, prompts, and promotions just replaced.
- **Kept in the target (`KEEP_TABLES`)**: user accounts with roles, tokens,
  Magic Coins, and print images, plus `schema_migrations`. Your local admin
  login survives a copy. A supplier login keeps its supplier link only when
  the copied data has a supplier with the same id; otherwise the link is
  removed.

Production destinations carry the real SFTP passwords and SPOD access tokens.
A target with production's destinations sends its production jobs to the real
producers, so disable them in the admin area before you place a test order.

Stored files (images, PDFs) live outside the database and are not copied.

## Prerequisites

1. **Both databases applied the same migrations.** The script compares their
   `schema_migrations` and stops on any difference. Start the backend of the
   matching version against the older database first.
2. `psql` and `pg_dump` on your PATH, at least the server's major version.

## Running it

```sh
scripts/copy-database.sh \
  --source 'postgresql://voenix:secret@db.example.com:5432/voenix' \
  --target 'postgresql://voenix:voenix@localhost:5432/voenix'
```

The script asks you to type the target's database name before it changes
anything; `--yes` skips that question.

## What it does

1. Opens the source read-only, enforced by PostgreSQL, and dumps the copied
   tables with `pg_dump --data-only` into a temporary file.
2. Empties the copied and the wiped tables in the target and loads the dump,
   all in **one transaction**. If anything fails, the target stays as it was.
3. Compares the row count of every copied table on both sides and fails on any
   difference.
