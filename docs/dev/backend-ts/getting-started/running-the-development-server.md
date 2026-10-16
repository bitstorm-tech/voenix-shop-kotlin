# Running the development server

The TypeScript backend lives in [`backend-ts/`](../../../../backend-ts). The
development launcher is the `dev` script of the repository root's
[`package.json`](../../../../package.json). It runs two processes in parallel:

- the backend with `bun run dev` in `backend-ts/` (`src/main.ts` under Bun's
  watcher, restarted on every change), and
- the Vite frontend dev server with `bun run dev` in `frontend/`.

The script is a single call of
[`concurrently`](https://www.npmjs.com/package/concurrently), a small tool that
starts several commands and merges their output. Each log line is prefixed
with `[backend]` or `[frontend]` so you can tell the two apart. Press `Ctrl+C`
to stop everything. When one process exits on its own, for example because the
backend refuses to start over a missing setting, `--kill-others` stops the
other processes too, and the launcher exits with a non-zero status.

(Bun can run scripts in parallel by itself with `bun run --parallel`, but it
keeps the other processes running when one of them fails. A frontend without a
backend behind it only produces confusing proxy errors, so the launcher uses
`concurrently`.)

## Start the servers

Once per checkout, install the dependencies of the root (`concurrently`), the
backend, and the frontend:

```sh
bun install
bun install --cwd backend-ts
bun install --cwd frontend
```

Then start both servers from the repository root:

```sh
bun run dev
```

Bun looks for the `package.json` in the current directory, so run the command
in the repository root. Inside `backend-ts/` or `frontend/`, `bun run dev`
starts only that package's own `dev` script.

The backend alone starts from `backend-ts/`:

```sh
bun run dev        # src/main.ts under the watcher
bun run start      # the same without the watcher, what the container runs
bun run migrate    # only the migrations, without starting the server
```

The backend listens on `PORT` (default `8080`); the Vite dev server on port
5173 proxies `/api` to it.

## What a start does

`src/main.ts` reads the environment files and the settings, then
`createApplication` in
[`composition.ts`](../../../../backend-ts/src/composition.ts):

1. applies the pending SQL migrations of `backend-ts/migrations/` (the same
   runner as `bun run migrate`, so a separate migrate step is never needed
   before a start);
2. opens the connection pool and creates every module;
3. serves the built frontend when `FRONTEND_DIST_PATH` is set (never in
   development).

Then `main.ts` starts the email and the production worker and listens. A
misconfiguration fails in step 0, before anything touches the database:
`Invalid configuration: MOLLIE_API_KEY is required`. `SIGTERM` and `Ctrl+C`
stop the server, then the workers, then the pool.

## Optional ngrok tunnel

The `dev:ngrok` script starts the same two processes plus an
[ngrok](https://ngrok.com/) tunnel to the backend on port 8080:

```sh
bun run dev:ngrok
```

The tunnel gives the backend a public HTTPS address, which is what an external
service needs to call it back. Locally that is the Mollie payment webhook.
Payment deliberately has no dummy mode, so testing a payment locally always
means a Mollie test key plus a tunnel. The `ngrok` binary must be installed
and authenticated (`brew install ngrok`, then `ngrok config add-authtoken …`
once). Its log lines appear with the `[ngrok]` prefix; the public address is
in the line containing `url=`.

## How configuration works

Every setting is an environment variable, parsed once at startup by the Zod
schema in [`settings.ts`](../../../../backend-ts/src/platform/settings.ts)
into one `Settings` object. The variable names are the upper-snake-case form of
the Kotlin backend's YAML keys (`database.host` → `DATABASE_HOST`), and the
defaults coded in the schema are the Kotlin base `application.yaml`'s.

Four layers supply values, later ones winning:

| Layer | Source | In Git? | Contains |
| --- | --- | --- | --- |
| 1 | the defaults in `settings.ts` | yes | `localhost`, port `5432`, search path `voenix`, pool size `100`, email off, … |
| 2 | [`backend-ts/.env.development`](../../../../backend-ts/.env.development) | yes | the development database and its password, the development session secret, dummy mode, Mollie placeholders |
| 3 | `backend-ts/.env.local` | no | optional: real keys and machine-specific values; the template is [`backend-ts/.env.example`](../../../../backend-ts/.env.example) |
| 4 | the real environment | — | always wins; this is how a container is configured |

`loadEnvFiles()` reads layers 2 and 3 into `process.env` without overriding a
variable that is already set, and `loadSettings(process.env)` parses the
result. (Bun also loads the two files on its own when started from
`backend-ts/`; the explicit loader makes the behaviour the same from any
directory and under any runtime.) The launcher passes nothing: there is no
`-config` argument any more, the backend finds its files next to itself.

The one rule to remember: a variable that a file does not mention keeps the
default. Never set a variable to an empty string; `DATABASE_PASSWORD=` is a
real, empty value, and the startup refuses it with
`Invalid configuration: DATABASE_PASSWORD is required`. A missing required
variable fails the same way, naming the variable, before anything touches the
database. Text values lose surrounding spaces, except `DATABASE_PASSWORD`,
which is taken as written (in a `.env` file, quote it to keep a trailing
space).

`.env.development` is complete on its own, so a fresh checkout or worktree
starts without `.env.local`. Its values are not secret: the password opens the
local database container, the session secret signs cookies on your machine, and
the Mollie values are placeholders that pass the startup checks. No deployment
ever loads this file: the root `.dockerignore` keeps all three `.env` files out of
every image.

## The development database

`.env.development` expects a PostgreSQL database `voenix_ts` on
`localhost:5432` with user and password `voenix`. The simplest way to get one is
the container described in the
[Kotlin guide](../../getting-started/running-the-development-server.md#the-development-database);
the container creates only the Kotlin backend's `voenix_kotlin`, so create the
second database in it once:

```sh
docker exec voenix-postgres createdb -U voenix voenix_ts
```

The two backends share the container but not a database, because they do not
share the migration history: the Kotlin backend records applied migrations in
Flyway's table, the TypeScript backend in `schema_migrations`. Pointed at a
database the Kotlin backend created, the first start fails with
`Migration V1__create_countries.sql failed` (`relation "countries" already
exists`). Drop that database (or its `voenix` schema) and start again on an
empty one; the migrations run as part of the start. A new database has no
catalog, so fill it as described below.

The runner applies the files of `backend-ts/migrations/` in version order, each
in one transaction, under the same advisory lock the Kotlin backend took, and
skips what `schema_migrations` already lists. A rewritten migration file
(allowed while the product has no production data) is not detected: drop the
database and migrate from scratch.

## Local configuration file

`backend-ts/.env.local` is ignored by Git. Start it as a copy of the
checked-in template [`backend-ts/.env.example`](../../../../backend-ts/.env.example),
which lists every variable you are likely to change with a short explanation,
all commented out:

```sh
cp backend-ts/.env.example backend-ts/.env.local
```

The backend never reads `.env.example` itself. You need `.env.local` only for
values that are machine-specific or must never land in Git:

- **An existing image store.** The image roots default to `backend-ts/data/`,
  which starts empty. If your database holds catalog images imported earlier
  (for example into the repository-root `storage/` folder the Kotlin backend
  used through `backend/application-local.yaml`), point the roots there, or
  every such image answers `404`:

  ```sh
  IMAGE_PUBLIC_ROOT=/absolute/path/to/storage/public/images
  IMAGE_PRIVATE_ROOT=/absolute/path/to/storage/private/images
  IMAGE_CACHE_ROOT=/absolute/path/to/storage/cache
  PRODUCTION_ARTIFACT_ROOT=/absolute/path/to/storage/production
  ```

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

Set only the variables you want to change. To start with a different setting
once, without editing any file, set it in the environment; it wins over both
files:

```sh
PORT=9090 bun run dev
```

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

Image generation talks to the paid fal.ai API, so `.env.development` runs it in
dummy mode: the generator answers a request with the uploaded image unchanged
and never calls the provider, while everything around it (the Magic Coin check,
the prompt lookup, the coin spend) still happens. The default is deliberately
the opposite, so a deployment that forgot its key refuses to start instead of
handing every customer their own photo back.

## Filling the catalog

A fresh database contains no catalog. Fill it with
[`seeding-the-development-catalog.md`](seeding-the-development-catalog.md).
