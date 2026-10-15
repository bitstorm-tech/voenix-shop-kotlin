# The technology stack and why it was chosen

The backend is a Kotlin/JVM application. The choice was made in July 2026, when
the ASP.NET Core backend was ported to Kotlin, and it is settled: a proposal
that swaps one of these pieces needs a reason the comparison below did not
already weigh.

## What the backend is built from

| Concern | Choice |
| --- | --- |
| Runtime | Kotlin on the JVM |
| HTTP server | Ktor, with Ktor sessions and auth; the user and role rules belong to the application |
| Database access | Exposed, SQL DSL only |
| Schema changes | Flyway, plain SQL migrations |
| Database in tests | PostgreSQL in Testcontainers |
| JSON | kotlinx.serialization |
| Background work | coroutines plus database-backed jobs |
| PDF documents | OpenHTMLToPDF, with PDFBox to inspect and post-process |
| Images | Scrimage, in-process WebP resizing |

## Why the JVM and not Kotlin/Native

The old backend depended on mature server, database, PDF, image, payment,
email, SFTP, and background-job libraries. The JVM ecosystem covers all of them;
Kotlin/Native would have added library and deployment risk the port did not
need.

## The alternatives that were rejected

- **Spring Boot** — viable, but heavier than this backend needs.
- **Liquibase** instead of Flyway — Flyway is SQL-first and small, and fits
  explicit, reviewed migrations.
- **Translating ASP.NET Identity** — the user and auth rules are owned by the
  application instead.
- **PDFBox alone for documents** — far too manual for order and invoice
  layouts. PDFBox stays as the inspection layer.
- **In-memory background work** — a side effect must survive a restart, so
  every one of them is a durable, idempotent database job.

## What follows from it

- Flyway owns the schema. Exposed's DDL helpers never run in production.
- Every persistence test runs against a real PostgreSQL in Testcontainers.
- A write to an external system goes through an interface the application owns
  and carries an idempotency key.
- If the OpenHTMLToPDF licence is ever rejected, the PDF choice is open again.
