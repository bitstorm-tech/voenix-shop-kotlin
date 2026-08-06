import { shallowRef, watch, type Ref } from 'vue'

export interface UseDialogFormOptions {
  open: Ref<boolean>
  resetKeys?: () => readonly unknown[]
  resetForm: () => void
}

export function useDialogForm(options: UseDialogFormOptions) {
  const isDeleteDialogOpen = shallowRef(false)

  watch(
    () => [options.open.value, ...(options.resetKeys?.() ?? [])] as const,
    ([isOpen]) => {
      isDeleteDialogOpen.value = false
      if (isOpen) {
        options.resetForm()
      }
    },
    { immediate: true },
  )

  return { isDeleteDialogOpen }
}
