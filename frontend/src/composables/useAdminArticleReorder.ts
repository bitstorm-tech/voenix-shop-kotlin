import { useAdminRowReorder } from '@/components/ui/reorder'
import type { AdminArticleListItemDto } from '@/stores/admin/articles'
import type { MaybeRefOrGetter } from 'vue'

interface UseAdminArticleReorderOptions {
  articles: MaybeRefOrGetter<readonly Readonly<AdminArticleListItemDto>[]>
  reorderDisabled: MaybeRefOrGetter<boolean>
  onReorder: (sourceArticleId: number, targetArticleId: number) => void
}

export function useAdminArticleReorder(options: UseAdminArticleReorderOptions) {
  const reorder = useAdminRowReorder({
    items: options.articles,
    reorderDisabled: options.reorderDisabled,
    dropTargetSelector: '[data-article-drop-id]',
    dropTargetIdAttribute: 'data-article-drop-id',
    onReorder: options.onReorder,
  })

  return {
    ...reorder,
    draggedArticleId: reorder.draggedItemId,
  }
}
