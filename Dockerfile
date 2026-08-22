# The whole application as one image: the Kotlin backend serving the built Vue
# frontend. Build context is the repository root. This is the image a production
# deployment (for example Render.com) runs; backend/Dockerfile builds the
# backend-only image the verification stack uses.
# See docs/dev/getting-started/full-stack-image.md.

# Frontend build stage: `bun run build` type-checks with vue-tsc and bundles
# with Vite into dist/.
FROM oven/bun:alpine AS frontend-build

WORKDIR /src
# Dependencies first, so a source-only change reuses this layer.
COPY frontend/package.json frontend/bun.lock ./
RUN bun install --frozen-lockfile
COPY frontend/ ./
RUN bun run build

# Backend build stage, identical to the one in backend/Dockerfile: the Kotlin
# Toolchain wrapper (./kotlin) provisions its own CLI distribution and its own
# JDK 25, so this stage only needs a JVM to start the wrapper plus network
# access. `:app:executableJarJvm` is the toolchain's fat JAR task — one file
# with every dependency and the Ktor EngineMain entry point.
FROM eclipse-temurin:25-jdk AS backend-build

ENV KOTLIN_CLI_NO_WELCOME_BANNER=1 \
    KOTLIN_CLI_JAVA_HOME=/opt/java/openjdk \
    KOTLIN_CLI_BOOTSTRAP_CACHE_DIR=/kotlin-cli-cache

# The wrapper downloads its distribution with curl or wget; the base image has
# neither.
RUN apt-get update \
    && apt-get install --no-install-recommends --yes curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /src
COPY backend/ .
# Cache mounts keep the toolchain distribution and the Maven dependencies out of
# the image layers and make a rebuild after a source change fast.
RUN --mount=type=cache,target=/kotlin-cli-cache \
    --mount=type=cache,target=/root/.m2 \
    ./kotlin task :app:executableJarJvm

# Runtime stage: a JRE, the JAR, and the built frontend. Not Alpine — image
# processing (scrimage, webp-imageio) loads glibc-linked native libraries.
FROM eclipse-temurin:25-jre AS runtime

RUN useradd --system --create-home --home-dir /app --shell /usr/sbin/nologin app

# magic-wormhole: files can be sent to and from the running container (for
# example to pull a database dump or logs off a deployed instance).
RUN apt-get update \
    && apt-get install --no-install-recommends --yes magic-wormhole \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=backend-build --chown=app:app \
    /src/build/tasks/_app_executableJarJvm/app-jvm-executable.jar app.jar
# The base defaults live inside the JAR as a classpath resource, but Ktor's
# -config takes file paths, and passing any -config replaces the classpath
# default instead of merging with it. So the base file is copied in as a file
# and layered explicitly, exactly as scripts/start-dev-server.sh does it.
COPY --from=backend-build --chown=app:app /src/app/resources/application.yaml application.yaml
COPY --from=backend-build --chown=app:app /src/application-container.yaml application-container.yaml
# The layer that makes this the full-stack image: it points frontend.distPath at
# the directory the next line fills.
COPY --from=backend-build --chown=app:app /src/application-fullstack.yaml application-fullstack.yaml
COPY --from=frontend-build --chown=app:app /src/dist frontend/dist

# Image and PDF storage default to ./data, resolved against this directory. The
# directories are baked in with the right owner: when Docker creates a missing
# bind-mount target itself, every created parent belongs to root, and the app
# user could no longer create the sibling directories next to it.
RUN mkdir -p data/images/public data/images/private data/images/cache \
        data/production/artifacts \
    && chown -R app:app data
USER app
EXPOSE 8080

# --enable-native-access: Netty loads a native library; without the flag the JVM
# prints a restricted-method warning on every start.
ENTRYPOINT ["java", "-Djava.awt.headless=true", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/app.jar", \
    "-config=/app/application.yaml", "-config=/app/application-container.yaml", \
    "-config=/app/application-fullstack.yaml"]
