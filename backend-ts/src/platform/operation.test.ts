import { describe, expect, test } from 'bun:test'
import { buildValidationErrors } from '@/platform/operation'

describe('buildValidationErrors', () => {
  test('collects nothing for a valid input', () => {
    expect(buildValidationErrors(() => undefined)).toEqual({})
  })

  test('appends a second message to a field instead of replacing the first', () => {
    const errors = buildValidationErrors((add) => {
      add('targetId', 'TargetId must be positive')
      add('targetId', 'TargetId must be different from SourceId')
    })
    expect(errors).toEqual({
      targetId: ['TargetId must be positive', 'TargetId must be different from SourceId'],
    })
  })

  test('keeps the order in which fields were first reported', () => {
    const errors = buildValidationErrors((add) => {
      add('second', 'Second is required')
      add('first', 'First is required')
      add('second', 'Second must be positive')
    })
    expect(Object.keys(errors)).toEqual(['second', 'first'])
  })
})
