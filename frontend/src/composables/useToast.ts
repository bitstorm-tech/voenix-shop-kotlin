import { ref } from 'vue'

export interface ToastProps {
  title?: string
  description?: string
  variant?: 'default' | 'success' | 'destructive'
  duration?: number
}

export interface Toast extends ToastProps {
  id: string
  open: boolean
}

const toasts = ref<Toast[]>([])
let count = 0

function toast(props: ToastProps): string {
  const id = String(++count)
  toasts.value.push({ ...props, id, open: true })
  return id
}

function dismiss(id: string) {
  const index = toasts.value.findIndex((t) => t.id === id)
  if (index !== -1) {
    toasts.value.splice(index, 1)
  }
}

export function useToast() {
  return {
    toasts,
    toast,
    dismiss,
  }
}
