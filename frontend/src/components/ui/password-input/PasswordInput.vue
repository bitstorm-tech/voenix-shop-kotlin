<script setup lang="ts">
import type { HTMLAttributes } from 'vue'
import { Eye, EyeOff } from 'lucide-vue-next'
import { shallowRef } from 'vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { cn } from '@/lib/utils'

const props = withDefaults(
  defineProps<{
    modelValue?: string | number
    class?: HTMLAttributes['class']
    label?: string
  }>(),
  {
    label: 'Show password',
  },
)

const emits = defineEmits<{
  (e: 'update:modelValue', payload: string | number): void
}>()

defineOptions({ inheritAttrs: false })

const visible = shallowRef(false)
</script>

<template>
  <div class="relative">
    <Input
      :model-value="props.modelValue"
      v-bind="$attrs"
      :type="visible ? 'text' : 'password'"
      :class="cn('pr-10 [&::-ms-reveal]:hidden', props.class)"
      @update:model-value="emits('update:modelValue', $event)"
    />
    <Button
      type="button"
      variant="ghost"
      size="icon"
      class="absolute inset-y-0 right-0 text-muted-foreground"
      :aria-label="label"
      :aria-pressed="visible"
      @pointerdown.prevent
      @click="visible = !visible"
    >
      <EyeOff v-if="visible" aria-hidden="true" />
      <Eye v-else aria-hidden="true" />
    </Button>
  </div>
</template>
