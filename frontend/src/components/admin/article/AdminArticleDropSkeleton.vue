<script setup lang="ts">
import { computed } from 'vue'
import { TableCell, TableRow } from '@/components/ui/table'

const props = withDefaults(
  defineProps<{
    articleId: number
    /** Whether the table this skeleton sits in shows the t-shirt sync column — see `AdminArticlesTable`. */
    syncColumn?: boolean
  }>(),
  { syncColumn: false },
)

/** The skeleton spans the whole row, so it counts the columns the table actually renders. */
const columnCount = computed(() => (props.syncColumn ? 9 : 8))

const emit = defineEmits<{
  dragOver: [event: DragEvent]
  drop: [event: DragEvent]
}>()
</script>

<template>
  <TableRow
    class="hover:bg-transparent"
    data-testid="article-drop-skeleton"
    :data-article-drop-id="articleId"
    aria-hidden="true"
    @dragover="emit('dragOver', $event)"
    @drop="emit('drop', $event)"
  >
    <TableCell :colspan="columnCount" class="px-4 py-2">
      <div class="h-12 rounded-md border border-dashed border-border bg-muted/30" />
    </TableCell>
  </TableRow>
</template>
