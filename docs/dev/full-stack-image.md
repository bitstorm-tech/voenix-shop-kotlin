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
   `/app/frontend/dist`, and three configuration files.

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
every push, and detects the listening port (8080) automatically — no start
command, no port setting.

Beyond that, the service needs:

- **The environment variables** from the table above, set in the service's
  *Environment* tab. Use a Render PostgreSQL instance for the `DATABASE_*`
  values (its internal connection details, not the external ones).
- **A persistent disk mounted at `/app/data`.** Uploaded images, the image
  cache, and production PDF artifacts default to `./data/...`, resolved against
  `/app` — without a disk they vanish on every deploy. The application creates
  the subdirectories at startup and fails with a clear "not writable" error if
  the mount's permissions are wrong (the container runs as the non-root user
  `app`).
- **The public URL in the Mollie settings**: `MOLLIE_WEBHOOK_URL` must be the
  service's public HTTPS address plus
  `/api/payments/webhook/<MOLLIE_WEBHOOK_SECRET>`, and `MOLLIE_REDIRECT_URL`
  the public checkout page, for example `https://<service>.onrender.com/checkout`.

Note that a disk makes the service single-instance (no horizontal scaling), and
that zero-downtime deploys are unavailable with a disk attached — both fine for
the current stage of the product.
