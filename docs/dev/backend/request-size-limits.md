# Request size limits

The backend refuses bodies that are too large in **two different places**, and
the two answer two different questions. Mixing them up is the usual source of
confusion, so this page starts with the difference.

- **How much may a client put on the wire at all?** That is the
  application-wide limit in the HTTP runtime: **30,000,000 bytes**. It is the
  outer transfer bound, it applies to every route of the application, and it is
  the only one that can cut a transfer off.
- **How much may this endpoint process?** That is a module's own limit — 10 MiB
  per image and 20 MiB of file parts per request in the Generator, 10 MiB for an
  image the Image module stores. These bounds decide what the server *reads and
  holds*, and they are the ones a normal upload meets.

The outer bound is deliberately the larger one. A legitimate 10 MiB picture,
plus the multipart framing a browser puts around it, fits comfortably below
30,000,000 bytes; a body that is only large stops at the outer bound long before
any handler sees it.

## Where the application-wide limit lives

In [`HttpRuntime.kt`](../../../backend/modules/platform/src/shop/voenix/http/HttpRuntime.kt),
the file `installHttpRuntime()` comes from — so every application that installs
the HTTP runtime has the limit, and no module can forget it:

```kotlin
install(RequestBodyLimit) { bodyLimit { MAX_REQUEST_BODY_BYTES } }
```

`RequestBodyLimit` is Ktor's own plugin (`io.ktor:ktor-server-body-limit`). It
does two things:

1. Before a route handler runs, it compares the request's `Content-Length`
   header with the limit. A request that *announces* too much is refused right
   there — nothing has been read yet.
2. For a body without a `Content-Length` (a chunked upload announces no size at
   all), it wraps the request channel in a counting one and refuses as soon as
   the bytes read past the limit.

Point 2 has a limit worth knowing: the counting happens **while a handler
receives the body**, not while the bytes arrive on the connection. A chunked
request sent to a route that never reads its body is therefore never counted and
never answered with `413`. What bounds such a transfer is not this plugin but
Netty's backpressure: nothing drains the unread body, so the client stalls
against a connection nobody reads from, exactly as in the announced-oversize case
below. The gap is the status code, not the transfer. Giving chunked bodies on
bodyless routes proper `413` semantics is a recorded follow-up; the obvious fix
(Ktor's `HttpObjectAggregator`) was rejected because it buffers every request
body in heap and breaks streaming multipart.

Either way a refusal becomes a `PayloadTooLargeException`, which the
`StatusPages` block of the same file turns into the shared `ApiError` shape:

```text
413 Payload Too Large
{"message":"Request body too large","errors":{}}
```

The value `30_000_000` is not a round number by accident. The legacy .NET
application ran on Kestrel, whose default `MaxRequestBodySize` is 30,000,000
bytes, and the migrated backend deliberately accepts exactly what the old one
accepted.

## Why "not drained" is the whole point

A refusal is cheap only if the server can stop the transfer. A module's own
limit cannot do that: a Ktor multipart read that is abandoned mid-body never
lets the call finish, because the parser waits for a reader that never comes.
That is why the Generator reads the rest of an oversized body and throws it away
before answering `400` — the bytes still cross the network, they are only never
held.

The application-wide limit is different, because it decides *before* the body is
read at all. The handler never runs, nothing reads the request channel, and the
client is left writing into a connection nobody drains. Measured against the
real Netty engine
([`RequestBodyLimitTransferTest`](../../../backend/modules/platform/test/shop/voenix/http/RequestBodyLimitTransferTest.kt)):
a client that announces 60 MB gets its `413` after about 1.4 MB — the bytes that
already fit into the socket buffers — instead of after 60 MB.

That test uses a plain `java.net.Socket` on purpose. Ktor's in-memory test host
hands a body over as a channel and never puts a byte on a wire, so it can prove
the status code but not the transfer behavior.

## What this means for the upload endpoints

Nothing changes for a request that is merely *invalid*. The order of the two
limits is:

| Body size | What happens |
| --- | --- |
| up to 10 MiB image, 20 MiB of file parts | the upload is processed normally |
| past a module's own limit, below 30,000,000 bytes | the module refuses with `400 Validation failed` on the `image`/`file` field, after reading the rest of the body away |
| past 30,000,000 bytes | `413 Payload Too Large` from the HTTP runtime, before any handler runs and before the body is transferred |

See the [Generator package guide](generator-package.md) and the
[Image package guide](image-package.md) for the module-side limits.
