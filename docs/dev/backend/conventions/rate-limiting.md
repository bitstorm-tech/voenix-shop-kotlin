# Rate limiting

The backend limits one endpoint: `POST /api/generator/generate`. This is not a
general "protect the API" limit but a cost bound: the generation endpoint is
the only request that can be sent without an account and that pays fal.ai per
call. The limit is:

> 20 generations per client IP per hour. Request 21 is answered with
> `429 Too Many Requests` and a `Retry-After` header.

## Why the IP and not the visitor

A generation is paid with Magic Coins, and a visitor without an account gets an
initial grant. That balance hangs on the `voenix.guest` cookie, so deleting the
cookie produces a fresh guest with a fresh grant. The IP is the next-cheapest
identity a browser cannot simply throw away. It is not perfect, which is why
the number is generous: far above what a customer does, far below what an
attack needs.

## Where the code is

[`client-ip-rate-limiter.ts`](../../../../backend/src/platform/ratelimit/client-ip-rate-limiter.ts)
holds the whole mechanism; the platform owns it because how many calls an IP
gets is infrastructure policy. The generator only says *where* the limit
applies:

```ts
const limiter = createClientIpRateLimiter({
  trustForwardedFor: settings.rateLimit.trustForwardedFor,
  peerAddress: (c) => getConnInfo(c).remote.address ?? '',
})

generator.post('/generate', formBody, limiter.middleware, async (c) => { … })
```

`peerAddress` is handed in because reading the connection's address is the
one Bun-specific call (`getConnInfo` from `@hono/bun`, the package Hono
moved its Bun adapter to); the composition root
passes it, and a test passes a constant. Mind the order: the route's
protection and its body guard (`formBody`) are installed first, so a request
without a valid CSRF token or without a multipart body is answered before the
limiter sees it and spends no slot.

## How the counting works

Each IP gets a fixed window: the first request opens a window of one hour and
sets the counter to 1, every further request inside it increments the counter,
the 20th is the last that passes, and the first request after the hour opens a
new window. A sliding window would smooth the boundary at the cost of one
timestamp per request; for a cost bound of this size the simple version wins.

`Retry-After` is the number of seconds until the window ends, always at least
`1`. A refused request never pushes the window further away. The refusal uses
the shared error shape:

```text
429 Too Many Requests
Retry-After: 3150
{"message":"Too many requests","errors":{}}
```

## Which IP is counted

By default the counted address is the peer address of the connection, which no
client can fake. Behind a reverse proxy every connection comes from the proxy,
so there is one setting:

```sh
RATE_LIMIT_TRUST_FORWARDED_FOR=true
```

Set it only when exactly one trusted reverse proxy sits in front of the backend
and the backend is not reachable directly. `X-Forwarded-For` is a plain
request header any client can send; trusting it without a proxy lets a caller
invent a fresh address per request. With the flag on, the limiter reads the
**last** entry of the header, the one the trusted proxy appended, never the
first, which is whatever the client itself sent.

## The multi-instance caveat

The counters live in a `Map` inside this process, which is correct for the
single-instance deployment and only for that. The day the backend is scaled
out, the state moves to something shared, and this file is the one place that
changes.

## How the memory stays bounded

Windows that have ended are swept at most once per window, and at most
100,000 addresses are remembered. A request from an unknown address while the
map is full first forces a sweep; if the map is still full, the request is
refused for the whole window. That fails closed on purpose: evicting an entry
would hand an address rotation exactly what it wants.

## Tests

[`client-ip-rate-limiter.test.ts`](../../../../backend/src/platform/ratelimit/client-ip-rate-limiter.test.ts)
states the counting with its own clock (how many pass, when a window restarts,
what `Retry-After` says, the size cap) and the HTTP behaviour: the 21st request
of an IP is refused before the handler runs, a neighbouring route keeps
answering, and both forwarded-header cases.
