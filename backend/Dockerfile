# The backend as a self-contained image. Build context is backend/.
#
# Build stage: the Kotlin Toolchain wrapper (./kotlin) provisions its own CLI
# distribution and its own JDK 25, so this stage only needs a JVM to start the
# wrapper plus network access. `:app:executableJarJvm` is the toolchain's fat
# JAR task — one file with every dependency and the Ktor EngineMain entry point.
FROM eclipse-temurin:25-jdk AS build

ENV KOTLIN_CLI_NO_WELCOME_BANNER=1 \
    KOTLIN_CLI_JAVA_HOME=/opt/java/openjdk \
    KOTLIN_CLI_BOOTSTRAP_CACHE_DIR=/kotlin-cli-cache

# The wrapper downloads its distribution with curl or wget; the base image has
# neither.
RUN apt-get update \
    && apt-get install --no-install-recommends --yes curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /src
COPY . .
# Cache mounts keep the toolchain distribution and the Maven dependencies out of
# the image layers and make a rebuild after a source change fast.
RUN --mount=type=cache,target=/kotlin-cli-cache \
    --mount=type=cache,target=/root/.m2 \
    ./kotlin task :app:executableJarJvm

# Runtime stage: a JRE and the JAR, nothing else. Not Alpine — image processing
# (scrimage, webp-imageio) loads glibc-linked native libraries.
FROM eclipse-temurin:25-jre AS runtime

RUN useradd --system --create-home --home-dir /app --shell /usr/sbin/nologin app
WORKDIR /app

COPY --from=build --chown=app:app \
    /src/build/tasks/_app_executableJarJvm/app-jvm-executable.jar app.jar
# The base defaults live inside the JAR as a classpath resource, but Ktor's
# -config takes file paths, and passing any -config replaces the classpath
# default instead of merging with it. So the base file is copied in as a file
# and layered explicitly, exactly as scripts/start-dev-server.sh does it.
COPY --from=build --chown=app:app /src/app/resources/application.yaml application.yaml
COPY --from=build --chown=app:app /src/application-container.yaml application-container.yaml

# Image and PDF storage default to ./data, resolved against this directory.
USER app
EXPOSE 8080

# --enable-native-access: Netty loads a native library; without the flag the JVM
# prints a restricted-method warning on every start.
ENTRYPOINT ["java", "-Djava.awt.headless=true", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/app.jar", \
    "-config=/app/application.yaml", "-config=/app/application-container.yaml"]
