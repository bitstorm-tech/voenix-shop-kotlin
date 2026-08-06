import { reactive, shallowRef, type ShallowRef } from 'vue'

export interface UseFormErrorsReturn<TField extends string> {
  fieldErrors: Partial<Record<TField, string>>
  generalError: ShallowRef<string | null>
  clearFieldErrors: () => void
  clearErrors: () => void
}

export function useFormErrors<TField extends string>(): UseFormErrorsReturn<TField> {
  const fieldErrors = reactive({}) as Partial<Record<TField, string>>
  const generalError = shallowRef<string | null>(null)

  function clearFieldErrors() {
    for (const field of Object.keys(fieldErrors) as TField[]) {
      delete fieldErrors[field]
    }
  }

  function clearErrors() {
    clearFieldErrors()
    generalError.value = null
  }

  return { fieldErrors, generalError, clearFieldErrors, clearErrors }
}
