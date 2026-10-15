import { conflict, notFound, ok, type OperationResult } from '@/platform/operation'

/**
 * How the repositories' plain answers become operation results; the slot and category services
 * share the three shapes their reads, writes, and deletes answer in.
 */

/** A read: `undefined` is an id nobody stored. */
export const found = <T>(value: T | undefined): OperationResult<T> =>
  value === undefined ? notFound : ok(value)

/**
 * A create, update, or reorder: the stored value, a conflict (a name that already exists, a lost
 * reorder race), or an unknown id.
 */
export function written<T>(stored: T | 'conflict' | 'notFound'): OperationResult<T> {
  if (stored === 'conflict') return conflict
  if (stored === 'notFound') return notFound
  return ok(stored)
}

/** A delete: the number of rows deleted, or a row that references the one to delete. */
export function deleted(result: number | 'inUse'): OperationResult<undefined> {
  if (result === 'inUse') return conflict
  return result === 0 ? notFound : ok(undefined)
}
