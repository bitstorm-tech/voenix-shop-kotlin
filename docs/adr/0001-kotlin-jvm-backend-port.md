---
status: accepted
date: 2026-07-09
---

# Choose Kotlin/JVM for the Backend Port

The backend port will target Kotlin/JVM, not Kotlin/Native, because the existing ASP.NET Core backend depends on mature server, database, PDF, image, payment, email, SFTP, and background-job capabilities that map far better to the JVM ecosystem.

The accepted stack is Ktor, Exposed SQL DSL, Flyway SQL migrations, Postgres/Testcontainers, kotlinx.serialization, coroutines with database-backed workers, Ktor sessions/auth with application-owned user rules, OpenHTMLToPDF plus PDFBox for document generation/inspection, and Scrimage for in-process WebP image resizing.

This decision is frozen because the JVM ecosystem covers the backend's required integration surface while Kotlin/Native would add avoidable library and deployment risk.

## Considered Options

- Kotlin/JVM with Ktor: accepted; mature library surface and deployable backend runtime.
- Kotlin/Native: rejected; higher integration risk for this backend's library surface.
- Spring Boot: rejected for this port; viable, but heavier than needed.
- Flyway vs Liquibase: Flyway accepted; SQL-first, small, and fits explicit reviewed migrations.
- Direct ASP.NET Identity translation: rejected; user/auth rules will be owned by the application.
- PDFBox-only documents: rejected for normal order/invoice layouts; too manual. Keep PDFBox as utility/inspection layer.
- In-memory-only background work: rejected; side effects need durable, idempotent database jobs.

## Consequences

- Production schema starts with fresh Flyway migrations.
- Exposed DDL helpers stay out of production; Flyway owns schema changes.
- EF Core behavior will be rewritten deliberately rather than translated one-to-one.
- Testcontainers Postgres is the persistence test baseline.
- External writes use explicit app-owned interfaces and idempotency keys.
- Real PDFs/images still need production fixture validation before porting full workflows.
- If OpenHTMLToPDF licensing is rejected, PDF choice must be reopened in a new ADR.
