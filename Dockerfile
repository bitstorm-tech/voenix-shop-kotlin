# The whole application as one image: the TypeScript backend serving the built
# Vue frontend. Build context is the repository root. This is the image a
# production deployment (for example Sliplane/Render) runs; backend/Dockerfile
# builds the backend-only image.
# See docs/dev/backend/getting-started/full-stack-image.md.

ARG WORMHOLE_RS_VERSION=0.8.1
ARG WORMHOLE_RS_AMD64_SHA256=bc5f2491fa483237a7f2b194b1083a21c47b76513a72695dc8cefd55602db1a4

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

# magic-wormhole.rs (Rust CLI): single native binary (no Python runtime),
# used to transfer files (for example database dumps or assets) between servers.
# On amd64 we fetch the pinned official release tarball and verify its SHA-256;
# on other architectures (e.g. local Apple Silicon arm64) we compile the same
# pinned version in this throwaway stage so only the binary enters runtime.
FROM oven/bun:1 AS wormhole
ARG WORMHOLE_RS_VERSION
ARG WORMHOLE_RS_AMD64_SHA256
RUN arch="$(dpkg --print-architecture)" \
    && if [ "$arch" = "amd64" ]; then \
         bun -e ' \
           const url = `https://github.com/magic-wormhole/magic-wormhole.rs/releases/download/${process.env.WORMHOLE_RS_VERSION}/magic-wormhole-cli-x86_64-unknown-linux-gnu.tgz`; \
           const res = await fetch(url); \
           if (!res.ok) throw new Error(`Failed to download wormhole-rs: ${res.status}`); \
           await Bun.write("/tmp/wormhole-rs.tgz", res); \
         ' \
         && echo "${WORMHOLE_RS_AMD64_SHA256}  /tmp/wormhole-rs.tgz" | sha256sum -c - \
         && tar -xzf /tmp/wormhole-rs.tgz -C /usr/local/bin wormhole-rs \
         && rm /tmp/wormhole-rs.tgz; \
       else \
         apt-get update \
         && apt-get install --no-install-recommends --yes cargo gcc libc6-dev \
         && cargo install --locked --version "${WORMHOLE_RS_VERSION}" magic-wormhole-cli \
         && install -m 0755 /root/.cargo/bin/wormhole-rs /usr/local/bin/wormhole-rs; \
       fi \
    && chmod 0755 /usr/local/bin/wormhole-rs

# Runtime stage: Bun runs the TypeScript sources directly, next to the built
# frontend. No .env file travels with the sources (see .dockerignore): a
# deployment is configured through environment variables only.
FROM oven/bun:1 AS runtime

RUN useradd --system --create-home --home-dir /app --shell /usr/sbin/nologin app

COPY --from=wormhole /usr/local/bin/wormhole-rs /usr/local/bin/wormhole-rs
RUN ln -s /usr/local/bin/wormhole-rs /usr/local/bin/wormhole

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

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD ["bun", "-e", "const r = await fetch(`http://127.0.0.1:${process.env.PORT ?? 8080}/api/ready`); if (!r.ok) process.exit(1)"]

ENTRYPOINT ["bun", "src/main.ts"]
