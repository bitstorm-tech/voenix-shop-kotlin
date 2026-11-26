# Logging

The backend logs through pino, one child logger per module or component
(`createLogger(name)` in `backend/src/platform/logging.ts`). Every line shows
the logger name and the `file:line` of the log statement. Each `/api` request
gets one line with method, route pattern, status, and duration.

## The request line names the route, never the path

```text
POST /api/payments/webhook/:secret 200 12ms
```

The line carries `routePath(c)` (`hono/route`) read after the handler ran: the pattern of
the route that answered, also inside a sub-app mounted with `app.route()`. A
request no API route matched logs a catch-all (`/*`), and a request a
middleware refused logs that middleware's pattern. Successful liveness probes
(`GET /api/health` answering `200`) are omitted so periodic platform probes do
not drown out API lines; non-`200` responses and `/api/ready` remain logged, and
a failed or timed-out readiness probe logs its cause at `warn`. The real path stays out: the
Mollie and SPOD webhook secrets and the order link token are path segments, and
any line that reaches Render's log stays there. The query string stays out for
the same reason. Before November 2026 the line carried `c.req.path`, with the
secrets in it.

## A caught error is logged as `errorDetails(error)`, never as itself

```ts
logger.error({ jobId, error: errorDetails(error) }, 'SPOD job submission failed unexpectedly')
```

`errorDetails` keeps:

- the class (`TypeError`, `ZodError`, `TimeoutError`, …),
- the `code` and `syscall` of a system, network, or Postgres error
  (`ECONNREFUSED`, `ConnectionRefused`, `ENOTFOUND`, `23505`),
- the `table`, `column`, and `constraint` Postgres names,
- the `at …` frames of the stack trace,
- and the same details for its `cause`, down to three levels.

It never keeps the **message**, Postgres' `detail`, or a fetch error's `path`.
All three can quote data: a parse error quotes the input it failed on (a client
body, a provider's answer), a provider's error may quote back the request with
its key, a database error's `detail` quotes the row's values
(`Key (email)=(…) already exists`), and a URL may carry a token. So the rule
holds for every caught error. Nobody has to decide case by case whether a
message is safe.

These details still tell the common causes apart: a refused or unresolvable
connection by its code, a database refusal by its code and constraint, and a
bug by the line in the stack.

**Why not only the class.** Up to October 2026 only `errorClass(error)` was
logged. That kept the data out of the log, but a failed worker scan read
`Email worker scan failed errorClass=Error`, and nothing told a dead database
from a bug. The frames and codes carry no input, so they were added and the
rule stayed.

**Why not the whole error.** pino's `err` serializer writes every enumerable
property, which includes the message and Postgres' `detail`.

**Carry safe diagnostic reasons in fields, never only in `Error.message`.**
Because `errorDetails` drops every message, an internal error that knows a safe,
bounded reason (an upstream HTTP status, a provider error code like
`PROVIDER_HTTP_503`, or an SFTP timeout reason) must attach it as a structured
field (`code` on the error, or a dedicated field in the log call) instead of
embedding it only in `new Error(…)`.

## Operational and security signals

Beyond unhandled errors and upstream client failures, the backend logs bounded,
secret-free signals for events that are otherwise invisible or ambiguous in the
HTTP status line:

- **Database**: idle `pg.Pool` client errors (`error`), and applied migrations on
  startup (`info`).
- **Auth & CSRF**: unreadable session cookies, CSRF refusals with their bounded
  reason (`missing_cookie`, `user_mismatch`, `missing_header`, `token_mismatch`),
  invalid/revoked access tokens, and role or supplier-link refusals (`warn`).
  Ordinary anonymous `401` responses are not logged as warnings.
- **Rate limiting**: refusals with `scope` (`ip`, `global`, or `maxTrackedIps`)
  and `retryAfterSeconds`, never the client IP (`warn`).
- **Orders, payments, and fulfillment**: order paid/cancelled transitions,
  payment status transitions and refunds (`info`), payment chargebacks (`error`),
  unsupported or reference-less SPOD webhooks and remote state changes (`warn`),
  SPOD print-image encoding errors (`warn`), and production delivery/submission
  failure transitions (logged once per error-code change so polling workers do
  not spam).
- **Operations worker**: every newly detected finding at `warn` even when an
  alert e-mail is enqueued (and `error` when no `OPERATIONS_ALERT_EMAIL` is
  configured).

## The request id

Each line logged while an `/api` request is served carries the request's short
random id as `reqId`, shown as `[1a2b3c4d]` in front of the message. This holds
in every module, through `AsyncLocalStorage` (`withRequestId`). An error line
can be matched with its request line that way. Worker lines have no `reqId`
and name their job, order, or delivery instead.

## A crash

Once the server has started, `main.ts` logs an uncaught exception or an
unhandled rejection at `fatal` with `errorDetails` and exits with code 1, as
the runtime would. A failed start is left to the runtime on purpose: its
message (which setting is missing or invalid) is what an operator needs, and
it quotes no data.
