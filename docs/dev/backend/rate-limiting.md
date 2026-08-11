# Rate limiting

The backend limits **one** endpoint: `POST /api/generator/generate`. That is on
purpose. This is not a general "protect the API" limit but a **cost bound** — the
generation endpoint is the only request that can be sent without an account and
that pays fal.ai per call.

The limit is:

> **20 generations per client IP per hour.** Request 21 is answered with
> `429 Too Many Requests` and a `Retry-After` header.

## Why the IP and not the visitor

A generation is paid with Magic Coins, and a visitor without an account gets an
initial grant of 10 coins. That balance hangs on the `voenix.guest` cookie, so
deleting the cookie produces a fresh guest with a fresh grant — the same browser
can pay for the same free generations again and again. The coin balance is
therefore an accounting rule, not a bound on provider cost.

The client IP is the next-cheapest identity a browser cannot simply throw away.
It is not perfect (a whole office shares one address, a mobile network reassigns
addresses), which is why the number is generous: 20 generations an hour is far
above what a customer does and far below what an attack needs.

## Where the code is

| File | What it does |
| --- | --- |
| [`ClientIpRateLimiter.kt`](../../../backend/modules/platform/src/shop/voenix/ratelimit/ClientIpRateLimiter.kt) | Counts requests per IP and answers "how long has this one to wait?" |
| [`ClientIpRateLimit.kt`](../../../backend/modules/platform/src/shop/voenix/ratelimit/ClientIpRateLimit.kt) | The route plugin: `installClientIpRateLimit(limiter)` |
| [`RateLimitSettings.kt`](../../../backend/modules/platform/src/shop/voenix/ratelimit/RateLimitSettings.kt) | The one configuration flag, `rateLimit.trustForwardedFor` |

All three live in `platform`, and that placement is the point: how many calls an
IP gets is infrastructure policy, and the Generator module should not own it. The
Generator only says *where* the limit applies, in
[`GeneratorRoutes.kt`](../../../backend/modules/generator/src/shop/voenix/generator/GeneratorRoutes.kt):

```kotlin
route(BASE_PATH) {
    installGuestCapableRouteProtection()

    route(GENERATE_PATH) {
        installClientIpRateLimit(rateLimiter)

        post { /* … */ }
    }
}
```

The limiter itself is built once, in the composition root
[`Application.kt`](../../../backend/app/src/shop/voenix/Application.kt), with
`ClientIpRateLimiter(settings.rateLimit)`.

Note the order inside the route: the CSRF protection is installed **first**. A
request without a valid CSRF token is answered before the limiter sees it, so a
rejected request does not use up a slot of the limit. The limit counts the
requests that would really generate an image.

## How the counting works

Each IP gets a **fixed window**:

1. The first request of an IP opens a window of one hour and sets the counter
   to 1.
2. Every further request inside that hour increments the counter. The 20th one
   is the last that passes.
3. The first request after the hour is over opens a new window.

```text
10:00:00  request  1 → window opens, allowed
10:07:12  request 20 → allowed (the last one)
10:07:30  request 21 → 429, Retry-After: 3150
11:00:00  request 22 → new window, allowed
```

A *sliding* window (remembering every single timestamp) would smooth the edges,
but it costs one timestamp per request instead of one small object per IP, and
its only advantage here would be to stop a burst of up to 40 requests spread
around a window boundary. For a cost bound of this size that is not worth the
extra machinery, so the code deliberately keeps the simple version.

`Retry-After` is the number of **seconds until the current window ends**, always
at least `1` — a `Retry-After: 0` would only invite an immediate retry. A refused
request never pushes the window further away, so the wait always shrinks.

The refusal uses the same error shape as every other error of the backend:

```text
429 Too Many Requests
Retry-After: 3150
{"message":"Too many requests","errors":{}}
```

## Which IP is counted

This is the part a deployment has to get right, because getting it wrong makes
the limit either useless or harmful.

- **By default** the counted address is the peer address of the TCP connection
  (`call.request.origin.remoteAddress`). No client can fake that.
- **Behind a reverse proxy** every connection comes from the proxy, so the peer
  address would be the *same* for all visitors and the whole site would share one
  window of 20 generations per hour. For that case there is one configuration
  flag:

```yaml
rateLimit:
  trustForwardedFor: "$RATE_LIMIT_TRUST_FORWARDED_FOR:false"
```

Set `RATE_LIMIT_TRUST_FORWARDED_FOR=true` **only** when a reverse proxy really
sits in front of the backend and the backend is not reachable directly.
`X-Forwarded-For` is a plain request header: any client can send one, so trusting
it without a proxy in front lets a caller invent a fresh IP per request and walk
straight around the limit. The safe default is therefore `false`, and enabling it
is a statement about the deployment, not a preference.

When the flag is enabled, the limiter reads the **last** entry of the header, not
the first:

```text
X-Forwarded-For: 203.0.113.7, 198.51.100.1
                 ^ client-supplied         ^ appended by our proxy
                 (may be a lie)              (trustworthy)
```

Each proxy on the way appends the address it saw. The first entry is whatever the
client itself sent and can be anything; only the last one was written by the
proxy in front of us. This is why the flag assumes **exactly one** trusted proxy.
With two chained proxies the last entry would be the inner proxy's address, and
all traffic would be counted as a single client — annoying, but failing closed
rather than open.

Ktor also ships an `XForwardedHeaders` plugin. It is deliberately not used here:
it rewrites the request origin for the whole application and takes the *first*
header entry, which is the spoofable one.

## The multi-instance caveat

The counters live in a `ConcurrentHashMap` **inside this process**. That is
correct for the current deployment, which runs a single instance, and only for
that one. Two instances behind a load balancer would each count their own share,
so the effective limit would be 20 generations per instance per hour.

The day the backend is scaled out, the state has to move somewhere shared — a
Redis counter or a database table — and `ClientIpRateLimiter` is the one class
that has to change. Nothing else knows how the counting works.

## How the memory stays bounded

The map is bounded in **two** ways, and one of them alone would not be enough.

1. **Time.** At most once per window the limiter drops the windows that have
   ended, so an IP that never returns does not stay in memory.
2. **Size.** That sweep only helps *after* a window is over. A caller who rotates
   through addresses — an IPv6 /64 alone hands out more of them than anyone can
   use — would grow the map for a full hour before a single entry expires. So the
   limiter remembers at most **100,000 addresses**. A request from an address
   that is not among them first forces a sweep of the ended windows; if the map
   is still full afterwards, the request is refused with the full window as its
   `Retry-After`.

The second rule fails **closed** on purpose. The alternative — throwing an old
entry out to make room — would hand the address rotation the cap exists against
exactly what it wants: an endless supply of fresh counters. The price is that
during such a flood a new legitimate visitor is refused too, which is why the cap
sits far above the number of addresses an hour of real traffic brings.

## Tests

| Test | What it states |
| --- | --- |
| [`ClientIpRateLimiterTest`](../../../backend/modules/platform/test/shop/voenix/ratelimit/ClientIpRateLimiterTest.kt) | The counting: how many requests pass, when a new window starts, what `Retry-After` says, and that each IP is counted on its own. It passes its own `now`, which is what makes a one-hour window testable in milliseconds. It also states the size cap: a new address is refused while the map is full, and gets in again once an ended window is swept out of the way. |
| [`ClientIpRateLimitTest`](../../../backend/modules/platform/test/shop/voenix/ratelimit/ClientIpRateLimitTest.kt) | The HTTP behavior: `429` with `Retry-After`, a handler that never runs, a neighbouring route that keeps answering, and both forwarded-header cases. |
| [`GeneratorRoutesTest`](../../../backend/modules/generator/test/shop/voenix/generator/GeneratorRoutesTest.kt) | The wiring: the 21st generation of an IP is refused before the operation runs, and requests without a CSRF token spend no slot at all. |
