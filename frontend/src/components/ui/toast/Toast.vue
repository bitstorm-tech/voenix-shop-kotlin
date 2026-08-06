<script setup lang="ts">
import type { ToastRootEmits, ToastRootProps } from 'reka-ui'
import type { HTMLAttributes } from 'vue'
import type { VariantProps } from 'class-variance-authority'
import { ToastRoot } from 'reka-ui'
import { useForwardPropsEmits } from 'reka-ui'
import { cn } from '@/lib/utils'
import { toastVariants } from './variants'

const props = defineProps<
  ToastRootProps & {
    class?: HTMLAttributes['class']
    variant?: VariantProps<typeof toastVariants>['variant']
  }
>()

const emits = defineEmits<ToastRootEmits>()

const forwarded = useForwardPropsEmits(props, emits)
</script>

<template>
  <ToastRoot v-bind="forwarded" :class="cn(toastVariants({ variant: props.variant }), props.class)">
    <slot />
  </ToastRoot>
</template>
