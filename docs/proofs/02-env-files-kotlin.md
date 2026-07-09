# `.env` Files in Kotlin/Ktor

## Result

A plain `.env` file is not read by Kotlin, Ktor, HOCON, or the Kotlin Toolchain by itself.

The current `backend/src/main/resources/application.conf` already supports environment overrides. It works when `DATABASE_JDBC_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`, `DATABASE_MAX_POOL_SIZE`, or `PORT` exist in the process environment before the JVM starts.

Recommendation for this repo: keep Ktor config environment-based and load local `.env` outside the app process. Prefer `direnv`, shell sourcing, IntelliJ's env-file support, or Docker Compose `env_file`. Avoid adding a dotenv runtime library unless there is a strong reason for the application itself to parse `.env`.

## Why The Current Config Works

Ktor `EngineMain` automatically loads an `application.*` file from resources. Ktor supports HOCON `application.conf` and says configuration values can be substituted with environment variables.

This repo uses the HOCON optional override pattern:

```hocon
jdbcUrl = "jdbc:postgresql://localhost:5432/voenix_shop"
jdbcUrl = ${?DATABASE_JDBC_URL}
```

Lightbend Config documents `${?VAR}` as optional system/env substitution. If the variable is absent, the override field disappears; if present, it replaces the previous value.

So the missing piece is not Kotlin code. The missing piece is loading `.env` into the process environment before:

```sh
cd backend
kotlin run
```

## Best Local Development Options

### 1. Shell source `.env`

Works everywhere a POSIX shell is available:

```sh
cd backend
set -a
source .env
set +a
kotlin run
```

Use when you want zero tooling and explicit launches.

### 2. `direnv`

Use a checked-in `backend/.envrc`:

```sh
dotenv_if_exists .env
```

Then:

```sh
direnv allow backend
cd backend
kotlin run
```

`direnv` loads approved per-directory environment into the shell. Its stdlib has `dotenv` and `dotenv_if_exists` helpers for `.env` files.

This is the best terminal workflow.

### 3. IntelliJ IDEA run config

JetBrains docs say Java run configurations can set env vars directly or load them from `.env` files/scripts via the Environment variables field.

This is the best IDE workflow.

### 4. Docker Compose

For containerized local dev:

```yaml
services:
  backend:
    env_file: backend/.env
```

Docker Compose passes those values into the container environment. Ktor then reads them normally through HOCON substitution.

## App-Level Dotenv Library Option

`io.github.cdimascio:dotenv-kotlin` is the main Kotlin/JVM dotenv library. Maven Central currently lists `6.5.1`.

Toolchain dependency shape:

```yaml
dependencies:
  - io.github.cdimascio:dotenv-kotlin:6.5.1
```

But there is an important Ktor timing issue. This repo uses `mainClass: io.ktor.server.netty.EngineMain`; `EngineMain` loads `application.conf` before `Application.module()` runs. Loading dotenv inside `module()` is too late for Ktor deployment config and may be too late for resolved config substitutions.

If using the library, add a custom main that runs before `EngineMain`:

```kotlin
package shop.voenix

import io.github.cdimascio.dotenv.dotenv
import io.ktor.server.netty.EngineMain

fun main(args: Array<String>) {
    dotenv {
        ignoreIfMissing = true
        systemProperties = true
    }

    EngineMain.main(args)
}
```

Then change `backend/module.yaml`:

```yaml
settings:
  jvm:
    mainClass: shop.voenix.MainKt
```

Reason for `systemProperties = true`: dotenv-kotlin says Java cannot set environment variables on the current process, so `.env` values are not visible through `System.getenv(...)`. Its system-properties mode exposes them through `System.getProperty(...)`; Lightbend Config supports system/env substitutions.

Use this only if external loading is not acceptable. It adds runtime dependency and app startup behavior for a local-development concern.

## Avoid

- Do not expect `backend/.env` to work just because it exists.
- Do not commit real `.env` secrets.
- Do not rely on `Application.module()` to load `.env` for `application.conf`.
- Do not include a standard `.env` file from HOCON. Simple `KEY=value` lines can look HOCON-like, but dotenv syntax and HOCON syntax are not the same, especially around quoting, comments, and URL-like values.

## Suggested Repo Convention

Commit:

```text
backend/.env.example
backend/.envrc
```

Ignore:

```text
backend/.env
backend/.env.*
!backend/.env.example
```

Example:

```dotenv
PORT=8080
DATABASE_JDBC_URL=jdbc:postgresql://localhost:5432/voenix_shop
DATABASE_USERNAME=voenix
DATABASE_PASSWORD=voenix
DATABASE_MAX_POOL_SIZE=5
```

## Sources

- Ktor configuration file docs: https://ktor.io/docs/server-configuration-file.html
- Lightbend Config optional env/system overrides: https://github.com/lightbend/config
- HOCON spec, environment substitutions/lists/includes: https://github.com/lightbend/config/blob/main/HOCON.md
- Java environment variables and `System.getenv`: https://docs.oracle.com/javase/tutorial/essential/environment/env.html
- dotenv-kotlin README: https://github.com/cdimascio/dotenv-kotlin/blob/master/README.md
- Maven Central dotenv-kotlin artifact: https://central.sonatype.com/artifact/io.github.cdimascio/dotenv-kotlin
- direnv docs: https://direnv.net/
- direnv stdlib `dotenv`: https://direnv.net/man/direnv-stdlib.1.html
- IntelliJ IDEA env-file support in run configs: https://www.jetbrains.com/help/idea/program-arguments-and-environment-variables.html
- Docker Compose `env_file`: https://docs.docker.com/compose/how-tos/environment-variables/set-environment-variables/
- Twelve-Factor config principle: https://12factor.net/config
