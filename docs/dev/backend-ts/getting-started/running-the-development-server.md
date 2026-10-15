# Running the development server

The TypeScript backend lives in [`backend-ts/`](../../../../backend-ts). Once
the composition ticket of the port has landed, the development launcher
[`scripts/start-dev-server.sh`](../../../../scripts/start-dev-server.sh) starts
it next to the Vite frontend; until then, start it from `backend-ts/`:

```sh
bun install          # once per checkout
bun run migrate      # applies the SQL migrations to the development database
bun run dev          # starts src/main.ts and restarts it on every change
```

`bun run start` starts it without the watcher. The backend listens on `PORT`
(default `8080`); the Vite dev server on port 5173 proxies `/api` to it.

## How configuration works

Every setting is an environment variable, parsed once at startup by the Zod
schema in [`settings.ts`](../../../../backend-ts/src/platform/settings.ts)
into one `Settings` object. The variable names are the upper-snake-case form of
the Kotlin backend's YAML keys (`database.host` → `DATABASE_HOST`), and the
defaults coded in the schema are the Kotlin base `application.yaml`'s.

Three layers supply values, later ones winning:

| Layer | Source | In Git? | Contains |
| --- | --- | --- | --- |
| 1 | the defaults in `settings.ts` | yes | `localhost`, port `5432`, search path `voenix`, pool size `100`, email off, … |
| 2 | [`backend-ts/.env.development`](../../../../backend-ts/.env.development) | yes | the development database and its password, the development session secret, dummy mode, Mollie placeholders |
| 3 | `backend-ts/.env.local` | no | optional: real keys and machine-specific values |
| 4 | the real environment | — | always wins; this is how a container is configured |

`loadEnvFiles()` reads layers 2 and 3 into `process.env` without overriding a
variable that is already set, and `loadSettings(process.env)` parses the
result. (Bun also loads the two files on its own when started from
`backend-ts/`; the explicit loader makes the behaviour the same from any
directory and under any runtime.)

The one rule to remember: a variable that a file does not mention keeps the
default. Never set a variable to an empty string; `DATABASE_PASSWORD=` is a
real, empty value, and the startup refuses it with
`Invalid configuration: DATABASE_PASSWORD is required`. A missing required
variable fails the same way, naming the variable, before anything touches the
database.

`.env.development` is complete on its own, so a fresh checkout or worktree
starts without `.env.local`. Its values are not secret: the password opens the
local database container, the session secret signs cookies on your machine, and
the Mollie values are placeholders that pass the startup checks. No deployment
ever loads this file.

## The development database

`.env.development` expects a PostgreSQL database `voenix_kotlin` on
`localhost:5432` with user and password `voenix`, the same one the Kotlin
backend used. The simplest way to get one is the container described in the
[Kotlin guide](../../getting-started/running-the-development-server.md#the-development-database).
The two backends share the database but not the migration history: the Kotlin
backend records applied migrations in Flyway's table, the TypeScript backend in
`schema_migrations`. `bun run migrate` therefore applies every file to a
database the Kotlin backend created, which fails on the first `CREATE TABLE`.
Drop the database (or the `voenix` schema) and run `bun run migrate` on an
empty one; the catalog is gone with it, so fill it again as described below.

The runner applies the files of `backend-ts/migrations/` in version order, each
in one transaction, and skips what `schema_migrations` already lists. A
rewritten migration file (allowed while the product has no production data)
is not detected: drop the database and migrate from scratch.

## Local configuration file

`backend-ts/.env.local` is ignored by Git. You need it only for values that
must never land in Git:

- **Testing payments.** Set a Mollie test key and an ngrok webhook URL:

  ```sh
  MOLLIE_API_KEY=test_replace-me
  MOLLIE_WEBHOOK_URL=https://replace-me.ngrok.app/api/payments/webhook/replace-with-16-chars
  MOLLIE_WEBHOOK_SECRET=replace-with-16-chars
  ```

- **Real image generation** (`GENERATOR_DUMMY_MODE=false` plus
  `GENERATOR_API_KEY`) or **live email** (`EMAIL_ENABLED=true` plus
  `EMAIL_API_KEY` and `EMAIL_FROM_EMAIL`).
- **A different database** than the local container.

Set only the variables you want to change.

## Individual settings

| Variable | Default | Meaning |
| --- | --- | --- |
| `PORT` | `8080` | the HTTP port |
| `DATABASE_HOST`, `DATABASE_PORT`, `DATABASE_NAME`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` | `localhost`, `5432`, required, required, required | the PostgreSQL connection |
| `DATABASE_SEARCH_PATH` | `voenix` | the one schema every table lives in |
| `DATABASE_SSL_MODE` | `disable` | `disable`, `require`, `verify-ca`, or `verify-full` |
| `DATABASE_MAXIMUM_POOL_SIZE` | `100` | connections in the pool |
| `AUTH_SESSION_SECRET` | required | at least 32 UTF-8 bytes; derives every cookie key |
| `FRONTEND_BASE_URL` | `http://localhost:5173` | where every mailed link points; HTTPS outside local hosts |
| `FRONTEND_DIST_PATH` | unset | the built frontend to serve; see [the full-stack image](full-stack-image.md) |
| `EMAIL_ENABLED`, `EMAIL_POLL_INTERVAL_MINUTES`, `EMAIL_API_KEY`, `EMAIL_FROM_EMAIL`, `EMAIL_FROM_NAME` | `false`, `5`, unset, unset, `Voenix Shop` | live delivery needs the key and the sender |
| `PRODUCTION_ARTIFACT_ROOT` | `./data/production/artifacts` | where production PDFs are stored |
| `PRODUCTION_SPOD_WEBHOOK_SECRET`, `PRODUCTION_SPOD_ALERT_EMAIL` | unset | both or neither; the secret needs 32 characters |
| `GENERATOR_DUMMY_MODE`, `GENERATOR_API_KEY` | `false`, unset | not in dummy mode, the key is required |
| `MOLLIE_API_KEY`, `MOLLIE_REDIRECT_URL`, `MOLLIE_WEBHOOK_URL`, `MOLLIE_WEBHOOK_SECRET` | required | the webhook URL must be HTTPS and end in the secret (16 characters or more) |
| `RATE_LIMIT_TRUST_FORWARDED_FOR` | `false` | see [Rate limiting](../conventions/rate-limiting.md) |
| `IMAGE_PUBLIC_ROOT`, `IMAGE_PRIVATE_ROOT`, `IMAGE_CACHE_ROOT` | `./data/images/{public,private,cache}` | three non-overlapping directories |

Relative paths resolve against the working directory of the backend process.
The rules that need the filesystem (an image root must be a writable directory,
the three roots must not overlap, a configured dist path must contain an
`index.html`) are checked by the module that uses the setting when it is
created, still at startup.

## Filling the catalog

A fresh database contains no catalog. Fill it with
[`seeding-the-development-catalog.md`](seeding-the-development-catalog.md).
