# Database, Migrations, and Tests

## Result

The Kotlin persistence baseline is proven with:

- Ktor database config loaded from `application.conf` and environment overrides.
- Hikari-backed Postgres connection.
- Flyway SQL migration under `backend/resources/db/migration`.
- Exposed SQL DSL mapping for a customer aggregate with nullable fields and a related order.
- Testcontainers Postgres integration test path.

No production feature slice was ported; the tables are proof tables only.

## Migration Tool Recommendation

Use Flyway by default for the Kotlin port.

Flyway fits this project because it is SQL-first, small, predictable, and easy to run from Ktor startup and Testcontainers tests. That matches the likely porting need: keep schema changes explicit while Exposed owns query mapping, not production DDL.

Liquibase remains viable if the project needs stronger migration governance: declarative changelogs, labels/contexts, preconditions, and richer rollback workflows. Its cost is extra ceremony and a larger tool surface for a focused Ktor + Exposed port.

Decision: start with Flyway. Revisit only if rollback governance or multi-environment changelog controls become real requirements.

## EF Core Behaviors To Decide Explicitly

- Schema ownership: Flyway migrations only; Exposed DDL helpers should stay out of production.
- Nullability/defaults: align Kotlin nullable types, DB nullability, and generated defaults deliberately.
- Relations/loading: replace EF `Include` patterns with explicit joins or repository queries; watch N+1 behavior.
- Cascades: map EF `DeleteBehavior` to DB cascades or application deletes case by case.
- Transactions: define service/request transaction boundaries; do not assume EF unit-of-work semantics.
- Change tracking: Exposed updates are explicit; no EF-style tracked entity graph.
- Concurrency: choose row version, timestamp, or manual optimistic locking where existing EF code used concurrency tokens.
- Value conversions/enums: replace EF converters with Kotlin value classes/enums and explicit SQL column choices.
- Query filters: soft delete, tenant filters, or global filters need explicit repository support.
- Tests: Testcontainers Postgres should be the persistence truth; avoid H2 parity traps.

For the proof schema, `orders.customer_id` uses the database default delete behavior instead of opting into cascade. That keeps cascade policy open for the real EF Core port decision.
