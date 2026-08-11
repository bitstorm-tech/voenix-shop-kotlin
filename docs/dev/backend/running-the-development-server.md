# Running the development server

The development launcher is
[`scripts/start-dev-server.sh`](../../../scripts/start-dev-server.sh). It loads
the local configuration from `backend/.env` and starts two processes in
parallel:

- the Ktor backend with the Kotlin Toolchain (`./kotlin run`), and
- the Vite frontend dev server (`bun run dev` in `frontend/`).

Each log line is prefixed with `[backend]` or `[frontend]` so you can tell the
two apart. Press `Ctrl+C` to stop everything. When one process exits on its
own — for example because the backend refuses to start over a missing setting —
the script stops the other processes too and exits with the same status.

## Start the servers

The script finds the project directory from its own location. Your terminal's
current directory therefore does not matter. You can invoke it with an absolute
path from anywhere:

```sh
/path/to/voenix-shop-kotlin/scripts/start-dev-server.sh
```

To invoke it by name, add the repository's `scripts` directory to your shell's
`PATH`. For zsh, add this line to `~/.zshrc`, replacing the example path with
the location of your checkout:

```sh
export PATH="$PATH:/path/to/voenix-shop-kotlin/scripts"
```

Open a new terminal or run `source ~/.zshrc`. You can then start the backend
and the frontend from any directory:

```sh
start-dev-server.sh
```

## Optional ngrok tunnel

Pass `--with-ngrok` to additionally start an [ngrok](https://ngrok.com/)
tunnel to the backend on port 8080:

```sh
start-dev-server.sh --with-ngrok
```

The tunnel gives the backend a public HTTPS address, which is what an external
service needs to call it back — locally that is the Mollie payment webhook.
Payment deliberately has no dummy mode, so testing a payment locally always
means a Mollie test key plus a tunnel. The `ngrok` binary must be installed
and authenticated (`brew install ngrok`, then `ngrok config add-authtoken …`
once). Its log lines appear with the `[ngrok]` prefix; the public address is
in the line containing `url=`.

## Environment file

Create `backend/.env` before starting the server. At minimum, it needs the
database credentials, a session secret, and the Mollie payment settings —
payment deliberately has no dummy mode, so the backend refuses to start
without them (see
[`payment-package.md`](payment-package.md) for what each value must look
like):

```dotenv
DATABASE_NAME=voenix
DATABASE_USERNAME=voenix
DATABASE_PASSWORD=replace-me
AUTH_SESSION_SECRET=replace-with-a-secret-that-is-at-least-32-bytes
EMAIL_ENABLED=false
GENERATOR_DUMMY_MODE=true
MOLLIE_API_KEY=test_replace-me
MOLLIE_REDIRECT_URL=http://localhost:5173/checkout
# The webhook URL must be HTTPS and end in the webhook secret. With ngrok,
# use your tunnel's public address as the host.
MOLLIE_WEBHOOK_URL=https://replace-me.ngrok.app/api/payments/webhook/replace-with-16-chars
MOLLIE_WEBHOOK_SECRET=replace-with-16-chars
# Optional overrides; these relative defaults are used when omitted.
# IMAGE_PUBLIC_ROOT=./data/images/public
# IMAGE_PRIVATE_ROOT=./data/images/private
# IMAGE_CACHE_ROOT=./data/images/cache
# PRODUCTION_ARTIFACT_ROOT=./data/production/artifacts
```

The launcher reads this file as Bash. Use normal shell assignment syntax and
do not put spaces around `=`. Wrap a value in single quotes when it contains
spaces or shell characters such as `$` or `#`; single quotes keep those
characters literal:

```dotenv
DATABASE_PASSWORD='price$with spaces#and-symbols'
```

The `.env` file is ignored by Git. Keep it in `backend/`, not
`backend/app/resources/`: resource files are copied into the application JAR,
so a secret stored there would be shipped with the application.

The launcher exports the `.env` entries before Ktor starts. This is important
because Ktor resolves references such as `$DATABASE_USERNAME:` while loading
`application.yaml`, before `Application.module` runs. The text after the colon
is the fallback when the environment variable is not set. For example,
`$DATABASE_HOST:localhost` uses `localhost` by default.

An environment variable already exported by the calling shell takes precedence
over the matching value in `.env`. For example, this starts the server with a
different database name without editing the file:

```sh
DATABASE_NAME=temporary_database start-dev-server.sh
```

## Configuration file

Ktor reads [`application.yaml`](../../../backend/app/resources/application.yaml).
Each configurable value uses one YAML line with an environment variable and a
fallback:

```yaml
Database:
  Host: "$DATABASE_HOST:localhost"
```

Here, `DATABASE_HOST` wins when it is set. Otherwise, Ktor uses `localhost`.
The empty fallback in `Username: "$DATABASE_USERNAME:"` deliberately produces
an empty value, which the application's required-setting validation then
rejects with a clear startup error.

[`ApplicationYamlConfigTest.kt`](../../../backend/app/test/shop/voenix/config/ApplicationYamlConfigTest.kt)
loads the real YAML file and verifies its module entry and every environment
fallback.

Image storage defaults to `./data/images/public`, `./data/images/private`, and
`./data/images/cache`, resolved against the backend process working directory.
Override them with `IMAGE_PUBLIC_ROOT`, `IMAGE_PRIVATE_ROOT`, and
`IMAGE_CACHE_ROOT`. Production deployments should use three non-overlapping
absolute mounted paths. Startup creates missing directories and rejects files,
overlapping roots, and roots that are not writable. See
[`image-package.md`](image-package.md) for delivery, upload, and cache behavior.

Production PDF artifacts default to `./data/production/artifacts`, resolved
against the backend process working directory; override the directory with
`PRODUCTION_ARTIFACT_ROOT`. Startup creates the directory when it is missing.

Image generation talks to the paid fal.ai API, so local development runs it in
dummy mode: with `GENERATOR_DUMMY_MODE=true`, the generator answers a request
with the uploaded image unchanged and never calls the provider. Everything else
around it still happens — the Magic Coin check, the prompt lookup, and the coin
spend — so the endpoint behaves like the real one and costs nothing.

The default is deliberately the opposite. `GENERATOR_DUMMY_MODE` defaults to
`false`, and a server that is not in dummy mode needs `FAL_API_KEY`; without it,
startup fails with a clear error. A default of `true` would let a deployment
that forgot its key start up and hand every customer their own photo back, and
nobody would notice until one of them complained.

Email is disabled by default and the composed application operates the email
runtime: with `EMAIL_ENABLED=false`, direct sends are no-ops and queued jobs
stay open untouched. Enable live delivery with `EMAIL_ENABLED=true`,
`SWEEGO_API_KEY`, and `EMAIL_FROM_ADDRESS`. `EMAIL_FROM_NAME` defaults to
`Voenix Shop`, and `EMAIL_POLL_INTERVAL_MINUTES` defaults to `5`. Never commit
the Sweego API key to `application.yaml` or another classpath resource.

## Filling the catalog

A fresh database contains no catalog data, so the storefront starts empty. Fill
it with [`seeding-the-development-catalog.md`](seeding-the-development-catalog.md),
which describes the seed script and what it writes. To work with the real
catalog of a legacy backend instead, import it with
[`importing-legacy-catalog-data.md`](importing-legacy-catalog-data.md).
