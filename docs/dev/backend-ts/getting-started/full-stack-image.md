# The full-stack image

The [`Dockerfile` at the repository root](../../../../Dockerfile) builds one
image that contains the whole application: the backend plus the built Vue
frontend, with the backend serving the frontend. The composition ticket of the
TypeScript port (#277) switches that image from the Kotlin backend to
`backend-ts`; this page records what is decided and is completed by that
ticket.

## What is decided

- One image, one container, one port. The runtime stage is `oven/bun`; it runs
  `bun src/main.ts` with the built frontend copied next to it.
- The backend serves the frontend when `FRONTEND_DIST_PATH` names the directory
  of the Vite build: a request that matches a file answers with the file, a
  request under `/api` is a route, everything else answers `index.html` (the
  SPA fallback). The cache headers mirror the split Vite produces: `index.html`
  and `sw.js` are revalidated on every load, content-hashed assets are cached
  for a year.
- The image is configured through environment variables only, the ones listed
  in [Running the development server](running-the-development-server.md#individual-settings).
  No `.env` file is copied into the image, and a missing required variable
  fails the start with a message naming it.

## What the image needs

| Variable | Meaning |
| --- | --- |
| `DATABASE_HOST`, `DATABASE_PORT`, `DATABASE_NAME`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` | the PostgreSQL connection |
| `AUTH_SESSION_SECRET` | at least 32 bytes |
| `FRONTEND_BASE_URL` | the public HTTPS address of the service |
| `EMAIL_ENABLED` and, when `true`, `EMAIL_API_KEY` and `EMAIL_FROM_EMAIL` | mail delivery |
| `GENERATOR_DUMMY_MODE` and, when `false`, `GENERATOR_API_KEY` | image generation |
| `MOLLIE_API_KEY`, `MOLLIE_REDIRECT_URL`, `MOLLIE_WEBHOOK_URL`, `MOLLIE_WEBHOOK_SECRET` | payments |
| `RATE_LIMIT_TRUST_FORWARDED_FOR` | `true` behind the platform's reverse proxy |

A persistent disk mounted at `/app/data` keeps uploaded images, the image
cache, and production PDFs across deploys.

The build and run commands, and the Render.com notes, follow with the
composition ticket.
