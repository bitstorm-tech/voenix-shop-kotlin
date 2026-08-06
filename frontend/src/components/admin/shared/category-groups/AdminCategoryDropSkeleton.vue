<script setup lang="ts">
import { computed } from 'vue'
import { TableCell, TableRow } from '@/components/ui/table'

interface Props {
  variant: 'category' | 'subcategory'
  testIdPrefix?: string
}

const props = withDefaults(defineProps<Props>(), {
  testIdPrefix: 'prompt',
})

const emit = defineEmits<{
  (event: 'dragOver', dragEvent: DragEvent): void
  (event: 'drop', dragEvent: DragEvent): void
}>()

const testId = computed(() =>
  props.variant === 'category'
    ? `${props.testIdPrefix}-category-drop-skeleton`
    : `${props.testIdPrefix}-subcategory-drop-skeleton`,
)
</script>

<template>
  <div
    v-if="props.variant === 'category'"
    class="overflow-hidden rounded-lg border border-dashed border-border bg-muted/20 p-4 shadow-sm"
    :data-testid="testId"
    aria-hidden="true"
    @dragover="emit('dragOver', $event)"
    @drop="emit('drop', $event)"
  >
    <div class="animate-pulse space-y-4 motion-reduce:animate-none">
      <div class="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
        <div class="flex min-w-0 items-start gap-3">
          <div class="mt-0.5 size-8 shrink-0 rounded-md bg-muted" />
          <div class="min-w-0 flex-1 space-y-2">
            <div class="h-4 w-44 max-w-full rounded-full bg-muted" />
            <div class="h-3 w-28 max-w-full rounded-full bg-muted" />
          </div>
        </div>
        <div class="flex gap-2">
          <div class="h-8 w-28 rounded-md bg-muted" />
          <div class="size-8 rounded-md bg-muted" />
          <div class="size-8 rounded-md bg-muted" />
        </div>
      </div>
      <div class="space-y-2">
        <div class="h-3 w-full rounded-full bg-muted" />
        <div class="h-3 w-4/5 rounded-full bg-muted" />
      </div>
    </div>
  </div>

  <TableRow
    v-else
    class="hover:bg-transparent"
    :data-testid="testId"
    aria-hidden="true"
    @dragover="emit('dragOver', $event)"
    @drop="emit('drop', $event)"
  >
    <TableCell colspan="5" class="px-4 py-2">
      <div
        class="overflow-hidden rounded-md border border-dashed border-border bg-muted/20 p-3 shadow-sm"
      >
        <div class="flex min-w-[38rem] animate-pulse items-center gap-3 motion-reduce:animate-none">
          <div class="size-8 shrink-0 rounded-md bg-muted" />
          <div class="h-3 w-36 rounded-full bg-muted" />
          <div class="h-6 w-20 rounded-sm bg-muted" />
          <div class="h-3 flex-1 rounded-full bg-muted" />
          <div class="h-8 w-20 rounded-md bg-muted" />
        </div>
      </div>
    </TableCell>
  </TableRow>
</template>
