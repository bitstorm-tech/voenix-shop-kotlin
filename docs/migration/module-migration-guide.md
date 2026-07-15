# Migrating a backend module from .NET to Kotlin

This guide describes the default way to migrate a source feature from the
existing .NET backend into a module in this Kotlin backend. It is based on the
Country, VAT, Supplier, and Pricing migrations and, especially, on the cleanup
and module-boundary work that followed them.

The goal is not to reproduce a C# package in Kotlin. The goal is to preserve
the required behavior with a small, idiomatic Kotlin module that fits the
existing application.

This is the durable repository guide for future module migrations. A
module-specific migration record should link to it and add only the source
paths, source behavior, approved deviations, and unresolved decisions that are
specific to that module.

## Start a module migration

When Codex performs the migration, use the repo-local
`$migrate-dotnet-feature` skill. The skill name refers to the .NET source
feature; its target and durable record are Kotlin modules. The skill
orchestrates this guide and keeps the module record current; it does not
replace either document.

Copy [`migration-base.md`](migration-base.md) to
`docs/migration/<module>-migration.md`. Fill in the placeholders only in the
module-specific copy; do not edit the base for an individual migration.

The module file is both the task brief and the durable record of its approved
deviations, unresolved decisions, and deferred work. Keep general migration
rules in this guide. Change the base only when the task structure itself should
change for every future migration.

## The target shape

A normal CRUD-style module has one runtime module handle and may contain a
domain value, input, validator, operation interface, service, repository,
routes, and Exposed table. Apart from the runtime handle, this is a calibration
example, not a required layer template. Omit a type when it does not express a
distinct idea. Add one only when its meaning cannot be expressed clearly by an
existing type or a standard Kotlin type.

Country, VAT, and Supplier use thin routes, coordinating services, Exposed
repositories, and PostgreSQL-enforced invariants. The operation boundary
returns the shared
[`OperationResult<T>`](../dev/backend/operation-results.md). Routes do not leak
into services, and SQL details do not leak out of repositories.

The cleanup history shows why this is a calibration example rather than a
file template:

| Module | First committed migration | Current shape when this guide was written | Main cleanup |
| --- | ---: | ---: | --- |
| Country | 32 production files | 10 production files | Removed module-owned auth, custom HTTP compatibility, DTO wrappers, and duplicated request models |
| VAT | 11 production files | 10 production files | Reused the shared operation result and removed a thin transaction abstraction |
| Supplier | 11 production files | 9 production files | Removed list-only models, its operation result, and a speculative delete result |

The counts are evidence, not targets. VAT legitimately has `VatWrite`, while
Country does not need a matching `CountryWrite`. Supplier legitimately has a
module-specific `SupplierWriteResult`, while its old `SupplierDeleteResult`
was unnecessary. Symmetry is not a reason to add a data, result, repository, or
service type. The runtime module handle described below is the deliberate
exception because it gives every module the same composition boundary.

### Give every module one runtime handle

Every product module defines its runtime composition through three concepts:

- `XModule` is the assembled runtime handle. It owns the internal object graph
  and knows how to install that module's routes.
- `createXModule(...)` constructs the repository, service, and exported
  capabilities without exposing those details to the composition root.
- `Application.installXModule(...)` is the Ktor composition seam. It creates
  and installs the handle and returns only capabilities needed by another
  module, such as `CountryReader` or `VatReader`.

Keep the handle, factory, and their members at the narrowest visibility that
real consumers allow. Country and VAT expose public handles because other
compilation modules need their reader capabilities. Supplier and Pricing keep
their handles and factories `internal` because no caller needs the assembled
instance. A public operation overload of `installXModule` may remain as a route
test seam without exposing the production object graph.

A thin runtime handle is still meaningful: it is the stable assembly and
installation boundary. This exception does not justify pass-through DTOs,
duplicate result types, or matching layers that carry no independent meaning.

The `platform` compilation module deliberately has no single `PlatformModule`.
It contains independent foundations that should stay independently testable.
A cohesive stateful concern may have its own runtime handle: `AuthModule`
captures `AuthSettings` and installs Sessions, Authentication, renewal, and
antiforgery behavior. `HttpRuntime`, `DatabaseFactory`, validation, and shared
result types retain their separate interfaces.

## The most important rule: migrate behavior, not source types

A C# class is evidence, not a requirement for a Kotlin class.

Before creating a Kotlin type, ask:

1. Does it have meaning that differs from an existing Kotlin type?
2. Does it preserve required fields, metadata, or behavior?
3. Does it create a useful boundary rather than a pass-through mapping?
4. Would deleting it lose information or make the code harder to understand?

If every answer is no, do not create the type.

This rule would have prevented several repeated cleanups:

- Country, VAT, and Supplier originally had module-specific operation result
  types. Commit `5b3ac12` replaced them with the shared `OperationResult`.
- Supplier originally had `SupplierListResponse` and `SupplierListItem`.
  Commit `ea294eb` removed both and returned `List<Supplier>` directly.
- Supplier briefly had `SupplierDeleteResult`. Commit `b29b969` removed it and
  used Exposed's affected-row count.

The Supplier migration prompt already said to return collections directly and
avoid speculative DTOs. The implementation still introduced the two list-only
types. Written principles therefore are not enough: the type map and public
payloads must be reviewed explicitly before the module is accepted.

### Classify source behavior before preserving it

Use these four classifications during analysis:

| Classification | Meaning |
| --- | --- |
| Required | Intentional business behavior, a confirmed client contract, a required dependency, or meaningful behavior supported by production code or tests |
| Proposed deviation | An intentional observable change that needs explicit approval |
| Incidental | Behavior produced by ASP.NET Core, Entity Framework, serializers, providers, or other implementation details |
| Unclear | Available evidence does not establish whether the behavior is intentional |

Calculations, validation, normalization, authorization, persistence semantics,
and meaningful outcomes owned by the source feature are presumed required
until evidence shows otherwise. Source tests are evidence, but assertions about
framework messages, parser positions, trace IDs, exception types, serializer
quirks, or private source structure may be incidental.

An unclear behavior blocks implementation only when it can materially affect
business data, security, schema compatibility, transactions, concurrency, a
known client, or another module. For minor framework behavior, use the
idiomatic Kotlin default and record the difference.

Capture the result in the migration analysis:

| Behavior | Evidence | Classification | Kotlin approach | Verification |
| --- | --- | --- | --- | --- |
| `<source behavior>` | `<source, test, client, or decision>` | `<classification>` | `<planned implementation>` | `<test or check>` |

## Decide the external contract before designing types

Create a small contract table before implementation:

| Operation | Required input | Required success value | Required errors | Ordering |
| --- | --- | --- | --- | --- |
| List | none or filters | direct list or justified wrapper | expected failures | explicit order |
| Get | identifier | stored/public representation | not found | n/a |
| Create | input | created representation | invalid, conflict | n/a |
| Update | identifier and input | updated representation | invalid, not found, conflict | n/a |
| Delete | identifier | no body | not found or domain conflict | n/a |

For each row, record the evidence in the migration analysis. Evidence may come
from production code, meaningful tests, an existing client, or approved
documentation.

Do not preserve framework-generated details such as ASP.NET exception names,
model-binding internals, trace IDs, provider messages, or Entity Framework
mechanics.

### Compatibility checkpoint

Standard Ktor behavior is the default. Stop for approval before implementing a
custom JSON parser or serializer, route matcher, content-type matcher,
framework compatibility adapter, HTTP error hierarchy, or schema-repair
mechanism.

Before asking for approval, state:

1. the required behavior and its evidence;
2. the known client or operational dependency;
3. the idiomatic Kotlin alternative;
4. the observable difference; and
5. the implementation and maintenance cost.

Country initially copied case-insensitive routes, optional trailing slashes,
ASP.NET-shaped errors, parser diagnostics, and trace behavior. Those features
created a large compatibility layer and were later removed. Do not repeat that
work unless a real consumer requires it.

### Collections

Use standard Kotlin collections at operation boundaries:

```kotlin
interface ProductOperations {
    suspend fun list(): OperationResult<List<Product>>
}
```

This usually becomes a direct JSON array:

```json
[
  { "id": 1, "name": "Example" }
]
```

Do not add `ProductList`, `ProductListResult`, or
`ProductListResponse(items)` only to wrap `List<Product>`.

A wrapper is justified when it carries required information such as pagination
metadata, an aggregate, a cursor, or links:

```kotlin
@Serializable
data class ProductPage(
    val items: List<Product>,
    val nextCursor: String?,
)
```

Changing `{ "items": [...] }` into `[...]` is observable. Do it only when the
contract analysis confirms that the wrapper is not required or when the
deviation is approved. Supplier deliberately chose the direct array and records
the remaining frontend work in
[`supplier-post-migration.md`](supplier-post-migration.md).

### Representations

Reuse one representation for list, detail, create, and update responses when
its shape and meaning are the same.

Create a separate representation only for a real difference. Country, for
example, has `Country` for administration and `PublicCountry` for the public
contract. Supplier uses `Supplier` everywhere because a list-only projection
did not carry required independent meaning.

Do not copy a source DTO distinction merely because the .NET package has
separate class names.

### Inputs

Share create and update input types when their fields, validation, and
replacement semantics are the same:

```kotlin
@Serializable
data class ProductInput(
    val name: String? = null,
    val description: String? = null,
)
```

Use separate input types only when create and update genuinely allow different
operations or fields. Document whether `PUT` replaces omitted optional values
with `null` or preserves stored values.

## Use the shared operation result

Module operation interfaces return the shared
[`OperationResult<T>`](../../backend/modules/platform/src/shop/voenix/operation/OperationResult.kt).
Its common variants are `Success`, `Invalid`, `NotFound`, `Conflict`, and
`UnexpectedFailure`.

Do not create `ProductResult<T>` with the same variants. That duplicates a
cross-module concept, increases route code, and makes later consolidation
costly.

The repository describes persistence outcomes, the service maps them to
`OperationResult`, and the route maps that result to HTTP. Do not put Ktor
response types or status codes in `ProductOperations`.

### When a module-specific write result is useful

An internal `ProductWriteResult` is useful when one write can produce several
meaningful persistence outcomes:

```kotlin
internal sealed interface ProductWriteResult {
    data class Stored(val product: Product) : ProductWriteResult
    data object NotFound : ProductWriteResult
    data object Conflict : ProductWriteResult
    data object CategoryNotFound : ProductWriteResult
}
```

It keeps SQL-state handling inside persistence while allowing the service to
map `CategoryNotFound` to a field error and `Conflict` to the shared conflict
result.

Do not add a write result automatically. It is not useful when the repository
has only one normal return value and no expected alternative outcome.

### Deletes normally do not need their own result type

For a simple delete, return the affected-row count from the repository:

```kotlin
suspend fun delete(id: Long): Int =
    transaction {
        Products.deleteWhere { Products.id eq id }
    }
```

Map it in the service:

```kotlin
if (repository.delete(id) == 0) {
    OperationResult.NotFound
} else {
    OperationResult.Success(Unit)
}
```

Create a typed delete outcome only when deletion has additional real domain
outcomes, for example `InUse`, and those outcomes cannot be represented safely
by the row count. Do not add a placeholder outcome before its real relationship
exists.

Supplier's future `InUse` behavior is intentionally deferred until the Article
relationship is migrated. This is module-specific and must not be generalized
into every delete operation.

## Validation and normalization

Every validation rule has exactly one implementation.

Public validation signatures use the shared `ValidationErrors` alias. It names
the `Map<String, List<String>>` field-error shape without introducing a wrapper
or changing JSON serialization.

The normal flow is:

1. Ktor's shared request validation calls the input's pure `validate()`
   method.
2. The service calls the same input method for non-HTTP callers.
3. The service returns `OperationResult.Invalid` when errors exist.
4. The service normalizes only valid input.
5. The repository receives only valid, normalized values.

```kotlin
val errors = input.validate()
if (errors.isNotEmpty()) return OperationResult.Invalid(errors)

val normalized = input.copy(
    name = checkNotNull(input.name).trim(),
    description = input.description?.trim()?.ifBlank { null },
)
```

Routes must not copy field conditions. The input method may be used at two
seams, but there is still only one implementation of each rule.

Do not normalize first. Trimming before validation can accidentally change
which value was checked and which error should be reported.

## Reuse shared Ktor and security infrastructure

A product module must not independently install application-wide plugins such
as Content Negotiation, StatusPages, Authentication, Sessions, or
RequestValidation.

The application composition root owns startup order and installs every shared
concern through its established seam. `HttpRuntime` installs Content
Negotiation and StatusPages, `AuthModule` installs Sessions and Authentication,
and the composition root installs RequestValidation once while registering
module input types. A product module provides its input, pure validator,
runtime handle, installation function, and routes.

For admin routes, protect the route subtree with the auth-owned,
fail-closed policy. Do not repeat authentication, role, and CSRF checks in
individual handlers. Tests must prove that rejected requests cannot invoke the
module operation.

Before adding a helper in a module package, search the existing application
for the same responsibility. Country's auth and validation cleanup showed that
shared application policy should not be rediscovered for each migration.

## Persistence decisions

Use Exposed for database access and Flyway for production schema ownership.
Exposed must not create or alter the production schema at runtime.

### Preserve the invariant, not the Entity Framework mechanism

For every source index, constraint, relationship, and transaction, record the
purpose:

- required business invariant;
- concurrency guarantee;
- foreign-key relationship and delete behavior;
- demonstrated query or ordering need;
- incidental source-framework artifact.

Only the first four normally belong in the Kotlin migration. Exact source
constraint names are not contracts unless an external operational dependency
requires them.

The default deployment path is a clean PostgreSQL database built by Flyway.
Do not add automatic adoption, repair, or validation of an existing Entity
Framework schema unless that deployment path is explicitly required and
approved. Country initially added schema-adoption infrastructure; VAT later
made the clean-database decision explicit.

Do not create placeholder tables, columns, or foreign keys for a module that
has not been migrated. Record the required relationship in the deviation log
and add it together with the owning module. Supplier, for example, deliberately
deferred its Article relationship instead of creating a partial Article table.

### Let PostgreSQL enforce concurrent invariants

A preliminary lookup is not sufficient protection against concurrent writes.
Use a database unique rule when uniqueness is required.

Repositories use the shared
[`executePostgresWrite`](../../backend/modules/platform/src/shop/voenix/db/PostgresWrite.kt)
helper to map expected PostgreSQL SQL states to typed write outcomes:

```kotlin
executePostgresWrite(
    uniqueViolation = ProductWriteResult.Conflict,
    foreignKeyViolation = ProductWriteResult.CategoryNotFound,
) {
    // Exposed insert or update transaction
}
```

The current shared mappings are:

| SQL state | Meaning | Map only when |
| --- | --- | --- |
| `23505` | unique violation | every applicable unique failure has the declared generic outcome |
| `23503` | foreign-key violation | the write has one unambiguous missing-reference outcome |

Do not inspect constraint names, index names, or localized database messages.
Do not expose them to services or clients. Undeclared SQL states must be
re-thrown.

The Supplier foreign-key mapping is safe today because create and update have
one foreign-key reference. If a later module write can violate several
different foreign keys, SQL state `23503` alone cannot identify the missing
field. That case needs a deliberate design rather than a misleading generic
mapping.

Module-specific transaction policy stays in the module repository. VAT's
serializable transaction and retry policy protect movement of the default VAT
entry; they are not a template for ordinary CRUD writes.

### Use Exposed transactions directly

JDBC is blocking even when Exposed exposes a suspending transaction API. Move
the blocking section to `Dispatchers.IO` and call Exposed directly:

```kotlin
suspend fun list(): List<Product> =
    withContext(Dispatchers.IO) {
        suspendTransaction(db = database, readOnly = true) {
            maxAttempts = 1
            Products.selectAll().map(::toProduct)
        }
    }
```

`suspend` does not make the query parallel. It lets the Ktor request coroutine
wait without keeping its request-dispatcher thread occupied by JDBC.

Do not create a shared wrapper that merely renames or forwards Exposed's
transaction arguments. VAT briefly introduced such an abstraction and removed
it again. A local helper is useful when it names and enforces a real module
policy, such as VAT's serializable isolation and retry count. Move that helper
to shared infrastructure only after another module needs the same policy for
the same reason.

### Unexpected failures and cancellation

Expected domain outcomes use result values. Unexpected SQL failures are logged
by the service and become `OperationResult.UnexpectedFailure`.

Always rethrow `CancellationException`. Cancellation controls coroutine
lifecycle and must not become a normal application failure.

## Stop conditions

Stop and report before implementation when:

- authoritative sources contradict each other in a way that materially affects
  required behavior;
- an ambiguity affects business data, security, schema compatibility,
  transactions, concurrency, external behavior, or a known client;
- an observable behavior change is necessary but has not been approved;
- the compatibility checkpoint requires custom infrastructure;
- application-wide infrastructure would have to be hidden inside a product module;
- shared infrastructure would require module-specific runtime type dispatch;
- required behavior needs an incompatible schema change; or
- an external dependency has no reasonable Kotlin equivalent.

Minor framework differences, unfamiliar source structure, or a high but
justified type count are not stop conditions. Use the guide's defaults and
record the decision.

## Migration workflow

### 1. Analyze before editing

- Read the source production code, meaningful tests, clients, and migrations.
- Read the target module conventions and shared infrastructure.
- Classify behavior as required, proposed deviation, incidental, or unclear.
- Create the operation contract table.
- Record validation, normalization, authorization, ordering, transactions,
  concurrency, relationships, and seed data.
- Identify material ambiguities. Stop for approval when they affect data,
  security, transactions, schema compatibility, or a known client.

### 2. Propose the Kotlin design

- Write the planned production file and type map.
- Explain every response wrapper and every representation split.
- Mark which standard collections and shared result types are reused.
- Define the operation interface before routes and repository details.
- Define `XModule`, `createXModule`, `installXModule`, their visibility, and any
  capability returned to another module.
- Identify application-composition and Flyway changes.
- Define how every required behavior will be verified.

More than 12 production types is a review signal, not permission for 12 types.
Supplier's unnecessary list types kept it below that threshold, so a number
alone does not catch shallow abstractions. Apply the deletion test to every
type even in a small package.

### 3. Implement the vertical slice

- Add the Flyway migration and Exposed table.
- Implement repository reads, writes, ordering, and transaction policy.
- Implement the pure validator and service normalization.
- Map repository outcomes to `OperationResult`.
- Add thin routes using shared HTTP and auth infrastructure.
- Assemble the object graph in `createXModule` and install it through
  `Application.installXModule` at the existing composition seam.
- Keep exactly one top-level Kotlin type per file and name the file after it.

### 4. Perform the post-migration simplification review

Do this before calling the migration complete:

- Search for `ListResponse`, `ListResult`, `ListItem`, `DeleteResult`, and the
  module name followed by `Result`.
- For every match, state the independent meaning that justifies it.
- Compare list and detail fields. Merge their models when no required semantic
  difference remains.
- Check that operation interfaces use `OperationResult` and standard Kotlin
  collections.
- Check that simple deletes return a row count rather than a two-variant sealed
  type.
- Search for copied auth, CSRF, JSON, StatusPages, and validation setup.
- Search for constraint-name and message inspection.
- Review every transaction wrapper and keep it only when it enforces a named policy.
- Keep the runtime module handle even when it is thin; verify instead that it
  owns assembly or installation and does not expose the internal object graph.
- Search for schema-adoption or compatibility code that no approved deployment path needs.
- Confirm that every TODO is either resolved or in the deviation log.
- Update the module documentation in `docs/dev`.

### 5. Run the migration retrospective

Do this after verification and simplification but before the final completion
report:

- Compare the original behavior matrix, operation contract, type map, and test
  plan with the final code and verification.
- Inspect concrete evidence: test failures, late consumer discoveries, review
  findings, types or infrastructure removed during simplification, repeated
  manual work, environmental blockers, and decisions that arrived late.
- Ask what could have surfaced each material finding earlier and whether that
  signal can be made repeatable.
- Complete the `Migration retrospective` section in the module migration
  record. Record `No reusable process finding` when that is the honest result.
- Route and promote findings using the rules below. Do not create a separate
  learning log.

## Tests and quality gate

Rewrite meaningful behavior tests in Kotlin. Do not translate the .NET test
class structure mechanically.

For an applicable module, cover:

- successful list, get, create, update, and delete flows;
- exact required ordering and stable tie-breaking;
- the complete field-rule matrix once in validator tests;
- HTTP rejection before operation invocation;
- direct service validation and normalization before persistence;
- not-found and domain-conflict outcomes;
- authentication, role, and CSRF subtree wiring;
- transaction rollback and module-specific isolation behavior;
- normal duplicate writes and concurrent duplicate writes;
- expected foreign-key failures and rollback;
- rethrowing or hiding unexpected database failures as appropriate;
- Flyway migration on an empty PostgreSQL database.

Use PostgreSQL through Testcontainers when PostgreSQL behavior matters. An
in-memory substitute cannot prove SQL states, partial indexes, isolation, or
concurrency behavior.

Run backend commands from `backend/` with the repository's Kotlin Toolchain:

```sh
./kotlin do ktfmt
./kotlin check
./kotlin do ktfmt
```

`./kotlin check` must pass tests, ktfmt, Ktlint, and Detekt. The final formatter
run must produce no further changes. Do not add baselines or broad suppressions
to make the gate pass.

## Migration completion checklist

- [ ] Every required behavior has evidence and a verification.
- [ ] Every observable deviation has explicit approval or remains unresolved.
- [ ] Lists use `List<T>` unless required metadata justifies a wrapper.
- [ ] Representations differ only where shape or meaning differs.
- [ ] Create and update inputs are shared when their rules are identical.
- [ ] Operations return the shared `OperationResult<T>`.
- [ ] Module write results exist only for meaningful persistence outcomes.
- [ ] Simple deletes use the affected-row count.
- [ ] Validation rules have one implementation.
- [ ] Normalization happens only after successful validation.
- [ ] Routes use shared Ktor, validation, and auth infrastructure.
- [ ] `XModule`, `createXModule`, and `installXModule` form the module's runtime
  composition boundary with the narrowest useful visibility and return only
  required cross-module capabilities.
- [ ] PostgreSQL owns concurrency-safe invariants.
- [ ] SQL mappings use declared SQL states, never schema object names or messages.
- [ ] Undeclared database failures are not silently converted to expected results.
- [ ] `CancellationException` is rethrown.
- [ ] Flyway owns the production schema.
- [ ] Existing-schema compatibility exists only when an approved deployment path requires it.
- [ ] Transaction helpers enforce a real policy rather than only forwarding Exposed arguments.
- [ ] PostgreSQL integration tests cover relevant constraints and concurrency.
- [ ] The post-migration simplification review found no unjustified types.
- [ ] The migration retrospective records findings or explicitly records that
  none were reusable.
- [ ] Qualifying process improvements were applied or remain documented with
  an approval owner.
- [ ] Module documentation and the deviation log are current.
- [ ] `./kotlin check` passes and formatting is stable.

## Improve future migrations from findings

Keep the raw evidence in the module migration record. Promote only the part
that will make another migration more reliable, simpler, or faster.

| Finding scope | Destination |
| --- | --- |
| Source behavior, consumer dependency, or deferred relationship | Module migration record or its post-migration file |
| Missing workflow step, phase transition, or source-routing instruction | `$migrate-dotnet-feature` skill |
| Missing parameter, analysis artifact, or completion field needed by every migration | `migration-base.md` |
| Reusable Kotlin, Ktor, validation, persistence, testing, or architecture default | This guide |
| Always-on backend invariant outside the migration workflow | The nearest applicable `AGENTS.md` |
| Repeated deterministic check that judgment cannot perform reliably | A skill script or repository quality gate |

Apply these promotion rules:

1. Require concrete evidence from the completed migration. Preference,
   aesthetics, and incidental framework behavior are not sufficient.
2. Promote after one occurrence only when the finding exposes a clearly
   general security, authorization, data-integrity, transaction, concurrency,
   or deterministic-verification gap. For normal design heuristics, require two
   independent migrations or equally strong repository-wide evidence.
3. Apply low-risk clarifications, missing references, and source-routing fixes
   directly when they do not change semantics.
4. Obtain Joe's approval before changing architecture defaults, external
   contract policy, stop conditions, compatibility policy, or quality gates.
   Record the proposed change and approval owner until it is decided.
5. Change the smallest authoritative source. Do not duplicate the same rule in
   the skill, base, guide, and `AGENTS.md`.
6. Reference the module and evidence behind every promoted rule. Merge or
   remove obsolete guidance rather than only appending more instructions.
7. When changing the skill, follow `$skill-creator`, keep `SKILL.md` concise,
   verify `agents/openai.yaml`, and validate the skill after editing.

## Deviation and uncertainty log

Keep this table in the module's migration analysis or a dedicated file under
`docs/migration`. Do not hide an unresolved decision in a code TODO.

| Behavior or contract | Source evidence | Kotlin behavior | Classification | Approval or owner | Follow-up |
| --- | --- | --- | --- | --- | --- |
| Example list wrapper | Existing client reads `items` | Proposed direct array | Proposed deviation | Pending | Decide before route implementation |

The log should include:

- observable payload or status differences;
- unresolved source contradictions;
- deferred relationships and domain outcomes;
- compatibility behavior intentionally not implemented;
- environmental verification that could not be run;
- work intentionally assigned to a later module.

Supplier provides a useful example: its direct list array is intentional and
its frontend adaptation is recorded, while the future Article relationship and
`InUse` delete result remain deferred until that relationship really exists.

## Known limits of the current rules

These decisions are established defaults, not universal truths:

- `OperationResult` contains the common outcomes needed by the first three
  modules. A genuinely new cross-module outcome needs design review; do not
  force it into `Conflict` merely to avoid changing the shared type.
- `executePostgresWrite` can identify SQL-state categories, not which of several
  constraints failed. Multiple foreign keys or distinct unique-error contracts
  may require a narrower persistence design.
- Direct lists are the default for simple endpoints, but pagination and
  required client metadata justify wrappers.
- A typed delete result becomes useful when real relationships create more than
  deleted-versus-missing behavior.
- Transaction isolation and retry policies are module-specific. Copy VAT's
  policy only when the same concurrency problem exists.

## Evidence behind this guide

The main post-migration changes are visible in Git:

| Commit | Finding |
| --- | --- |
| `d5bdc60` | Moved module-owned authentication and shared HTTP behavior out of Country |
| `d5748f6` | Removed Country's custom .NET HTTP compatibility, list wrappers, and duplicated request models |
| `017ff4a` | Established one pure Country validator reused by HTTP and service callers |
| `6b83b36` | Removed unused existing-schema adoption and compatibility checks |
| `ea294eb` | Removed Supplier list wrapper and list-item types; returned `List<Supplier>` |
| `a55c3b3` | Mapped Supplier's expected foreign-key violation inside persistence |
| `9b5344e` | Generalized expected PostgreSQL write-error mapping, now exposed as `executePostgresWrite` |
| `5b3ac12` | Replaced Country, VAT, and Supplier operation result types with `OperationResult` |
| `b29b969` | Removed the unnecessary Supplier delete result type |
| `f389eeb` | Renamed Kotlin runtime composition from Feature to Module and added consistent handles for Supplier and Pricing |
| `b278e69` | Introduced a cohesive `AuthModule` runtime handle without creating a broad `PlatformModule` |

The current implementation and detailed explanations are in:

- [`operation-results.md`](../dev/backend/operation-results.md);
- [`persistence-error-handling.md`](../dev/backend/persistence-error-handling.md);
- [`country-package.md`](../dev/backend/country-package.md);
- [`vat-package.md`](../dev/backend/vat-package.md);
- [`supplier-package.md`](../dev/backend/supplier-package.md);
- [`module-architecture.md`](../dev/backend/module-architecture.md); and
- [`authentication-and-authorization.md`](../dev/backend/authentication-and-authorization.md).

The historical Codex tasks used to identify recurring review themes include
Country migration and simplification (`019f4b92-2afa-7771-922f-3c3ebf95e7fa`,
`019f55b9-13b5-7321-a4c4-322b9e805b19`), VAT migration and query/result cleanup
(`019f57cf-4d67-7550-8f17-6bc72547a2af`,
`019f5877-08f1-7b41-a07e-f829a08790b4`), and Supplier migration and result/list
cleanup (`019f5f59-84a1-7ad2-a2f7-848004739ca7`,
`019f601c-a48b-7832-bbb4-369c6b69643f`,
`019f605c-9501-7c93-ad49-e56c36fbaf08`,
`019f6093-30a4-7b50-8360-a0a4624e602f`). The Git history and current code are
the authoritative evidence for the final rules documented here.
