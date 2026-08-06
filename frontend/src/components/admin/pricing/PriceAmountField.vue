<script setup lang="ts">
import type { HTMLAttributes } from 'vue'
import { computed, shallowRef, watch } from 'vue'
import { Input } from '@/components/ui/input'
import { cn } from '@/lib/utils'

const props = withDefaults(
  defineProps<{
    modelValue?: string
    value?: string
    suffix: string
    editable?: boolean
    disabled?: boolean
    emphasis?: boolean
    class?: HTMLAttributes['class']
    ariaLabel?: string
    testId?: string
  }>(),
  {
    modelValue: '',
    value: '',
    editable: false,
    disabled: false,
    emphasis: false,
    ariaLabel: undefined,
    testId: undefined,
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const draftValue = shallowRef(props.modelValue)
const displayValue = computed(() => props.value || props.modelValue || '0,00')

watch(
  () => props.modelValue,
  (value) => {
    draftValue.value = value
  },
)

function updateDraft(value: string | number) {
  draftValue.value = String(value)
}

function commitDraft() {
  if (draftValue.value === props.modelValue) {
    return
  }

  emit('update:modelValue', draftValue.value)
}
</script>

<template>
  <div :class="cn('relative min-w-0', props.class)">
    <Input
      v-if="editable"
      :model-value="draftValue"
      type="text"
      inputmode="decimal"
      :aria-label="ariaLabel"
      :data-testid="testId"
      :disabled="disabled"
      :class="
        cn(
          'h-8 pr-12 text-right font-mono tabular-nums',
          emphasis && 'border-primary bg-primary/5 font-semibold text-primary',
        )
      "
      @update:model-value="updateDraft"
      @blur="commitDraft"
      @change="commitDraft"
      @keydown.enter="commitDraft"
    />
    <div
      v-else
      :aria-label="ariaLabel"
      :data-testid="testId"
      :class="
        cn(
          'flex h-8 min-w-0 items-center justify-end rounded-md border border-input bg-muted/30 px-2 pr-12 text-right font-mono text-sm tabular-nums text-muted-foreground shadow-sm',
          emphasis && 'border-primary/40 bg-primary/5 font-semibold text-foreground',
          disabled && 'opacity-75',
        )
      "
    >
      <span class="truncate">{{ displayValue }}</span>
    </div>
    <span
      class="pointer-events-none absolute inset-y-0 right-2 flex items-center text-[0.65rem] font-semibold uppercase text-muted-foreground"
    >
      {{ suffix }}
    </span>
  </div>
</template>
