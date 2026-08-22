# Running the development server

The development launcher is
[`scripts/start-dev-server.sh`](../../../scripts/start-dev-server.sh). It
starts the backend with the layered configuration described below and runs two
processes in parallel:

- the Ktor backend with the Kotlin Toolchain (`./kotlin run`), and
- the Vite frontend dev server (`bun run dev` in `frontend/`).

Each log line is prefixed with `[backend]` or `[frontend]` so you can tell the
two apart. Press `Ctrl+C` to stop everything. When one process exits on its
own, for example because the backend refuses to start over a missing setting,
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
service needs to call it back. Locally that is the Mollie payment webhook.
Payment deliberately has no dummy mode, so testing a payment locally always
means a Mollie test key plus a tunnel. The `ngrok` binary must be installed
and authenticated (`brew install ngrok`, then `ngrok config add-authtoken …`
once). Its log lines appear with the `[ngrok]` prefix; the public address is
in the line containing `url=`.

## How configuration works

The backend starts through Ktor's `EngineMain`, which accepts one or more
`-config` arguments. Every file is loaded, and when the same key appears in
several files, the value from the *last* file wins. Files are merged per
individual key, not per file or per block, so a later file only changes the
keys it actually sets:

```sh
java -jar app.jar -config=application.yaml -config=overrides.yaml
```

The development launcher passes three layers:

| Layer | File | In Git? | Contains |
| --- | --- | --- | --- |
| 1 | [`backend/app/resources/application.yaml`](../../../backend/app/resources/application.yaml) | yes | base defaults, shipped inside the JAR |
| 2 | [`backend/application-dev.yaml`](../../../backend/application-dev.yaml) | yes | shared development values (dev database, dummy mode, …) |
| 3 | `backend/application-local.yaml` | no | per-developer secrets and machine-specific values |

Every file lists every key, so one glance shows which file sets what. A
key that a file does not set stays *empty*, with nothing after the colon.

There is a fourth checked-in file, `backend/application-container.yaml`. The
launcher never uses it; it is layer 2 inside the Docker image built by
[`backend/Dockerfile`](../../../backend/Dockerfile), and the only place where
configuration comes from environment variables. An image is built once and
configured per deployment, so its values are Ktor `$VAR` substitutions. The
disposable verification stack that runs this image lives in the
`voenix-shop-specs` repository (`harness/stack.md` there).

The one rule to remember: an empty key (`password:`) is *not set* and falls
through to the earlier layers. An empty string (`password: ""`) is a real
value and *overrides* the earlier layers with emptiness. The server then
fails at startup with "Missing required configuration value". Never write `""`.

Secrets are never checked in. They are empty in layers 1 and 2, and the
application's required-setting validation rejects a missing secret with a
clear startup error, so a deployment cannot silently run without one.

[`ApplicationYamlConfigTest.kt`](../../../backend/app/test/shop/voenix/config/ApplicationYamlConfigTest.kt)
loads the real base file and verifies its module entry, every default, and that
no environment substitution sneaks back in.

A production deployment does not use `application-dev.yaml`. The full-stack
image built by the repository-root `Dockerfile` layers
`application-container.yaml` and then `application-fullstack.yaml` (which turns
on frontend serving) over the base file. See
[`full-stack-image.md`](full-stack-image.md).

## Local configuration file

Create `backend/application-local.yaml` before starting the server. The
easiest way is to copy the checked-in dev layer and fill in your values:

```sh
cp backend/application-dev.yaml backend/application-local.yaml
```

Then empty the keys that `application-dev.yaml` already sets (bare key, no
`""`) and fill in the secrets. At minimum, the backend needs a database
password, a session secret, and the Mollie payment settings. Payment
deliberately has no dummy mode, so the backend refuses to start without them
(see [`payment-package.md`](../backend/packages/payment-package.md) for what each value must look
like):

```yaml
database:
  password: replace-me

auth:
  sessionSecret: replace-with-a-secret-that-is-at-least-32-bytes

mollie:
  apiKey: test_replace-me
  # The webhook URL must be HTTPS and end in the webhook secret. With ngrok,
  # use your tunnel's public address as the host.
  webhookUrl: https://replace-me.ngrok.app/api/payments/webhook/replace-with-16-chars
  webhookSecret: replace-with-16-chars
```

The file is ignored by Git. Keep it in `backend/`, not
`backend/app/resources/`. Resource files are copied into the application JAR,
so a secret stored there would be shipped with the application.

To start the server with a different setting once, without editing any file,
pass a fourth file; later `-config` arguments win. From `backend/`:

```sh
./kotlin run -- -config=app/resources/application.yaml \
    -config=application-dev.yaml -config=application-local.yaml \
    -config=my-experiment.yaml
```

## Individual settings

Image storage defaults to `./data/images/public`, `./data/images/private`, and
`./data/images/cache`, resolved against the backend process working directory.
Override them under the `image:` block. Production deployments should use three
non-overlapping absolute mounted paths. Startup creates missing directories and
rejects files, overlapping roots, and roots that are not writable. See
[`image-package.md`](../backend/packages/image-package.md) for delivery, upload, and cache behavior.

Production PDF artifacts default to `./data/production/artifacts`, resolved
against the backend process working directory; override the directory with
`production.artifactRoot`. Startup creates the directory when it is missing.

The backend can serve the built frontend itself. `frontend.distPath` names the
directory with the Vite build output, and the backend then answers `/` and
every non-API URL from it. In development the key stays empty, because the Vite
dev server serves the frontend, so this matters only for the full-stack image,
described in [`full-stack-image.md`](full-stack-image.md).

Image generation talks to the paid fal.ai API, so local development runs it in
dummy mode. With `generator.dummyMode: true`, the generator answers a request
with the uploaded image unchanged and never calls the provider. Everything else
around it still happens (the Magic Coin check, the prompt lookup, and the coin
spend), so the endpoint behaves like the real one and costs nothing.

The default is deliberately the opposite. `generator.dummyMode` defaults to
`false`, and a server that is not in dummy mode needs `generator.apiKey`;
without it, startup fails with a clear error. A default of `true` would let a
deployment that forgot its key start up and hand every customer their own photo
back, and nobody would notice until one of them complained.

Email is disabled by default and the composed application operates the email
runtime. With `email.enabled: false`, direct sends are no-ops and queued jobs
stay open untouched. Enable live delivery with `email.enabled: true`,
`email.apiKey` (the Sweego API key), and `email.fromEmail`. `email.fromName`
defaults to `Voenix Shop`, and `email.pollIntervalMinutes` defaults to `5`.
Never commit the Sweego API key to `application.yaml` or another classpath
resource.

## When Flyway refuses to start: a rewritten migration

While the product has no production data, a migration may be *rewritten in
place* instead of getting a follow-up file. Issue #110 did exactly that: it
edited `V15` and `V16` and deleted `V19` altogether. The supplier
fulfillment feature (issue #119) rewrote `V5`, `V8`, and `V11` for the shipping
columns, the item snapshot table, and the `users.supplier_id` link. Flyway
stores a checksum of every migration it has already applied, so on a database
that still carries the old files it compares the new content against the stored
checksum and stops the backend at startup with a checksum-mismatch error naming
the version.

That failure is intended and it is not a bug in your checkout. It means your
local database is older than the migrations in Git. The fix is to throw the
local database away. Drop and recreate the database (or just the `voenix`
schema), then start the server again. Flyway finds an empty database and
applies the rewritten migrations from scratch. The catalog is gone with it, so
fill it again as described below.

## Filling the catalog

A fresh database contains no catalog data, so the storefront starts empty. Fill
it with [`seeding-the-development-catalog.md`](seeding-the-development-catalog.md),
which describes the seed script and what it writes. To work with the real
catalog of a legacy backend instead, import it with
[`importing-legacy-catalog-data.md`](importing-legacy-catalog-data.md).
