<script setup lang="ts">
import type { ToastDescriptionProps } from 'reka-ui'
import type { HTMLAttributes } from 'vue'
import { ToastDescription } from 'reka-ui'
import { cn } from '@/lib/utils'

const props = defineProps<ToastDescriptionProps & { class?: HTMLAttributes['class'] }>()
</script>

<template>
  <ToastDescription :class="cn('text-sm opacity-90', props.class)">
    <slot />
  </ToastDescription>
</template>
