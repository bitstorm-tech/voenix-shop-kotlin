<script setup lang="ts">
import type { TabsIndicatorProps } from 'reka-ui'
import type { HTMLAttributes } from 'vue'
import { reactiveOmit } from '@vueuse/core'
import { TabsIndicator, useForwardProps } from 'reka-ui'
import { cn } from '@/lib/utils'

const props = defineProps<TabsIndicatorProps & { class?: HTMLAttributes['class'] }>()

const delegatedProps = reactiveOmit(props, 'class')
const forwardedProps = useForwardProps(delegatedProps)
</script>

<template>
  <TabsIndicator
    v-bind="forwardedProps"
    :class="cn('absolute bottom-0 h-0.5 rounded-full bg-primary transition-all', props.class)"
  >
    <slot />
  </TabsIndicator>
</template>
