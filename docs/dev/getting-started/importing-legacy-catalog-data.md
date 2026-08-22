# Importing catalog data from a legacy backend

[`scripts/migrate-legacy-data.sh`](../../../scripts/migrate-legacy-data.sh)
copies the catalog of a legacy backend's database into the Kotlin backend's
database. Use it when you want to work with the real catalog instead of the
small demo catalog that
[`seeding-the-development-catalog.md`](seeding-the-development-catalog.md)
creates.

The script recognizes its source on its own:

- **v1**: the Go backend (`app.voenix.shop`), the version that is live. This
  is where the real catalog data lives.
- **dotnet**: the .NET backend (`voenix-shop`) with all of its own
  migrations applied.

It copies the VAT entries, suppliers, prices, article categories and
subcategories, mugs with their details and variants, prompt categories and
subcategories, prompt slots with their variants, prompts, and the mappings
between prompts and slot variants. It does **not** copy users, carts, orders,
payments, coupons, or image files. Those either belong to people (accounts)
or to transactions that the new backend starts fresh.

The script only ever **reads** from the source, and it opens every source
connection in read-only mode, enforced by PostgreSQL itself. A write on that
connection would fail instead of changing anything. Pointing it at the live
database is therefore safe; pointing it at a dump restored locally is safer
still.

## Prerequisites

1. **A source database** with one of the two schemas above.
2. **A target database that Flyway has migrated.** Start the Kotlin backend
   once against the target database; Flyway then creates all tables. The
   script checks this.
3. `psql` on your PATH.

## Running it

Pass one PostgreSQL connection string per database:

```sh
scripts/migrate-legacy-data.sh \
  --source 'postgresql://voenix:voenix@localhost:5432/voenix_v1' \
  --target 'postgresql://voenix:voenix@localhost:5432/voenix_kotlin'
```

Both databases keep their data in the `voenix` schema, and the script
addresses that schema explicitly, so the connection strings need no
`search_path` option.

## What the script does, in order

1. **Checks the source data against the target schema's rules.** The Kotlin
   schema is stricter than either legacy schema: names must be unique
   case-insensitively, a price belongs to exactly one article or one prompt,
   a subcategory must belong to the row's own category, and several text
   columns have length limits. Every violation is reported with the offending
   rows, and the script stops **before** touching the target. Fix the data in
   the source and run the script again.
2. **Truncates the target's catalog tables.** `TRUNCATE ... CASCADE` also
   empties every table that references them, so carts, orders, and payments in
   the target database are wiped along the way. The import is therefore meant
   for a development database, not for one whose transactions you want to
   keep.
3. **Copies the tables in foreign-key order** with `COPY`, keeping all ids.
   Three shapes change on the way:
   - The legacy `articles` and `article_mug_details` rows merge into
     `article_mugs`, and every article and variant is registered in the
     identity tables `article_identities` and `article_variant_identities`.
   - `prompt_slot_types` becomes `prompt_slots`, and the mapping column
     `slot_id` becomes `slot_variant_id`.
   - A supplier's country is matched against the target's own seeded country
     rows, by `country_code` for a .NET source and by name for a v1 source,
     because v1 countries have no code.
4. **Resets the identity sequences**, so the next insert continues after the
   highest imported id.
5. **Compares row counts** between source and target and fails when any pair
   differs. The source counts apply the same filters as the copies, so a
   skipped row never shows up as a mismatch.

## What changes on a v1 import

The v1 schema is two generations older than the Kotlin one, so the import
makes a few decisions instead of copying blindly:

- **Positions are invented.** v1 has no display positions on categories,
  subcategories, articles, and prompts, so the import numbers them in id
  order, that is, creation order. Slot positions exist in v1 but start at
  0; the target requires positions from 1, so they are renumbered.
- **The Magic Coins article is skipped.** v1 models Magic Coins as a hidden
  `CREDIT` catalog article (id 1000) with its own variant and price, because
  its cart demanded one. The Kotlin backend has a real Magic Coins module and
  no such article, so the article, its variant, and its price stay behind.
  The `Digital Products` category that held it is still imported. Delete it
  in the admin area if you do not want it. Any other non-mug article type
  would be skipped and reported the same way.
- **Prompts without a category are skipped**, together with their slot
  mappings. The target requires a category on every prompt. Every skipped
  prompt is listed in a `NOTICE` block.
- **Prices keep only their sales side.** A v1 price row carries many derived
  columns; the import keeps the sales gross total, resets the purchase side,
  and finds the VAT row by its percent value. This repeats the decision the
  earlier v1-to-.NET migration script already made. Only prices that a
  migrated mug or prompt references are copied.

## The one deliberate data change on every import

An article that is active in the source but has no price, no category, or no
mug details is imported as **inactive**. The Kotlin schema forbids an active
article without them; the legacy storefronts simply hid such articles, so
customers never saw them anyway. The script lists every demoted article by id
and name in a `NOTICE` block. Review that list and complete the articles in
the admin area if they should be for sale.

## When something goes wrong

- **A pre-flight check fails.** The target is untouched. The message names the
  table and the offending rows; fix them in the source database and rerun.
- **A copy step fails halfway.** The target's catalog is then incomplete, but
  nothing is lost. The script truncates the same tables at the start of every
  run, so fixing the cause and rerunning gives a clean result.
