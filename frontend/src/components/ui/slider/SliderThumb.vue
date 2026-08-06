<script setup lang="ts">
import type { SliderThumbProps } from 'reka-ui'
import type { HTMLAttributes } from 'vue'
import { reactiveOmit } from '@vueuse/core'
import { SliderThumb, useForwardProps } from 'reka-ui'
import { cn } from '@/lib/utils'

const props = defineProps<SliderThumbProps & { class?: HTMLAttributes['class'] }>()

const delegatedProps = reactiveOmit(props, 'class')

const forwarded = useForwardProps(delegatedProps)
</script>

<template>
  <SliderThumb
    v-bind="forwarded"
    :class="
      cn(
        'block h-5 w-5 rounded-full border-2 border-primary bg-background shadow transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring',
        props.class,
      )
    "
  />
</template>
