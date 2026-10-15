# Request size limits

The backend refuses bodies that are too large in two different places, and the
two answer two different questions:

- How much may a client put on the wire at all? That is the application-wide
  limit of **30,000,000 bytes** in [`app.ts`](../../../../backend-ts/src/platform/http/app.ts).
  It applies to every route and is the only limit that refuses a transfer.
- How much may this endpoint process? That is a module's own limit: 10 MiB per
  image and 20 MiB of file parts in the generator, 10 MiB for an image the
  image module stores. These bounds decide what the server reads and holds, and
  they are the ones a normal upload meets.

The outer bound is deliberately the larger one, and it is the 30,000,000 bytes
the legacy application's Kestrel refused at, so the backend accepts exactly
what the old one accepted.

## Where the application-wide limit lives

`createApp` installs Hono's `bodyLimit` middleware for every route, with the
shared error shape as its answer:

```text
413 Payload Too Large
{"message":"Request body too large","errors":{}}
```

The middleware does two things:

1. A request that announces its size with a `Content-Length` header above the
   limit is refused before the handler runs; not a single byte is read.
2. A body without a `Content-Length` (a chunked upload) is read and buffered
   **before** the handler runs, chunk by chunk, and refused the moment the
   bytes pass the limit. The handler then either never runs, or runs with a
   complete body.

Point 2 is where this backend differs from the Kotlin one. Ktor let the handler
read the body while the limit counted, so a handler that streamed its body
could see a cut-off body that looked complete, and the platform shipped a
`readChunks` helper that turned the cut-off into the refusal. Hono buffers
first, so there is no cut-off body to detect and no helper: a handler reads
`c.req.raw.body` or `c.req.arrayBuffer()` and gets either everything or
nothing. The price is memory: a chunked body is held in full, up to the limit,
before the handler sees it. A connection that dies mid-transfer errors the
body stream, and a read of it throws, which the app's `onError` answers with
`500`.

## What this means for the upload endpoints

| Body size | What happens |
| --- | --- |
| up to a module's own limit | the upload is processed normally |
| past the module's limit, below 30,000,000 bytes | the module refuses with `400 Validation failed` on the `image`/`file` field |
| past 30,000,000 bytes, announced | `413` before any handler runs |
| past 30,000,000 bytes, chunked | `413` once the limit is passed while buffering, before the handler runs |

The platform test `app.test.ts` pins the announced case, the exact-limit case,
and the chunked case.
