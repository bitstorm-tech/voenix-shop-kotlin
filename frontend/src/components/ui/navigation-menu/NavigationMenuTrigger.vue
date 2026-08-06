<script setup lang="ts">
import type { NavigationMenuTriggerProps } from 'reka-ui'
import type { HTMLAttributes } from 'vue'
import { reactiveOmit } from '@vueuse/core'
import { ChevronDown } from 'lucide-vue-next'
import { NavigationMenuTrigger, useForwardProps } from 'reka-ui'
import { cn } from '@/lib/utils'

type NavigationMenuTriggerVariant = 'default' | 'plain'

const props = withDefaults(
  defineProps<
    NavigationMenuTriggerProps & {
      class?: HTMLAttributes['class']
      variant?: NavigationMenuTriggerVariant
    }
  >(),
  {
    variant: 'default',
  },
)

const triggerVariants: Record<NavigationMenuTriggerVariant, string> = {
  default:
    'group inline-flex h-9 items-center justify-center gap-1 rounded-md bg-background px-3 py-2 text-sm font-medium text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground focus:bg-accent focus:text-accent-foreground focus-visible:outline-none disabled:pointer-events-none disabled:opacity-50 data-[state=open]:bg-accent data-[state=open]:text-accent-foreground',
  plain:
    'group inline-flex appearance-none items-center justify-center gap-1 rounded-none border-0 bg-transparent p-0 text-sm font-medium text-foreground shadow-none transition-colors hover:bg-transparent hover:text-muted-foreground focus:bg-transparent focus:text-foreground focus-visible:outline-none disabled:pointer-events-none disabled:opacity-50 data-[state=open]:bg-transparent data-[state=open]:text-foreground',
}

const delegatedProps = reactiveOmit(props, 'class', 'variant')
const forwardedProps = useForwardProps(delegatedProps)
</script>

<template>
  <NavigationMenuTrigger
    v-bind="forwardedProps"
    :class="cn(triggerVariants[props.variant], props.class)"
  >
    <slot />
    <ChevronDown
      class="relative top-px size-3 transition duration-300 group-data-[state=open]:rotate-180"
      aria-hidden="true"
    />
  </NavigationMenuTrigger>
</template>
