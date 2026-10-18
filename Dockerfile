# The whole application as one image: the TypeScript backend serving the built
# Vue frontend. Build context is the repository root. This is the image a
# production deployment (for example Render.com) runs; backend/Dockerfile
# builds the backend-only image.
# See docs/dev/backend/getting-started/full-stack-image.md.

# Frontend build stage: `bun run build` type-checks with vue-tsc and bundles
# with Vite into dist/.
FROM oven/bun:alpine AS frontend-build

WORKDIR /src
# Dependencies first, so a source-only change reuses this layer.
COPY frontend/package.json frontend/bun.lock ./
RUN bun install --frozen-lockfile
COPY frontend/ ./
RUN bun run build

# Backend dependency stage, identical to the one in backend/Dockerfile: only
# the lockfile-pinned production dependencies. Debian-based, not Alpine: sharp
# ships glibc-linked libvips binaries, and the runtime stage is the same base.
FROM oven/bun:1 AS backend-deps

WORKDIR /app
COPY backend/package.json backend/bun.lock ./
RUN bun install --frozen-lockfile --production

# Runtime stage: Bun runs the TypeScript sources directly, next to the built
# frontend. No .env file travels with the sources (see .dockerignore): a
# deployment is configured through environment variables only.
FROM oven/bun:1 AS runtime

RUN useradd --system --create-home --home-dir /app --shell /usr/sbin/nologin app

# magic-wormhole: files can be sent to and from the running container (for
# example to pull a database dump or logs off a deployed instance).
RUN apt-get update \
    && apt-get install --no-install-recommends --yes magic-wormhole \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=backend-deps --chown=app:app /app/node_modules node_modules
COPY --chown=app:app backend/package.json backend/tsconfig.json ./
COPY --chown=app:app backend/migrations migrations
COPY --chown=app:app backend/src src
# The layer that makes this the full-stack image: the built frontend, and the
# variable that points the backend at it.
COPY --from=frontend-build --chown=app:app /src/dist frontend/dist
ENV FRONTEND_DIST_PATH=/app/frontend/dist

# Image and PDF storage default to ./data, resolved against this directory. The
# directories are baked in with the right owner: when Docker creates a missing
# bind-mount target itself, every created parent belongs to root, and the app
# user could no longer create the sibling directories next to it.
RUN mkdir -p data/images/public data/images/private data/images/cache \
        data/production/artifacts \
    && chown -R app:app data
USER app
EXPOSE 8080

ENTRYPOINT ["bun", "src/main.ts"]
