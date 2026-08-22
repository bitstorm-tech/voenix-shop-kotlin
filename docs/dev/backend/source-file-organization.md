# Kotlin source file organization

This guide explains how Kotlin declarations are grouped into files in the
backend. It replaces the earlier rule of exactly one top-level type per file.

## The principle

We follow the official
[Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html#source-file-organization):

> Placing multiple declarations (classes, top-level functions or properties) in
> the same Kotlin source file is encouraged as long as these declarations are
> closely related to each other semantically, and the file size remains
> reasonable.

A file is a cohesive unit of the domain, not a container for a single type.
Small value types, sealed result hierarchies, and helper declarations live in
the file of the component they belong to. A reader should be able to open one
file and understand one complete concern.

## The standard module shape

A typical module `x` groups its declarations like this:

| File | Contents |
| --- | --- |
| `X.kt` | The central domain type(s) plus the small value types that belong to them. |
| `XRoutes.kt` | The `Application.installXRoutes(...)` extension that installs the routes, plus the request and response types only the HTTP layer uses, including their validation. |
| `XService.kt` | The service, the use-case seam interface it implements, and the sealed result types its operations return. |
| `XRepository.kt` | The repository, the Exposed table object(s) it owns, the internal write/read value types, and the sealed result types persistence returns. |
| `XModule.kt` | The runtime handle (the constructed dependencies and the exported capabilities, no Ktor wiring), `createXModule`, and `installXModule`, which installs the routes and returns the capability. |

This is a default, not a straitjacket. The deciding question is always: *which
component produces or owns this type?* Put the type in that component's file.

Where the Ktor wiring lives is a rule, not a preference. A top-level
`Application.install...` function owns all of it, never the handle. Normally
that is `Application.installXModule(...)`: it calls `createXModule(...)`,
installs the module's routes with the internal `installXRoutes(...)`
installers, registers whatever the module needs from the application lifecycle
(`monitor.subscribe(ApplicationStopped) { … }`), and returns the capability the
composition root asked for. (A module gets a second install function only when
a dependency does not exist yet at that point of the composition. The image
module's `installGuestImageRoute` is one.) A handle therefore never has an
`install(application)` member. A member that only forwards to the route
installers is one indirection with nothing behind it. The one exception is a
module that starts a background worker: its handle keeps a
`startWorker(application)` member, because it has to remember the launched
`Job` to cancel it on shutdown and to refuse a second start. Today that is
Email and Production, and nothing else.

## When a type still gets its own file

Give a declaration its own file when:

- it is large enough to be a concern of its own (as a rough feel, more than
  150 to 200 lines), or
- it is shared equally by several components so no single file is its natural
  owner, or
- it is a public cross-module seam that readers look up by name.

## What a file must not become

- No grab-bag files. Names like `Utils.kt`, `Types.kt`, `Models.kt`, or
  `Dtos.kt` signal that the contents are grouped by *kind* instead of by
  *meaning*. Group by meaning.
- No mega-files. When a file stops fitting one concern, split it along the
  concern boundary, not alphabetically.
- No namespace-only objects. An `object` whose only job is to prefix a few
  functions and constants adds a name without adding a value; in Kotlin the
  file is already the namespace, and `private` at top level is file-scoped.
  Write the functions and constants as top-level declarations instead. An
  `object` stays an object when it *is* a value: a Ktor `Hook` implementation
  such as `BeforeRouteHandler`, an Exposed table, a public constant namespace
  such as `AuthRouting`, or the `Application` object Ktor's `EngineMain`
  requires.

## Naming

Name the file after the main concept it holds. When a file contains exactly one
top-level type, the file name matches that type (Ktlint enforces this case).
When it contains several, choose the name of the concept that binds them
together, usually the primary type.

## What grouping never changes

Moving declarations between files is invisible to the rest of the code as long
as the package stays the same. Regrouping therefore never changes:

- package names,
- type names,
- visibility (`public`/`internal`/`private`), or
- behavior.

If one of those needs to change, that is a separate, deliberate decision, not
part of file organization.

## Tests

Test classes stay one scenario class per file, named after the scenario. Shared
test fixtures live in a `*TestSupport.kt` file per module. This keeps test
reports and file names aligned.
