<script setup lang="ts">
import { GripVertical, Pencil } from 'lucide-vue-next'
import { useI18n } from 'vue-i18n'
import { Badge, type BadgeVariants } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { TableCell, TableRow } from '@/components/ui/table'
import AdminExampleImageThumbnail from '@/components/admin/shared/AdminExampleImageThumbnail.vue'
import { derivePromptStatus } from '@/composables/useAdminPromptListFilters'
import { formatPrice } from '@/lib/formatPrice'
import { promptExampleImageUrl } from '@/lib/promptExampleImage'
import { cn } from '@/lib/utils'
import type { AdminPromptListItemDto } from '@/stores/admin/prompts'

interface Props {
  prompt: Readonly<AdminPromptListItemDto>
  dragging?: boolean
  reorderDisabled?: boolean
}

withDefaults(defineProps<Props>(), {
  dragging: false,
  reorderDisabled: false,
})

const emit = defineEmits<{
  edit: [prompt: Readonly<AdminPromptListItemDto>]
  dragStart: [prompt: Readonly<AdminPromptListItemDto>, event: DragEvent]
  dragEnd: []
  dragOver: [prompt: Readonly<AdminPromptListItemDto>, event: DragEvent]
  drop: [prompt: Readonly<AdminPromptListItemDto>, event: DragEvent]
  pointerDown: [prompt: Readonly<AdminPromptListItemDto>, event: PointerEvent]
  pointerMove: [event: PointerEvent]
  pointerUp: [event: PointerEvent]
  pointerCancel: [event: PointerEvent]
  lostPointerCapture: [event: PointerEvent]
}>()

const { t } = useI18n()

function getPromptStatus(prompt: Readonly<AdminPromptListItemDto>): {
  label: string
  variant: BadgeVariants['variant']
} {
  switch (derivePromptStatus(prompt)) {
    case 'archived':
      return { label: t('admin.prompts.table.archived'), variant: 'muted' }
    case 'active':
      return { label: t('admin.prompts.table.active'), variant: 'success' }
    case 'inactive':
      return { label: t('admin.prompts.table.inactive'), variant: 'warning' }
  }
}
</script>

<template>
  <TableRow
    :data-testid="`prompt-drop-${prompt.id}`"
    :data-prompt-drop-id="prompt.id"
    :class="cn(dragging && 'opacity-50')"
    @dragover="emit('dragOver', prompt, $event)"
    @drop="emit('drop', prompt, $event)"
  >
    <TableCell class="whitespace-nowrap">
      <div class="flex items-center gap-1">
        <Button
          type="button"
          size="icon-lg"
          variant="ghost"
          class="touch-none cursor-grab text-muted-foreground active:cursor-grabbing"
          :disabled="reorderDisabled"
          :draggable="!reorderDisabled"
          :aria-label="t('admin.prompts.table.dragPrompt', { title: prompt.title })"
          :title="t('admin.prompts.table.dragPrompt', { title: prompt.title })"
          @dragstart="emit('dragStart', prompt, $event)"
          @dragend="emit('dragEnd')"
          @pointerdown="emit('pointerDown', prompt, $event)"
          @pointermove="emit('pointerMove', $event)"
          @pointerup="emit('pointerUp', $event)"
          @pointercancel="emit('pointerCancel', $event)"
          @lostpointercapture="emit('lostPointerCapture', $event)"
        >
          <GripVertical class="size-4" />
          <span class="sr-only">{{ t('admin.prompts.table.reorderPrompt') }}</span>
        </Button>
        <span class="min-w-6 text-right text-sm tabular-nums text-muted-foreground">
          {{ prompt.position }}
        </span>
      </div>
    </TableCell>
    <TableCell class="whitespace-nowrap">
      <AdminExampleImageThumbnail
        :filename="prompt.exampleImageFilename"
        :title="prompt.title"
        :image-url="promptExampleImageUrl"
      />
    </TableCell>
    <TableCell class="min-w-60 text-foreground">{{ prompt.title }}</TableCell>
    <TableCell class="whitespace-nowrap text-muted-foreground">{{
      prompt.category.name
    }}</TableCell>
    <TableCell class="whitespace-nowrap text-muted-foreground">
      {{ prompt.subcategory?.name ?? '—' }}
    </TableCell>
    <TableCell class="whitespace-nowrap text-muted-foreground">
      {{ prompt.price ? formatPrice(prompt.price.salesTotalGross) : '—' }}
    </TableCell>
    <TableCell class="whitespace-nowrap">
      <Badge :variant="getPromptStatus(prompt).variant">{{ getPromptStatus(prompt).label }}</Badge>
    </TableCell>
    <TableCell class="whitespace-nowrap text-right">
      <Button
        variant="outline"
        size="icon-sm"
        :aria-label="t('admin.prompts.table.editPrompt', { title: prompt.title })"
        :title="t('admin.prompts.table.editPrompt', { title: prompt.title })"
        @click="emit('edit', prompt)"
      >
        <Pencil class="size-4" />
        <span class="sr-only">{{ t('admin.prompts.table.edit') }}</span>
      </Button>
    </TableCell>
  </TableRow>
</template>
