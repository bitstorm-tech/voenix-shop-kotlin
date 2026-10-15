import { zValidator } from '@hono/zod-validator'
import type { Context, MiddlewareHandler } from 'hono'
import { z } from 'zod'
import {
  buildValidationErrors,
  type OperationFailure,
  type OperationResult,
  type ValidationErrors,
} from '@/platform/operation'

/** The one error body of this API; `code` is omitted unless given. */
export interface ApiError {
  message: string
  errors: ValidationErrors
  code?: string
}

export function apiError(message: string, errors: ValidationErrors = {}, code?: string): ApiError {
  return code === undefined ? { message, errors } : { message, errors, code }
}

/**
 * The `400 Validation failed` body of a rejected request: one entry per field, keyed like the JSON
 * (`name`, `mugVariants[0].name`), in the order the issues were found. An unknown property is
 * reported under its own name.
 */
export function validationFailed(error: { issues: readonly z.core.$ZodIssue[] }): ApiError {
  const errors = buildValidationErrors((add) => {
    for (const issue of error.issues) {
      if (issue.code === 'unrecognized_keys') {
        for (const key of issue.keys) add(fieldName([...issue.path, key]), 'Unknown property')
      } else {
        add(fieldName(issue.path), issue.message)
      }
    }
  })
  return apiError('Validation failed', errors)
}

function fieldName(path: readonly PropertyKey[]): string {
  return path.reduce<string>((name, segment) => {
    if (typeof segment === 'number') return `${name}[${String(segment)}]`
    return name === '' ? String(segment) : `${name}.${String(segment)}`
  }, '')
}

/**
 * Validates a JSON request body against `schema` and makes it available as `c.req.valid('json')`.
 * Answers `415` for a content type other than `application/json`, `400 Malformed request body`
 * for a body that is not JSON, and `400 Validation failed` with the field errors otherwise.
 * Declare schemas with `z.strictObject`, so an unknown property is an error.
 */
export function jsonBody<S extends z.ZodType>(schema: S) {
  const validate = zValidator('json', schema, (result, c): Response | undefined =>
    result.success ? undefined : c.json(validationFailed(result.error), 400),
  )
  const validateJson: typeof validate = async (c, next) => {
    const contentType = c.req.header('content-type') ?? ''
    if (!/^application\/json(\s*;.*)?$/i.test(contentType)) {
      return c.json(apiError('Unsupported media type'), 415)
    }
    try {
      await c.req.json()
    } catch {
      return c.json(apiError('Malformed request body'), 400)
    }
    return validate(c, next)
  }
  return validateJson
}

/**
 * Guards a route that reads a multipart form (`await c.req.formData()` in the handler; Hono parses
 * the body once and caches it). Answers `415` for a content type other than `multipart/form-data`
 * and `400 Malformed request body` for a body that is not the form its content type announces, the
 * two answers `jsonBody` gives a JSON route. Which parts the form must carry is the route's rule.
 */
export const formBody: MiddlewareHandler = async (c, next) => {
  const contentType = c.req.header('content-type') ?? ''
  if (!/^multipart\/form-data(\s*;.*)?$/i.test(contentType)) {
    return c.json(apiError('Unsupported media type'), 415)
  }
  try {
    await c.req.formData()
  } catch {
    return c.json(apiError('Malformed request body'), 400)
  }
  await next()
}

const integerText = z
  .string()
  .regex(/^-?\d+$/)
  .transform(Number)
  .pipe(z.int())

/**
 * Parses the path parameter `name` as an integer id into `c.req.valid('param')`, answering `400`
 * with `invalid` as the message for anything else. `0` and negative numbers parse and reach the
 * operation, which answers not-found for them like for any unknown id. Hono keeps one validated
 * value per target, so a route with two ids declares one `zValidator('param', …)` of its own.
 */
export function integerParam<Name extends string>(name: Name, invalid: string) {
  const shape = { [name]: integerText } as Record<Name, typeof integerText>
  return zValidator('param', z.object(shape), (result, c) =>
    result.success ? undefined : c.json(apiError(invalid), 400),
  )
}

/** The messages a route file answers the failure variants with; `conflict: null` says the operations behind it cannot conflict. */
export interface FailureResponses {
  notFound: string
  conflict: string | null
}

/** Answers `result`'s value with `successStatus`, or the failure through `respondFailure`. */
export function respondResult<T>(
  c: Context,
  result: OperationResult<T>,
  responses: FailureResponses,
  successStatus: 200 | 201 = 200,
): Response {
  return result.kind === 'ok'
    ? c.json(result.value, successStatus)
    : respondFailure(c, result, responses)
}

/**
 * Answers a failure. Routes call this directly when they build the success answer themselves (a
 * `201` with a `Location` header, a `204` delete).
 */
export function respondFailure(
  c: Context,
  result: OperationFailure,
  responses: FailureResponses,
): Response {
  switch (result.kind) {
    case 'notFound':
      return c.json(apiError(responses.notFound), 404)
    case 'conflict':
      if (responses.conflict === null)
        throw new Error('An operation that cannot conflict answered a conflict')
      return c.json(apiError(responses.conflict), 409)
    case 'invalid':
      return c.json(apiError('Validation failed', result.errors), 400)
  }
}
