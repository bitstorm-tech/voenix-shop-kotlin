# Request size limits

The backend refuses bodies that are too large in two different places, and
the two answer two different questions. Mixing them up is the usual source of
confusion, so this page starts with the difference.

- How much may a client put on the wire at all? That is the
  application-wide limit in the HTTP runtime: 30,000,000 bytes. It is the
  outer transfer bound, it applies to every route of the application, and it is
  the only one that can cut a transfer off.
- How much may this endpoint process? That is a module's own limit: 10 MiB
  per image and 20 MiB of file parts per request in the Generator, 10 MiB for an
  image the Image module stores. These bounds decide what the server *reads and
  holds*, and they are the ones a normal upload meets.

The outer bound is deliberately the larger one. A legitimate 10 MiB picture,
plus the multipart framing a browser puts around it, fits comfortably below
30,000,000 bytes. A body that is only large stops at the outer bound, before
any handler sees it when the request announces its size, and mid-transfer when
it does not (see below).

## Where the application-wide limit lives

In [`HttpRuntime.kt`](../../../backend/modules/platform/src/shop/voenix/http/HttpRuntime.kt),
the file `installHttpRuntime()` comes from. So every application that installs
the HTTP runtime has the limit, and no module can forget it:

```kotlin
install(RequestBodyLimit) { bodyLimit { MAX_REQUEST_BODY_BYTES } }
```

`RequestBodyLimit` is Ktor's own plugin (`io.ktor:ktor-server-body-limit`). It
does two things:

1. Before a route handler runs, it compares the request's `Content-Length`
   header with the limit. A request that *announces* too much is refused right
   there, before anything has been read.
2. For a body without a `Content-Length` (a chunked upload announces no size at
   all), it wraps the request channel in a counting one and refuses as soon as
   the bytes read past the limit.

Point 2 has a limit worth knowing. The counting happens while a handler
receives the body, not while the bytes arrive on the connection. A chunked
request sent to a route that never reads its body is therefore never counted and
never answered with `413`. What bounds such a transfer is not this plugin but
Netty's backpressure. Nothing drains the unread body, so the client stalls
against a connection nobody reads from, exactly as in the announced-oversize case
below. The gap is the status code, not the transfer. Giving chunked bodies on
bodyless routes proper `413` semantics is a recorded follow-up; the obvious fix
(Ktor's `HttpObjectAggregator`) was rejected because it buffers every request
body in heap and breaks streaming multipart.

A second gap sat in point 2 and is now closed. The refusal reaches a handler
that is already reading the body, and Ktor delivers it in a way that is easy to
miss. The next section is about that. Reading a body with `readChunks` is what
closes it. That Ktor's `readAvailable(ByteArray, …)` swallows the reason a body
channel ended, while `readBuffer()`, `readRemaining()`, `readTo()` and
`copyAndClose()` in the same file rethrow it, is worth a report upstream; filing
it is a recorded follow-up.

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

## A mid-arrival refusal reaches the handler as a cancelled channel

Point 2 above deserves a closer look, because it is the one case where a handler
is already running when the limit is met.

The plugin does not stop the handler. It cancels the request body channel
with a `PayloadTooLargeException`, the same object `StatusPages` would turn
into a `413`. The handler is left holding a channel that has just been cut off,
and how it finds out is where it gets interesting:

```kotlin
// This loop cannot tell the two cases apart:
val count = channel.readAvailable(chunk, 0, chunk.size)
if (count <= 0) break
```

Ktor's `readAvailable(ByteArray, …)` answers `-1` both when the body is simply
over and when the channel was cancelled, and it never rethrows the reason.
A handler written like the loop above therefore treats the refused upload as a
complete, merely shorter one. It stores half a file and answers `200`. Only a
reader that happens to be suspended waiting for the next bytes at the exact
moment of the cancellation gets the exception thrown into it, which is why the
symptom is a *flaky* wrong answer, not a reliable one. (A loop that also asks
`exhausted()` before every read, as the upload readers used to, does get the
exception in most interleavings, because `exhausted()` looks at the buffer and
that look throws. It fails silently only in the narrow moment between that
look and the read. Narrow is not never, and the point of `readChunks` is that
the answer no longer depends on timing at all.)

The channel does know. It carries the reason as its `closedCause`, and asking
after the loop is what turns a cut-off body back into a refusal.

### What a handler that reads the body itself must do

Use
[`readChunks`](../../../backend/modules/platform/src/shop/voenix/http/RequestBodyChannels.kt),
the platform's chunk loop. It reads 64 KiB at a time, lets the caller stop early
by answering `false`, and rethrows the close cause when the loop ends:

```kotlin
var total = 0
val complete =
    channel.readChunks { chunk, count ->
        total += count
        true // false would stop the read right here
    }
```

A refused body leaves this function with the `PayloadTooLargeException`, so the
client gets its `413` instead of the server storing a truncated upload. The same
is true for a connection that dies mid-upload. It also ends the channel with a
cause, and the handler now fails instead of quietly accepting half a file.

If you would rather use Ktor directly, pick a function that rethrows the close
cause after its own loop: `readBuffer()`, `readRemaining()`, `readTo(sink,
limit)`, `copyAndClose(...)`, `toByteArray()`. `call.receive<T>()` for JSON goes
through `readRemaining()`, so every JSON endpoint is already safe. The
obligation only concerns code that streams a body itself, which in this backend
means the multipart upload readers, and, for the same reason, the Generator's
reader of the fal.ai answer. A provider answer that breaks off mid-transfer is
a failed generation, not a half image stored and paid for.

These are the ones that do not pass the refusal on:

| Ktor read function | What it does when the body was cut off |
| --- | --- |
| `readAvailable(ByteArray, offset, length)` | returns `-1`, exactly like a body that ended |
| `readBuffer(max)` | returns what it has |
| `readRemaining(max)` | returns what it has |
| `copyTo(channel)` | stops copying |
| `copyTo(channel, limit)` | stops copying |
| `readFully(...)` | throws `EOFException`, an error, but not the refusal |

Mind the pairs: `readBuffer()` rethrows, `readBuffer(max)` does not. The
argument changes the behavior.

### The limit is never leaky in bytes

The unreliable part is the *signal*, not the bound. The plugin copies the body in
chunks of at most 4 KiB and throws as soon as one chunk pushes the total past
the limit, so at most `30,000,000 + 4096` bytes ever enter the channel. That is
one chunk past the limit at the very most, never a whole extra request. And what
a reader can actually see is usually far less than that. The writer hands bytes
on in flushes of about 1 MiB, and everything it has not flushed at the moment of
the cancellation is dropped. In the test that posts 30,000,001 bytes, the
handler sees at most 29,360,128 of them. Only if the chunk that crosses the
limit happens to be the one that also fills a flush does a reader see those few
kilobytes past the limit, and it still gets the refusal, because the reader
asks for the close cause afterwards.

The mechanism is pinned by tests that need no server at all
([`RequestBodyChannelsTest`](../../../backend/modules/platform/test/shop/voenix/http/RequestBodyChannelsTest.kt)),
including a deliberate tripwire: if a future Ktor version makes `readAvailable`
rethrow the cause, that test fails and says so.

## Why "not drained" is the whole point

A refusal is cheap only if the server can stop the transfer. A module's own
limit cannot do that. A Ktor multipart read that is abandoned mid-body never
lets the call finish, because the parser waits for a reader that never comes.
That is why the Generator reads the rest of an oversized body and throws it away
before answering `400`. The bytes still cross the network, they are only never
held.

The application-wide limit is different, because for a request that announces
its size it decides *before* the body is read at all. The handler never runs,
nothing reads the request channel, and the client is left writing into a
connection nobody drains. Measured against the real Netty engine
([`RequestBodyLimitTransferTest`](../../../backend/modules/platform/test/shop/voenix/http/RequestBodyLimitTransferTest.kt)),
a client that announces 60 MB gets its `413` after about 1.4 MB, the bytes that
already fit into the socket buffers, instead of after 60 MB.

That measurement covers the *announced* case only: a body with a
`Content-Length`, refused before it is read. A chunked body has to arrive to be
counted, so its bytes do cross the network up to the limit; what the previous
section buys there is the correct answer, not a shorter transfer.

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
| past 30,000,000 bytes, announced with `Content-Length` | `413 Payload Too Large` from the HTTP runtime, before any handler runs; only what already sat in the socket buffers (about 1.4 MB measured) crosses the wire |
| past 30,000,000 bytes, without a `Content-Length` (chunked) | the handler runs and its body read is cut off after the limit; read through `readChunks` that becomes the same `413 Payload Too Large`, and the rest of the body is never transferred |

See the [Generator package guide](generator-package.md) and the
[Image package guide](image-package.md) for the module-side limits.
