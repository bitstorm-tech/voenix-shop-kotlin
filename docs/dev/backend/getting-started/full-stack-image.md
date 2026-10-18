# The full-stack image

The [`Dockerfile` at the repository root](../../../../Dockerfile) builds one
image that contains the whole application: the TypeScript backend plus the
built Vue frontend, with the backend serving the frontend. This is the image a
production deployment runs: one container, one port, no separate web server.

It is one of two images in the repository:

| Dockerfile | Contains | Build context |
| --- | --- | --- |
| [`Dockerfile`](../../../../Dockerfile) (root) | the backend + built frontend | the repository root |
| [`backend/Dockerfile`](../../../../backend/Dockerfile) | the backend only | the repository root (`docker build -f backend/Dockerfile .`) |

Both images are built from the repository root, so the one root
[`.dockerignore`](../../../../.dockerignore) governs what either context
carries: never `backend/.env.development`, `backend/.env.local`,
`node_modules`, or local `data/`.

## How the backend serves the frontend

The frontend is a single-page application: Vite builds it into a directory of
static files (`frontend/dist`) with one entry page, `index.html`. The backend
serves that directory when, and only when, `FRONTEND_DIST_PATH` names it.

`serveFrontend` in [`composition.ts`](../../../../backend/src/composition.ts)
installs Hono's `serveStatic` (from `@hono/bun`) after every module's routes:

- A request that matches a real file (`/assets/app-abc123.js`) answers with
  that file.
- A request under `/api` is a route: the modules' routes are registered
  earlier and answer first, and the fallback below steps aside for any `/api/`
  path, so an unknown API path is a `404`, never a page.
- Everything else answers with `index.html`. That is the SPA fallback: a URL
  like `/cart` or `/admin/orders` exists only inside the frontend router, so a
  full page load there must boot the SPA, which then reads the URL itself.

The cache headers mirror the split Vite produces. `index.html` and the service
worker `sw.js` are the two entry points a deployment replaces, so they must be
revalidated on every load (`no-cache, no-store`). Everything content-hashed
(`.js`, `.css`, `.woff2`) may be cached for a year
(`max-age=31536000, public, immutable`), because a new build produces new file
names, never new content under an old name. Other files carry no cache header.

In development the variable stays unset and none of this is installed; the Vite
dev server serves the frontend on port 5173 and proxies `/api` to the backend,
as described in
[Running the development server](running-the-development-server.md). When the
variable *is* set, the directory must contain an `index.html`, or
`createApplication` refuses to start with
`FRONTEND_DIST_PATH points to …, but there is no index.html in it`. A
full-stack image without a frontend is a broken build, and refusing to start is
how the mistake surfaces before a customer does.
[`composition.test.ts`](../../../../backend/src/composition.test.ts) pins
the fallback, the cache headers, and that the API is not swallowed.

## What the image contains

The root Dockerfile builds in three stages:

1. **Frontend build** (`oven/bun:alpine`): `bun install`, then
   `bun run build`, which type-checks with vue-tsc and bundles with Vite into
   `dist/`.
2. **Backend dependencies** (`oven/bun:1`): `bun install --frozen-lockfile
   --production` from `backend/package.json` and `bun.lock`, so a
   source-only change reuses the layer. The stage is Debian-based, not Alpine:
   sharp ships glibc-linked libvips binaries.
3. **Runtime** (`oven/bun:1`): the `node_modules` of stage 2, the sources
   (`src/`, `migrations/`, `package.json`, `tsconfig.json`, which Bun reads
   for the `@/` path alias), and the frontend under `/app/frontend/dist`, with
   `FRONTEND_DIST_PATH=/app/frontend/dist` baked in as the one environment
   variable the image sets itself. There is no build output: Bun runs the
   TypeScript sources directly with `bun src/main.ts`, which applies the
   pending migrations, starts the three workers, and listens on `PORT`
   (default `8080`). The stage also installs
   [`magic-wormhole`](https://magic-wormhole.readthedocs.io/), so files can be
   moved to and from a running container (for example logs or a database
   dump) with `wormhole send <file>` in a shell inside the container.

The container runs as the non-root user `app` with `/app` as its home and
working directory. Uploaded images, the image cache, and production PDFs
default to `./data/…`, resolved against `/app`; the directories are created in
the image with the right owner.

## Configuration

The image is configured through environment variables only, the ones listed in
[Running the development server](running-the-development-server.md#individual-settings).
No `.env` file is in the image, so a missing required variable fails the start
with `Invalid configuration: <VARIABLE> is required` instead of falling back
to a development value. The image needs:

| Variable | Meaning |
| --- | --- |
| `DATABASE_HOST`, `DATABASE_PORT`, `DATABASE_NAME`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` | the PostgreSQL connection; `DATABASE_SSL_MODE=require` for a managed database |
| `AUTH_SESSION_SECRET` | at least 32 bytes |
| `FRONTEND_BASE_URL` | the public HTTPS address of the service; every mailed link points back to it |
| `EMAIL_ENABLED` and, when `true`, `EMAIL_API_KEY` and `EMAIL_FROM_EMAIL` | mail delivery |
| `GENERATOR_DUMMY_MODE` and, when `false`, `GENERATOR_API_KEY` | image generation |
| `MOLLIE_API_KEY`, `MOLLIE_REDIRECT_URL`, `MOLLIE_WEBHOOK_URL`, `MOLLIE_WEBHOOK_SECRET` | payments; a test system uses a `test_` key |
| `OPERATIONS_ALERT_EMAIL` | the mailbox for the operations alert and the SPOD ops alert; unset, the findings are only logged |
| `PRODUCTION_SPOD_WEBHOOK_SECRET` | required once a SPOD destination exists, and then `OPERATIONS_ALERT_EMAIL` too |
| `RATE_LIMIT_TRUST_FORWARDED_FOR` | `true` behind exactly one trusted reverse proxy, see [Rate limiting](../conventions/rate-limiting.md) |

## Building and running locally

From the repository root:

```sh
docker build -t voenix-shop .
docker run --rm -p 8080:8080 \
  -e DATABASE_HOST=host.docker.internal \
  -e DATABASE_PORT=5432 \
  -e DATABASE_NAME=voenix_ts \
  -e DATABASE_USERNAME=voenix \
  -e DATABASE_PASSWORD=… \
  -e AUTH_SESSION_SECRET=… \
  -e FRONTEND_BASE_URL=http://localhost:8080 \
  -e EMAIL_ENABLED=false \
  -e GENERATOR_DUMMY_MODE=true \
  -e MOLLIE_API_KEY=test_… \
  -e MOLLIE_REDIRECT_URL=http://localhost:8080/checkout \
  -e MOLLIE_WEBHOOK_URL=https://…/api/payments/webhook/… \
  -e MOLLIE_WEBHOOK_SECRET=… \
  voenix-shop
```

Then open `http://localhost:8080`. The backend answers with the frontend, and
the frontend calls the backend on the same origin. The database has to be one
the TypeScript backend migrated (or an empty one); see
[the development database](running-the-development-server.md#the-development-database)
for why the Kotlin backend's database cannot be reused.

The backend-only image is built the same way with
`docker build -f backend/Dockerfile -t voenix-backend .` and run without
`FRONTEND_DIST_PATH`; it answers `404` outside `/api`.

## Deploying on Render.com

Create a Web Service with the Docker runtime pointing at this repository.
Render finds the root `Dockerfile` on its own, builds the image on every push,
and detects the listening port (8080) automatically. A test and a production
system are simply two such services running the same image with different
configuration.

The service needs:

- The environment variables of the table above, as Render environment
  variables or as an environment group. Every setting of the backend is an
  environment variable, so nothing needs a secret file or a command override;
  the Kotlin image's `-config` layering is gone with it.
- `RATE_LIMIT_TRUST_FORWARDED_FOR=true`: Render terminates TLS in front of the
  container, so every request arrives from the proxy's address.
- A persistent disk mounted at `/app/data`. Uploaded images, the image cache,
  and production PDF artifacts default to `./data/…`, resolved against `/app`.
  Without a disk they vanish on every deploy. The application creates the
  subdirectories at startup and fails with a clear "not writable" error if the
  mount's permissions are wrong (the container runs as the non-root user
  `app`).

A disk makes the service single-instance (no horizontal scaling), and
zero-downtime deploys are unavailable with a disk attached. Both are fine for
the current stage of the product, and a single instance is also what the
production worker requires (see the
[production package guide](../packages/production-package.md#composition)).
