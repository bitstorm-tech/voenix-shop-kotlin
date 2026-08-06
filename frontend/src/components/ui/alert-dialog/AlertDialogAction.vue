<script setup lang="ts">
import type { AlertDialogActionProps } from 'reka-ui'
import type { HTMLAttributes } from 'vue'
import type { ButtonVariants } from '@/components/ui/button'
import { reactiveOmit } from '@vueuse/core'
import { AlertDialogAction, useForwardProps } from 'reka-ui'
import { buttonVariants } from '@/components/ui/button'
import { cn } from '@/lib/utils'

interface Props extends AlertDialogActionProps {
  variant?: ButtonVariants['variant']
  size?: ButtonVariants['size']
  class?: HTMLAttributes['class']
}

const props = withDefaults(defineProps<Props>(), {
  variant: 'destructive',
  size: 'default',
})

const delegatedProps = reactiveOmit(props, 'class', 'variant', 'size')
const forwardedProps = useForwardProps(delegatedProps)
</script>

<template>
  <AlertDialogAction
    v-bind="forwardedProps"
    :class="cn(buttonVariants({ variant, size }), props.class)"
  >
    <slot />
  </AlertDialogAction>
</template>
