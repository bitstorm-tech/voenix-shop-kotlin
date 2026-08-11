# The full-stack image

The [`Dockerfile` at the repository root](../../Dockerfile) builds one image
that contains the whole application: the Kotlin backend plus the built Vue
frontend, with the backend serving the frontend. This is the image a production
deployment runs — one container, one port, no separate web server.

It is not the only image in the repository:

| Dockerfile | Contains | Used by |
| --- | --- | --- |
| [`Dockerfile`](../../Dockerfile) (root) | backend + built frontend | production deployments (Render.com) |
| [`backend/Dockerfile`](../../backend/Dockerfile) | backend only | the verification stack in the `voenix-shop-specs` repository |

## How the backend serves the frontend

The frontend is a single-page application: Vite builds it into a directory of
static files (`frontend/dist`) with one entry page, `index.html`. The backend
serves that directory when — and only when — the configuration key
`frontend.distPath` names it.

[`FrontendSettings.kt`](../../backend/app/src/shop/voenix/FrontendSettings.kt)
reads the key and installs Ktor's `staticFiles` at `/`:

- A request that matches a real file (`/assets/app-abc123.js`) answers with
  that file.
- A request that matches an API route (`/api/...`) is unaffected: Ktor always
  prefers the more specific route over the static catch-all.
- Everything else answers with `index.html`. That is the SPA fallback: a URL
  like `/cart` or `/admin/orders` exists only inside the frontend router, so a
  full page load there must boot the SPA, which then reads the URL itself.

The cache headers mirror the split Vite produces. `index.html` and the service
worker `sw.js` are the two entry points a deployment replaces, so they must be
revalidated on every load (`no-cache, no-store`). Everything content-hashed
(`.js`, `.css`, `.woff2`) may be cached for a year (`immutable`) — a new build
produces new file names, never new content under an old name.

In development the key stays empty and none of this is installed: the Vite dev
server serves the frontend on port 5173 and proxies `/api` to the backend, as
described in
[`running-the-development-server.md`](backend/running-the-development-server.md).
When the key *is* set, the directory must contain an `index.html`, or the
backend refuses to start — a full-stack image without a frontend is a broken
build, and refusing to start is how the mistake surfaces before a customer
does.

## What the image contains

The root Dockerfile builds in three stages:

1. **Frontend build** (`oven/bun`): `bun install`, then `bun run build` —
   type-check with vue-tsc, bundle with Vite into `dist/`.
2. **Backend build** (`eclipse-temurin:25-jdk`): the Kotlin Toolchain wrapper
   builds the fat JAR (`./kotlin task :app:executableJarJvm`), exactly like
   `backend/Dockerfile` does.
3. **Runtime** (`eclipse-temurin:25-jre`): the JAR, the frontend under
   `/app/frontend/dist`, and three configuration files. The stage also installs
   [`magic-wormhole`](https://magic-wormhole.readthedocs.io/), so files can be
   moved to and from a running container (for example logs or a database dump)
   with `wormhole send <file>` in a shell inside the container.

The container starts with three `-config` layers, later files winning per
individual key (the same mechanism the development launcher uses):

| Layer | File | Contains |
| --- | --- | --- |
| 1 | `application.yaml` | base defaults, same file that is inside the JAR |
| 2 | [`backend/application-container.yaml`](../../backend/application-container.yaml) | every per-deployment value, as `$VAR` environment substitutions |
| 3 | [`backend/application-fullstack.yaml`](../../backend/application-fullstack.yaml) | one key: `frontend.distPath: /app/frontend/dist` |

So the image is configured entirely through environment variables, and a
variable that is missing fails startup with a clear message instead of falling
back to a development default. The image needs all of these:

| Variable | Meaning |
| --- | --- |
| `DATABASE_HOST`, `DATABASE_PORT`, `DATABASE_NAME`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` | the PostgreSQL connection |
| `AUTH_SESSION_SECRET` | session cookie signing secret, at least 32 bytes |
| `EMAIL_ENABLED` | `true` for live delivery (then also set the `email.*` keys), `false` for no-op sends |
| `GENERATOR_DUMMY_MODE` | `false` in production (then `generator.apiKey` must be set via an extra layer or the key), `true` returns uploads unchanged |
| `MOLLIE_API_KEY`, `MOLLIE_REDIRECT_URL`, `MOLLIE_WEBHOOK_URL`, `MOLLIE_WEBHOOK_SECRET` | the payment settings, see [`payment-package.md`](backend/payment-package.md) |

## Building and running locally

From the repository root:

```sh
docker build -t voenix-shop .
docker run --rm -p 8080:8080 \
  -e DATABASE_HOST=host.docker.internal \
  -e DATABASE_PORT=5432 \
  -e DATABASE_NAME=voenix_kotlin \
  -e DATABASE_USERNAME=voenix \
  -e DATABASE_PASSWORD=… \
  -e AUTH_SESSION_SECRET=… \
  -e EMAIL_ENABLED=false \
  -e GENERATOR_DUMMY_MODE=true \
  -e MOLLIE_API_KEY=test_… \
  -e MOLLIE_REDIRECT_URL=http://localhost:8080/checkout \
  -e MOLLIE_WEBHOOK_URL=https://…/api/payments/webhook/… \
  -e MOLLIE_WEBHOOK_SECRET=… \
  voenix-shop
```

Then open `http://localhost:8080` — the backend answers with the frontend, and
the frontend calls the backend on the same origin.

## Deploying on Render.com

Create a **Web Service** with the **Docker** runtime pointing at this
repository. Render finds the root `Dockerfile` on its own, builds the image on
every push, and detects the listening port (8080) automatically. A test and a
production system are simply two such services running the same image with
different configuration.

The recommended way to configure a service is a **secret file**, not
environment variables. Render mounts secret files under `/etc/secrets/`, which
is exactly the "own override file passed as the last `-config` argument" the
configuration layering was designed for — and unlike the environment layer,
which only maps a subset of the keys, a YAML file can set *every* key
(`generator.apiKey` and the `email.*` values, for example, have no environment
variable).

The service needs:

- **A secret file named `application-production.yaml`** with the full
  configuration — template below.
- **A Docker Command override** (in the service's *Settings*), because the
  image's default entrypoint cannot know whether the file exists:

  ```
  java -Djava.awt.headless=true --enable-native-access=ALL-UNNAMED -jar /app/app.jar -config=/app/application.yaml -config=/app/application-fullstack.yaml -config=/etc/secrets/application-production.yaml
  ```

  Note that `application-container.yaml` is deliberately *not* in this command.
  It is either/or: that file demands every one of its `$VAR` environment
  variables (a missing one fails startup), so a service configured through the
  secret file must not load it. The default entrypoint with environment
  variables keeps working unchanged for local `docker run`.
- **A persistent disk mounted at `/app/data`.** Uploaded images, the image
  cache, and production PDF artifacts default to `./data/...`, resolved against
  `/app` — without a disk they vanish on every deploy. The application creates
  the subdirectories at startup and fails with a clear "not writable" error if
  the mount's permissions are wrong (the container runs as the non-root user
  `app`).

Note that a disk makes the service single-instance (no horizontal scaling), and
that zero-downtime deploys are unavailable with a disk attached — both fine for
the current stage of the product.

### Secret file template

The same rules as for every other layer apply: the file lists every key, a bare
key (nothing after the colon) is *not set* and falls through to the base
defaults, and an empty string `""` would override with emptiness — never write
`""`. `frontend.distPath` stays bare: `application-fullstack.yaml` already sets
it.

```yaml
ktor:
  deployment:
    port:
  application:
    modules:

database:
  # The *internal* connection details of a Render PostgreSQL instance
  # (same region, hostname without .render.com suffix shown as "Internal").
  host: replace-me
  port: 5432
  database: replace-me
  username: replace-me
  password: replace-me
  searchPath:
  sslMode:
  maximumPoolSize:

auth:
  # At least 32 bytes.
  sessionSecret: replace-me

account:
  # The public address of this service; account emails link back to it.
  frontendBaseUrl: https://replace-me.onrender.com

frontend:
  distPath:

email:
  # true for live delivery: then apiKey (Sweego) and fromEmail are required.
  enabled: true
  pollIntervalMinutes:
  apiKey: replace-me
  fromEmail: replace-me
  fromName:

production:
  artifactRoot:

generator:
  # false = real image generation; then apiKey (fal.ai) is required. A test
  # system can run dummyMode: true and needs no key.
  dummyMode: false
  apiKey: replace-me

mollie:
  # A test system uses a test_ key, production a live_ key.
  apiKey: replace-me
  redirectUrl: https://replace-me.onrender.com/checkout
  # Public HTTPS address plus /api/payments/webhook/<webhookSecret>.
  webhookUrl: https://replace-me.onrender.com/api/payments/webhook/replace-with-16-chars
  webhookSecret: replace-with-16-chars

rateLimit:
  # Render terminates TLS in front of the container, so every request arrives
  # from the proxy's address. true makes the rate limiter read the real client
  # address from X-Forwarded-For — see backend/rate-limiting.md.
  trustForwardedFor: true

image:
  publicRoot:
  privateRoot:
  cacheRoot:
```

### Alternative: environment variables

Without a Docker Command override, the default entrypoint loads
`application-container.yaml`, and the service is configured through the
environment variables from the table further up (all of them — a missing one
fails startup). This works, but cannot set the keys that have no environment
variable, so a production system with real image generation or live email needs
the secret file.
