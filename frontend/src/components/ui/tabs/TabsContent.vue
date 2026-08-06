<script setup lang="ts">
import type { TabsContentProps } from 'reka-ui'
import type { HTMLAttributes } from 'vue'
import { reactiveOmit } from '@vueuse/core'
import { TabsContent, useForwardProps } from 'reka-ui'
import { cn } from '@/lib/utils'

const props = defineProps<TabsContentProps & { class?: HTMLAttributes['class'] }>()

const delegatedProps = reactiveOmit(props, 'class')
const forwardedProps = useForwardProps(delegatedProps)
</script>

<template>
  <TabsContent
    v-bind="forwardedProps"
    :class="
      cn(
        'mt-4 focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring',
        props.class,
      )
    "
  >
    <slot />
  </TabsContent>
</template>
