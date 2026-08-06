<script setup lang="ts">
import type { AlertDialogTriggerProps } from 'reka-ui'
import type { HTMLAttributes } from 'vue'
import { reactiveOmit } from '@vueuse/core'
import { AlertDialogTrigger, useForwardProps } from 'reka-ui'
import { cn } from '@/lib/utils'

const props = defineProps<AlertDialogTriggerProps & { class?: HTMLAttributes['class'] }>()

const delegatedProps = reactiveOmit(props, 'class')
const forwardedProps = useForwardProps(delegatedProps)
</script>

<template>
  <AlertDialogTrigger v-bind="forwardedProps" :class="cn(props.class)">
    <slot />
  </AlertDialogTrigger>
</template>
