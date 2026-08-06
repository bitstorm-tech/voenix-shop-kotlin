<script setup lang="ts">
import type { SliderRangeProps } from 'reka-ui'
import type { HTMLAttributes } from 'vue'
import { reactiveOmit } from '@vueuse/core'
import { SliderRange, useForwardProps } from 'reka-ui'
import { cn } from '@/lib/utils'

const props = defineProps<SliderRangeProps & { class?: HTMLAttributes['class'] }>()

const delegatedProps = reactiveOmit(props, 'class')

const forwarded = useForwardProps(delegatedProps)
</script>

<template>
  <SliderRange v-bind="forwarded" :class="cn('absolute h-full bg-primary', props.class)" />
</template>
