<script setup lang="ts">
import type { NavigationMenuSubEmits, NavigationMenuSubProps } from 'reka-ui'
import type { HTMLAttributes } from 'vue'
import { reactiveOmit } from '@vueuse/core'
import { NavigationMenuSub, useForwardPropsEmits } from 'reka-ui'
import { cn } from '@/lib/utils'

const props = defineProps<NavigationMenuSubProps & { class?: HTMLAttributes['class'] }>()
const emits = defineEmits<NavigationMenuSubEmits>()

const delegatedProps = reactiveOmit(props, 'class')
const forwarded = useForwardPropsEmits(delegatedProps, emits)
</script>

<template>
  <NavigationMenuSub v-bind="forwarded" :class="cn('relative', props.class)">
    <slot />
  </NavigationMenuSub>
</template>
