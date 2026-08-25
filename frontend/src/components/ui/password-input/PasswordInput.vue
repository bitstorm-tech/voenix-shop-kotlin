<script setup lang="ts">
import type { HTMLAttributes } from 'vue'
import { Eye, EyeOff } from 'lucide-vue-next'
import { shallowRef } from 'vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { cn } from '@/lib/utils'

defineOptions({ inheritAttrs: false })

const props = withDefaults(
  defineProps<{
    class?: HTMLAttributes['class']
    label?: string
  }>(),
  {
    label: 'Show password',
  },
)

const model = defineModel<string | number>()

const visible = shallowRef(false)
</script>

<template>
  <div class="relative">
    <Input
      v-model="model"
      v-bind="$attrs"
      :type="visible ? 'text' : 'password'"
      :class="cn('pr-10 [&::-ms-reveal]:hidden', props.class)"
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
