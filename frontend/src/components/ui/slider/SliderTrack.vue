<script setup lang="ts">
import type { SliderTrackProps } from 'reka-ui'
import type { HTMLAttributes } from 'vue'
import { reactiveOmit } from '@vueuse/core'
import { SliderTrack, useForwardProps } from 'reka-ui'
import { cn } from '@/lib/utils'

const props = defineProps<SliderTrackProps & { class?: HTMLAttributes['class'] }>()

const delegatedProps = reactiveOmit(props, 'class')

const forwarded = useForwardProps(delegatedProps)
</script>

<template>
  <SliderTrack
    v-bind="forwarded"
    :class="
      cn('relative h-1.5 w-full grow overflow-hidden rounded-full bg-primary/20', props.class)
    "
  >
    <slot />
  </SliderTrack>
</template>
