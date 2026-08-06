import { useAdminRowReorder } from '@/components/ui/reorder'
import type { AdminPromptListItemDto } from '@/stores/admin/prompts'
import type { MaybeRefOrGetter } from 'vue'

interface UseAdminPromptReorderOptions {
  prompts: MaybeRefOrGetter<readonly Readonly<AdminPromptListItemDto>[]>
  reorderDisabled: MaybeRefOrGetter<boolean>
  /** Moves `sourceId` to the place currently held by `targetId`, the shared reorder request body. */
  onReorder: (sourceId: number, targetId: number) => void
}

export function useAdminPromptReorder(options: UseAdminPromptReorderOptions) {
  const reorder = useAdminRowReorder({
    items: options.prompts,
    reorderDisabled: options.reorderDisabled,
    dropTargetSelector: '[data-prompt-drop-id]',
    dropTargetIdAttribute: 'data-prompt-drop-id',
    onReorder: options.onReorder,
  })

  return {
    ...reorder,
    draggedPromptId: reorder.draggedItemId,
  }
}
