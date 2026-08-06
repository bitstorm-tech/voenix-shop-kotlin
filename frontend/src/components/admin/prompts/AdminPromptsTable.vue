<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import AdminPromptDropSkeleton from './AdminPromptDropSkeleton.vue'
import AdminPromptRow from './AdminPromptRow.vue'
import { Card } from '@/components/ui/card'
import { Table, TableBody, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useAdminPromptReorder } from '@/composables/useAdminPromptReorder'
import type { AdminPromptListItemDto } from '@/stores/admin/prompts'

interface Props {
  prompts: readonly Readonly<AdminPromptListItemDto>[]
  reordering?: boolean
  reorderDisabled?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  reordering: false,
  reorderDisabled: false,
})

const emit = defineEmits<{
  edit: [prompt: Readonly<AdminPromptListItemDto>]
  reorderPrompts: [sourcePromptId: number, targetPromptId: number]
}>()

const { t } = useI18n()
const isReorderDisabled = computed(() => props.reordering || props.reorderDisabled)
const {
  draggedPromptId,
  clearVisualDragState,
  isDropIndicator,
  onDragOver,
  onDragStart,
  onDrop,
  onLostPointerCapture,
  onPointerCancel,
  onPointerDown,
  onPointerMove,
  onPointerUp,
} = useAdminPromptReorder({
  prompts: () => props.prompts,
  reorderDisabled: isReorderDisabled,
  onReorder: (sourcePromptId, targetPromptId) => {
    emit('reorderPrompts', sourcePromptId, targetPromptId)
  },
})
</script>

<template>
  <Card class="overflow-hidden" :aria-busy="isReorderDisabled">
    <div
      v-if="reordering"
      class="border-b border-border bg-muted/20 px-4 py-2 text-sm text-muted-foreground"
      role="status"
    >
      {{ t('admin.prompts.savingOrder') }}
    </div>

    <Table class="min-w-[62rem]">
      <TableHeader>
        <TableRow>
          <TableHead class="w-24">{{ t('admin.prompts.table.order') }}</TableHead>
          <TableHead class="w-14">{{ t('admin.prompts.table.image') }}</TableHead>
          <TableHead>{{ t('admin.prompts.table.title') }}</TableHead>
          <TableHead>{{ t('admin.prompts.table.category') }}</TableHead>
          <TableHead>{{ t('admin.prompts.table.subcategory') }}</TableHead>
          <TableHead>{{ t('admin.prompts.table.price') }}</TableHead>
          <TableHead>{{ t('admin.prompts.table.status') }}</TableHead>
          <TableHead class="text-right">{{ t('admin.prompts.table.actions') }}</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        <template v-for="prompt in prompts" :key="prompt.id">
          <AdminPromptDropSkeleton
            v-if="isDropIndicator(prompt.id, 'before')"
            :prompt-id="prompt.id"
            @drag-over="onDragOver(prompt, $event)"
            @drop="onDrop(prompt, $event)"
          />
          <AdminPromptRow
            :prompt="prompt"
            :dragging="draggedPromptId === prompt.id"
            :reorder-disabled="isReorderDisabled"
            @edit="emit('edit', $event)"
            @drag-start="onDragStart"
            @drag-end="clearVisualDragState"
            @drag-over="onDragOver"
            @drop="onDrop"
            @pointer-down="onPointerDown"
            @pointer-move="onPointerMove"
            @pointer-up="onPointerUp"
            @pointer-cancel="onPointerCancel"
            @lost-pointer-capture="onLostPointerCapture"
          />
          <AdminPromptDropSkeleton
            v-if="isDropIndicator(prompt.id, 'after')"
            :prompt-id="prompt.id"
            @drag-over="onDragOver(prompt, $event)"
            @drop="onDrop(prompt, $event)"
          />
        </template>
      </TableBody>
    </Table>
  </Card>
</template>
