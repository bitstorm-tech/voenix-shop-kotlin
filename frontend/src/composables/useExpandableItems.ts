import { readonly, shallowRef } from 'vue'

export type ExpandableItemId = number | string

export interface UseExpandableItemsOptions<TId extends ExpandableItemId> {
  initialExpandedIds?: Iterable<TId>
}

export function useExpandableItems<TId extends ExpandableItemId>(
  options: UseExpandableItemsOptions<TId> = {},
) {
  const expandedIds = shallowRef(new Set(options.initialExpandedIds ?? []))

  function isExpanded(id: TId) {
    return expandedIds.value.has(id)
  }

  function setExpanded(id: TId, expanded: boolean) {
    const nextExpandedIds = new Set(expandedIds.value)

    if (expanded) {
      nextExpandedIds.add(id)
    } else {
      nextExpandedIds.delete(id)
    }

    expandedIds.value = nextExpandedIds
  }

  function toggleExpanded(id: TId) {
    setExpanded(id, !isExpanded(id))
  }

  function expandAll(ids: Iterable<TId>) {
    expandedIds.value = new Set(ids)
  }

  function collapseAll() {
    expandedIds.value = new Set()
  }

  return {
    expandedIds: readonly(expandedIds),
    isExpanded,
    setExpanded,
    toggleExpanded,
    expandAll,
    collapseAll,
  }
}
