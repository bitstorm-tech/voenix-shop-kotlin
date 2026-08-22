# Seeding the development catalog

A freshly created database has no catalog. Flyway creates the tables and a few
fixed rows: the countries, the article types, and the ordering anchors. It
creates no VAT entry, no supplier, no category, no mug, and no prompt. The
storefront therefore shows empty lists until somebody enters data.

[`scripts/seed-catalog.mjs`](../../../scripts/seed-catalog.mjs) enters that data
for you. It writes a small, complete catalog through the **admin REST API**, so
it can only produce states the application itself could produce: one VAT entry,
one supplier, a mug category with two subcategories, three priced mugs with
variants, a prompt category with a subcategory, and three priced prompts.

## Prerequisites

1. **A running backend.** Start it with
   [`scripts/start-dev-server.sh`](../../../scripts/start-dev-server.sh). The
   script talks to `http://localhost:8080` unless you set `BASE_URL`.
2. **A user with the `ADMIN` role.** The application seeds no users and no
   roles. Register one, confirm the address, and grant the role in SQL as
   described in [`account-package.md`](account-package.md) under "Bootstrapping
   the first administrator".

## Running it

```sh
ADMIN_EMAIL=admin@example.com ADMIN_PASSWORD=secret bun scripts/seed-catalog.mjs
```

Bun is the repository's JavaScript runtime, but the script uses nothing beyond
the standard library, so `node scripts/seed-catalog.mjs` works as well.

The output names every entity and says whether it was created or reused:

```text
Seeding the catalog at http://localhost:8080 as admin@example.com
VAT and supplier
  created  VAT: Standard 19% (id 1)
  created  supplier: Voenix Development Supplier (id 1)
...
Done. The storefront now lists 3 mugs and 3 prompts.
```

## Why it is safe to run twice

Every entity is looked up by name in the matching admin list before it is
created. A second run therefore reuses what is already there instead of failing
on a duplicate-name conflict, which is what makes the script usable both after a
database rebuild and on a database that has been seeded before. Names work as
the identity here because the admin contract makes them unique, case
insensitively, exactly where the script relies on it.

## What the script demonstrates about the contract

Reading it is a compact tour of three rules that hold across the whole admin
API:

- **Admin lists answer bare JSON arrays.** No endpoint wraps a collection in
  `{ "items": [...] }`.
- **A write needs a session cookie and a CSRF token.** The token comes from
  `GET /api/antiforgery/token` and travels in the `X-XSRF-TOKEN` header. It is
  bound to the logged-in user, so it has to be fetched *after* the login. A
  token fetched before it belongs to nobody, and every write is rejected with
  `400 Invalid CSRF token`.
- **A price is part of the article or prompt it belongs to.** The write embeds
  a `price` object; there is no price id to reference. Both `purchaseVatId` and
  `salesVatId` are required, and the remaining fields have defaults. A net
  purchase cost and a gross sales total are enough.
