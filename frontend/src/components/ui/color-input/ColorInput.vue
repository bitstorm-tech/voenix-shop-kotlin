<script setup lang="ts">
import type { HTMLAttributes } from 'vue'
import { useVModel } from '@vueuse/core'
import { useTemplateRef } from 'vue'
import { cn } from '@/lib/utils'

const props = withDefaults(
  defineProps<{
    class?: HTMLAttributes['class']
    defaultValue?: string
    disabled?: boolean
    id?: string
    inputClass?: HTMLAttributes['class']
    label?: string
    modelValue?: string
    name?: string
    triggerClass?: HTMLAttributes['class']
    visuallyHidden?: boolean
  }>(),
  {
    defaultValue: '#000000',
    label: 'Choose color',
  },
)

const emits = defineEmits<{
  (event: 'update:modelValue', value: string): void
  (event: 'change', value: string): void
}>()

const input = useTemplateRef<HTMLInputElement>('input')
const value = useVModel(props, 'modelValue', emits, {
  passive: true,
  defaultValue: props.defaultValue,
})

function open() {
  if (props.disabled) {
    return
  }

  input.value?.click()
}

function handleChange(event: Event) {
  emits('change', (event.target as HTMLInputElement).value)
}

defineExpose({
  open,
})
</script>

<template>
  <span :class="cn('inline-flex items-center gap-2', props.class)">
    <input
      :id="id"
      ref="input"
      v-model="value"
      :aria-hidden="visuallyHidden || undefined"
      :aria-label="label"
      :class="
        cn(
          visuallyHidden
            ? 'sr-only'
            : 'h-9 w-12 rounded-md border border-input bg-transparent p-1 shadow-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50',
          inputClass,
        )
      "
      :disabled="disabled"
      :name="name"
      :tabindex="visuallyHidden ? -1 : undefined"
      type="color"
      @change="handleChange"
    />

    <button
      v-if="visuallyHidden"
      type="button"
      :aria-label="label"
      :class="
        cn(
          'inline-flex size-9 items-center justify-center rounded-md border border-input bg-background p-1 shadow-sm transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50',
          triggerClass,
        )
      "
      :disabled="disabled"
      @click="open"
    >
      <slot :open="open" :value="value">
        <span
          class="size-full rounded-sm border border-black/10"
          :style="{ backgroundColor: value }"
          aria-hidden="true"
        />
      </slot>
    </button>
  </span>
</template>
