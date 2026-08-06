<script setup lang="ts">
import type { NavigationMenuIndicatorProps } from 'reka-ui'
import type { HTMLAttributes } from 'vue'
import { reactiveOmit } from '@vueuse/core'
import { NavigationMenuIndicator, useForwardProps } from 'reka-ui'
import { cn } from '@/lib/utils'

const props = defineProps<NavigationMenuIndicatorProps & { class?: HTMLAttributes['class'] }>()

const delegatedProps = reactiveOmit(props, 'class')
const forwardedProps = useForwardProps(delegatedProps)
</script>

<template>
  <NavigationMenuIndicator
    v-bind="forwardedProps"
    :class="
      cn(
        'top-full z-10 flex h-1.5 items-end justify-center overflow-hidden transition-[width,transform] duration-300 data-[state=hidden]:animate-out data-[state=visible]:animate-in data-[state=hidden]:fade-out data-[state=visible]:fade-in',
        props.class,
      )
    "
  >
    <slot>
      <div class="relative top-[60%] size-2 rotate-45 rounded-tl-sm bg-border shadow-md" />
    </slot>
  </NavigationMenuIndicator>
</template>
