# Seeding the development catalog

A freshly migrated database has no catalog: the migrations create the tables
and a few fixed rows (the countries, the article types, the ordering anchors)
and nothing else. The storefront shows empty lists until somebody enters data.

[`scripts/seed-catalog.mjs`](../../../../scripts/seed-catalog.mjs) enters that
data through the **admin REST API**. It writes one VAT
entry, one supplier, a mug category with two subcategories, three priced mugs
with variants, a prompt category with a subcategory, and three priced prompts.
It seeds mugs only; a SPOD article comes from a sync run against the
Spreadconnect backoffice, as
[Configuring a SPOD article](../../guides/configuring-spod-articles.md)
describes.

## Prerequisites

1. **A running backend**, see
   [Running the development server](running-the-development-server.md). The
   script talks to `http://localhost:8080` unless you set `BASE_URL`.
2. **A user with the `ADMIN` role.** The application seeds no users and no
   roles. Register one, confirm the address (in SQL while `EMAIL_ENABLED` is
   `false`, because no confirmation mail leaves and the token is stored only
   as a hash), and grant the role in SQL as
   [the account package guide](../packages/account-package.md#bootstrapping-the-first-administrator)
   describes under "Bootstrapping the first administrator".

## Running it

```sh
ADMIN_EMAIL=admin@example.com ADMIN_PASSWORD=secret bun scripts/seed-catalog.mjs
```

The output names every entity and says whether it was created or reused. Every
entity is looked up by name in the matching admin list before it is created, so
a second run reuses what is there instead of failing on a duplicate-name
conflict.

## What the script demonstrates about the contract

- **Admin lists answer bare JSON arrays**, never `{ "items": [...] }`.
- **A write needs a session cookie and a CSRF token.** The token comes from
  `GET /api/antiforgery/token` and travels in the `X-XSRF-TOKEN` header. It is
  bound to the logged-in user, so it has to be fetched *after* the login.
- **A price is part of the article or prompt it belongs to.** The write embeds
  a `price` object; there is no price id to reference.
