# The TypeScript backend as a self-contained image, without the frontend. Build
# context is the repository root, so the root .dockerignore governs both images:
#
#     docker build -f backend-ts/Dockerfile -t voenix-backend-ts .
#
# The full-stack image a production deployment runs, backend plus built
# frontend, is the Dockerfile at the repository root.
# See docs/dev/backend-ts/getting-started/full-stack-image.md.

# Dependency stage: only the lockfile-pinned production dependencies, so a
# source-only change reuses this layer. Debian-based, not Alpine: sharp ships
# glibc-linked libvips binaries, and the runtime stage below is the same base.
FROM oven/bun:1 AS backend-deps

WORKDIR /app
COPY backend-ts/package.json backend-ts/bun.lock ./
RUN bun install --frozen-lockfile --production

# Runtime stage: Bun runs the TypeScript sources directly; there is no build
# output. The sources are copied in after the dependencies for the same layer
# reason, and no .env file travels with them (see the root .dockerignore): a
# deployment is configured through environment variables only.
FROM oven/bun:1 AS runtime

RUN useradd --system --create-home --home-dir /app --shell /usr/sbin/nologin app
WORKDIR /app

COPY --from=backend-deps --chown=app:app /app/node_modules node_modules
COPY --chown=app:app backend-ts/package.json backend-ts/tsconfig.json ./
COPY --chown=app:app backend-ts/migrations migrations
COPY --chown=app:app backend-ts/src src

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
