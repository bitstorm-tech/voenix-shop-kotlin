import { describe, expect, it } from 'vitest'
import { useFormErrors } from '../useFormErrors'

describe('useFormErrors', () => {
  it('stores field errors per field', () => {
    const { fieldErrors } = useFormErrors<'name' | 'percent'>()

    fieldErrors.name = 'Name is required.'
    fieldErrors.percent = 'Percent is required.'

    expect(fieldErrors.name).toBe('Name is required.')
    expect(fieldErrors.percent).toBe('Percent is required.')
  })

  it('clearFieldErrors removes field errors but keeps the general error', () => {
    const { fieldErrors, generalError, clearFieldErrors } = useFormErrors<'name'>()

    fieldErrors.name = 'Name is required.'
    generalError.value = 'Something failed.'

    clearFieldErrors()

    expect(fieldErrors.name).toBeUndefined()
    expect(generalError.value).toBe('Something failed.')
  })

  it('clearErrors removes field errors and the general error', () => {
    const { fieldErrors, generalError, clearErrors } = useFormErrors<'name' | 'percent'>()

    fieldErrors.name = 'Name is required.'
    fieldErrors.percent = 'Percent is required.'
    generalError.value = 'Something failed.'

    clearErrors()

    expect(fieldErrors.name).toBeUndefined()
    expect(fieldErrors.percent).toBeUndefined()
    expect(generalError.value).toBeNull()
  })
})
