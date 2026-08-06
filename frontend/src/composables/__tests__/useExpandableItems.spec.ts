import { describe, expect, it } from 'vitest'
import { useExpandableItems } from '../useExpandableItems'

describe('useExpandableItems', () => {
  it('starts with every item collapsed by default', () => {
    const expandableItems = useExpandableItems<number>()

    expect(expandableItems.isExpanded(1)).toBe(false)
    expect(expandableItems.expandedIds.value).toEqual(new Set())
  })

  it('tracks expanded ids through explicit updates and toggles', () => {
    const expandableItems = useExpandableItems<number>({ initialExpandedIds: [1] })

    expect(expandableItems.isExpanded(1)).toBe(true)

    expandableItems.setExpanded(2, true)
    expandableItems.toggleExpanded(1)

    expect(expandableItems.isExpanded(1)).toBe(false)
    expect(expandableItems.isExpanded(2)).toBe(true)
    expect(expandableItems.expandedIds.value).toEqual(new Set([2]))
  })

  it('can expand or collapse a whole collection', () => {
    const expandableItems = useExpandableItems<string>()

    expandableItems.expandAll(['alpha', 'beta'])

    expect(expandableItems.expandedIds.value).toEqual(new Set(['alpha', 'beta']))

    expandableItems.collapseAll()

    expect(expandableItems.expandedIds.value).toEqual(new Set())
  })
})
