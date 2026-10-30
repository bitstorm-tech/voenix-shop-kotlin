# Logging

The backend logs through pino, one child logger per module or component
(`createLogger(name)` in `backend/src/platform/logging.ts`). Every line shows
the logger name and the `file:line` of the log statement. Each `/api` request
gets one line with method, path, status, and duration.

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
