<script setup lang="ts">
import type { HTMLAttributes } from 'vue'
import type { ButtonVariants } from '@/components/ui/button'
import { useVModel } from '@vueuse/core'
import { useTemplateRef } from 'vue'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

const props = withDefaults(
  defineProps<{
    accept?: string
    capture?: boolean | 'user' | 'environment'
    buttonTestId?: string
    class?: HTMLAttributes['class']
    defaultValue?: File[]
    disabled?: boolean
    id?: string
    inputTestId?: string
    label?: string
    modelValue?: File[]
    multiple?: boolean
    name?: string
    resetOnSelect?: boolean
    rootClass?: HTMLAttributes['class']
    size?: ButtonVariants['size']
    variant?: ButtonVariants['variant']
  }>(),
  {
    defaultValue: () => [],
    label: 'Choose file',
    size: 'default',
    variant: 'outline',
  },
)

const emits = defineEmits<{
  (event: 'update:modelValue', value: File[]): void
  (event: 'change', value: File[]): void
}>()

const input = useTemplateRef<HTMLInputElement>('input')
const files = useVModel(props, 'modelValue', emits, {
  passive: true,
  defaultValue: props.defaultValue,
})

function open() {
  if (props.disabled) {
    return
  }

  input.value?.click()
}

function clear() {
  if (input.value) {
    input.value.value = ''
  }

  files.value = []
  emits('change', [])
}

function handleChange(event: Event) {
  const target = event.target as HTMLInputElement
  const selectedFiles = Array.from(target.files ?? [])

  files.value = selectedFiles
  emits('change', selectedFiles)

  if (props.resetOnSelect) {
    target.value = ''
  }
}

defineExpose({
  clear,
  open,
})
</script>

<template>
  <span :class="cn('inline-flex', rootClass)">
    <input
      ref="input"
      :accept="accept"
      :capture="capture"
      class="sr-only"
      :data-testid="inputTestId"
      :disabled="disabled"
      :id="id"
      :multiple="multiple"
      :name="name"
      tabindex="-1"
      type="file"
      @change="handleChange"
    />
    <Button
      type="button"
      :variant="variant"
      :size="size"
      :class="cn(props.class)"
      :data-testid="buttonTestId"
      :disabled="disabled"
      @click="open"
    >
      <slot :clear="clear" :disabled="disabled" :files="files" :open="open">
        {{ label }}
      </slot>
    </Button>
  </span>
</template>
