import { z } from 'zod'

/**
 * The Zod fragments the request schemas of this module share. Their messages are those of the
 * Kotlin `validate()` methods: a display name in the casing of the field ("CategoryId is required").
 */

/** A required name: blank is "required", the limit counts the trimmed text, the value is trimmed. */
export const requiredName = (maximumLength: number) =>
  z
    .string({ error: 'Name is required' })
    .trim()
    .min(1, 'Name is required')
    .max(maximumLength, `Name must be at most ${String(maximumLength)} characters`)

/** An optional text: missing, `null`, and blank all mean `null`; anything else is trimmed. */
export const optionalText = (displayName: string, maximumLength: number) =>
  z
    .string({ error: `${displayName} must be a string` })
    .trim()
    .max(maximumLength, `${displayName} must be at most ${String(maximumLength)} characters`)
    .transform((text) => (text === '' ? null : text))
    .nullable()
    .default(null)

const reorderId = (displayName: string) =>
  z.int({ error: `${displayName} is required` }).positive(`${displayName} must be positive`)

/**
 * The body of every reorder route: move `sourceId` to the place `targetId` holds. Whether the ids
 * exist is the database's question and answers `404`, not a field error.
 */
export const reorderInput = z
  .strictObject({ sourceId: reorderId('SourceId'), targetId: reorderId('TargetId') })
  .superRefine(
    ({ sourceId, targetId }, ctx) => {
      if (sourceId === targetId) {
        ctx.addIssue({
          code: 'custom',
          path: ['targetId'],
          message: 'TargetId must be different from SourceId',
        })
      }
    },
    // Also for two ids that broke their own rules, so `0, 0` reports the move onto itself too.
    {
      when: ({ value }) =>
        typeof value === 'object' &&
        value !== null &&
        'sourceId' in value &&
        typeof value.sourceId === 'number',
    },
  )

export type ReorderInput = z.output<typeof reorderInput>
