<script setup lang="ts">
import type { ToggleEmits, ToggleProps } from 'reka-ui'
import type { HTMLAttributes } from 'vue'
import type { ToggleVariants } from '.'
import { reactiveOmit } from '@vueuse/core'
import { Toggle, useForwardPropsEmits } from 'reka-ui'
import { cn } from '@/lib/utils'
import { toggleVariants } from '.'

interface Props extends ToggleProps {
  variant?: ToggleVariants['variant']
  size?: ToggleVariants['size']
  class?: HTMLAttributes['class']
}

const props = defineProps<Props>()
const emits = defineEmits<ToggleEmits>()

const delegatedProps = reactiveOmit(props, 'class', 'variant', 'size')
const forwarded = useForwardPropsEmits(delegatedProps, emits)
</script>

<template>
  <Toggle v-bind="forwarded" :class="cn(toggleVariants({ variant, size }), props.class)">
    <slot />
  </Toggle>
</template>
