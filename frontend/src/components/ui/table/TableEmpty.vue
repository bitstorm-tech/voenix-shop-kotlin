<script setup lang="ts">
import type { HTMLAttributes } from 'vue'
import { reactiveOmit } from '@vueuse/core'
import { cn } from '@/lib/utils'
import TableCell from './TableCell.vue'
import TableRow from './TableRow.vue'

const props = withDefaults(
  defineProps<{
    class?: HTMLAttributes['class']
    colspan?: number
  }>(),
  {
    colspan: 1,
  },
)

const delegatedProps = reactiveOmit(props, 'class')
</script>

<template>
  <TableRow class="hover:bg-transparent">
    <TableCell
      :class="cn('px-4 py-6 text-sm text-muted-foreground', props.class)"
      v-bind="delegatedProps"
    >
      <div class="flex items-center justify-center">
        <slot />
      </div>
    </TableCell>
  </TableRow>
</template>
