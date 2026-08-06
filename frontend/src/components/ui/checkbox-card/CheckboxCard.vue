<script setup lang="ts">
import type { HTMLAttributes } from 'vue'
import { useVModel } from '@vueuse/core'
import { Check } from 'lucide-vue-next'
import { cn } from '@/lib/utils'

const props = withDefaults(
  defineProps<{
    class?: HTMLAttributes['class']
    contentClass?: HTMLAttributes['class']
    defaultValue?: boolean
    disabled?: boolean
    id?: string
    modelValue?: boolean
    name?: string
    value?: string
  }>(),
  {
    defaultValue: false,
    value: 'on',
  },
)

const emits = defineEmits<{
  (event: 'update:modelValue', value: boolean): void
  (event: 'change', value: boolean): void
}>()

const checked = useVModel(props, 'modelValue', emits, {
  passive: true,
  defaultValue: props.defaultValue,
})

function handleChange(event: Event) {
  emits('change', (event.target as HTMLInputElement).checked)
}
</script>

<template>
  <label
    :class="
      cn(
        'group grid grid-cols-[auto_1fr] gap-3 rounded-lg border border-border bg-background p-4 text-left shadow-sm transition-colors has-[:checked]:border-primary has-[:checked]:bg-primary/5 has-[:focus-visible]:ring-1 has-[:focus-visible]:ring-ring',
        disabled && 'cursor-not-allowed opacity-50',
        props.class,
      )
    "
  >
    <input
      :id="id"
      v-model="checked"
      class="peer sr-only"
      :disabled="disabled"
      :name="name"
      type="checkbox"
      :value="value"
      @change="handleChange"
    />
    <span
      class="mt-0.5 grid size-4 place-content-center rounded-sm border border-primary text-primary-foreground shadow-sm transition-colors peer-checked:bg-primary"
      aria-hidden="true"
    >
      <Check v-if="checked" class="size-3" />
    </span>
    <span :class="cn('min-w-0 text-sm text-foreground', contentClass)">
      <slot :checked="checked" :disabled="disabled" />
    </span>
  </label>
</template>
