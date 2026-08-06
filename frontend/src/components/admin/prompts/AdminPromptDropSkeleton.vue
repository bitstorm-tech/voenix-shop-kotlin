<script setup lang="ts">
import { TableCell, TableRow } from '@/components/ui/table'

defineProps<{ promptId: number }>()

const emit = defineEmits<{
  dragOver: [event: DragEvent]
  drop: [event: DragEvent]
}>()
</script>

<template>
  <TableRow
    class="hover:bg-transparent"
    data-testid="prompt-drop-skeleton"
    :data-prompt-drop-id="promptId"
    aria-hidden="true"
    @dragover="emit('dragOver', $event)"
    @drop="emit('drop', $event)"
  >
    <TableCell :colspan="8" class="px-4 py-2">
      <div class="h-12 rounded-md border border-dashed border-border bg-muted/30" />
    </TableCell>
  </TableRow>
</template>
