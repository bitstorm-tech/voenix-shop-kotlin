<script setup lang="ts">
import type { ToastActionProps } from 'reka-ui'
import type { HTMLAttributes } from 'vue'
import { ToastAction } from 'reka-ui'
import { cn } from '@/lib/utils'

const props = defineProps<ToastActionProps & { class?: HTMLAttributes['class'] }>()
</script>

<template>
  <ToastAction
    v-bind="props"
    :class="
      cn(
        'inline-flex h-8 shrink-0 cursor-pointer items-center justify-center rounded-md border bg-transparent px-3 text-sm font-medium ring-offset-background transition-colors hover:bg-secondary focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2 disabled:pointer-events-none disabled:opacity-50',
        props.class,
      )
    "
  >
    <slot />
  </ToastAction>
</template>
