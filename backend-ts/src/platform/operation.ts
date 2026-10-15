/**
 * Validation messages grouped by lower-camel-case field name. An empty object means the input is
 * valid. This is the `errors` map of a `400 Validation failed` answer and what `invalid` carries.
 */
export type ValidationErrors = Record<string, string[]>

/**
 * What a service answers: the value, or one of three expected failures. There is no "unexpected
 * failure" variant on purpose: an exception propagates to the app's `onError`, which answers
 * `500` without describing it (docs/dev/backend-ts/conventions/operation-results.md).
 */
export type OperationResult<T> =
  | { kind: 'ok'; value: T }
  | { kind: 'notFound' }
  | { kind: 'conflict' }
  | { kind: 'invalid'; errors: ValidationErrors }

export type OperationFailure = Exclude<OperationResult<never>, { kind: 'ok' }>

export const ok = <T>(value: T): OperationResult<T> => ({ kind: 'ok', value })
export const notFound: OperationResult<never> = { kind: 'notFound' }
export const conflict: OperationResult<never> = { kind: 'conflict' }
export const invalid = (errors: ValidationErrors): OperationResult<never> => ({
  kind: 'invalid',
  errors,
})

/**
 * Collects validation messages per field. Several rules may reject the same field, so a second
 * message is appended to the first rather than replacing it; fields and messages keep the order
 * in which they were added.
 */
export function buildValidationErrors(
  build: (add: (field: string, message: string) => void) => void,
): ValidationErrors {
  const errors: ValidationErrors = {}
  build((field, message) => {
    ;(errors[field] ??= []).push(message)
  })
  return errors
}

/** The `default` branch of an exhaustive `switch`: compiles only when every variant is handled. */
export function assertNever(value: never): never {
  throw new Error(`Unhandled variant: ${JSON.stringify(value)}`)
}
