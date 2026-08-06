import {
  computed,
  onScopeDispose,
  readonly,
  shallowRef,
  toValue,
  watch,
  type MaybeRefOrGetter,
} from 'vue'

interface ReorderItem {
  id: number
}

type DropIndicatorPlacement = 'before' | 'after'

interface UseAdminRowReorderOptions<TItem extends ReorderItem> {
  items: MaybeRefOrGetter<readonly Readonly<TItem>[]>
  reorderDisabled: MaybeRefOrGetter<boolean>
  dropTargetSelector: string
  dropTargetIdAttribute: string
  onReorder: (sourceItemId: number, targetItemId: number) => void
}

const POINTER_DRAG_THRESHOLD_PX = 8

export function useAdminRowReorder<TItem extends ReorderItem>(
  options: UseAdminRowReorderOptions<TItem>,
) {
  const draggedItemId = shallowRef<number | null>(null)
  const dragOverItemId = shallowRef<number | null>(null)
  const activePointerId = shallowRef<number | null>(null)
  const pointerSourceItemId = shallowRef<number | null>(null)
  const pointerStart = shallowRef<{ x: number; y: number } | null>(null)
  const pointerCaptureElement = shallowRef<HTMLElement | null>(null)

  const currentItems = () => toValue(options.items)
  const isReorderDisabled = () => toValue(options.reorderDisabled)

  const dropIndicator = computed<{
    itemId: number
    placement: DropIndicatorPlacement
  } | null>(() => {
    if (draggedItemId.value === null || dragOverItemId.value === null) return null

    const sourceIndex = currentItems().findIndex((item) => item.id === draggedItemId.value)
    const targetIndex = currentItems().findIndex((item) => item.id === dragOverItemId.value)
    if (sourceIndex < 0 || targetIndex < 0 || sourceIndex === targetIndex) return null

    return {
      itemId: dragOverItemId.value,
      placement: sourceIndex < targetIndex ? 'after' : 'before',
    }
  })

  function itemExists(itemId: number) {
    return currentItems().some((item) => item.id === itemId)
  }

  function clearVisualDragState() {
    draggedItemId.value = null
    dragOverItemId.value = null
  }

  function releasePointerCapture(element: HTMLElement | null, pointerId: number | null) {
    if (!element || pointerId === null || !element.releasePointerCapture) return

    try {
      element.releasePointerCapture(pointerId)
    } catch {
      // Pointer capture may already have been released by the browser.
    }
  }

  function consumePointerTracking() {
    const tracking = {
      captureElement: pointerCaptureElement.value,
      pointerId: activePointerId.value,
    }
    activePointerId.value = null
    pointerSourceItemId.value = null
    pointerStart.value = null
    pointerCaptureElement.value = null
    return tracking
  }

  function clearPointerGesture({ releaseCapture = true } = {}) {
    const { captureElement, pointerId } = consumePointerTracking()
    clearVisualDragState()
    if (releaseCapture) releasePointerCapture(captureElement, pointerId)
  }

  function beginHtmlDrag(itemId: number) {
    if (isReorderDisabled() || activePointerId.value !== null || !itemExists(itemId)) return false
    draggedItemId.value = itemId
    dragOverItemId.value = null
    return true
  }

  function setDragTarget(targetItemId: number | null) {
    if (
      isReorderDisabled() ||
      draggedItemId.value === null ||
      targetItemId === null ||
      targetItemId === draggedItemId.value ||
      !itemExists(targetItemId)
    ) {
      dragOverItemId.value = null
      return false
    }
    dragOverItemId.value = targetItemId
    return true
  }

  function completeDrag(targetItemId: number | null) {
    const sourceItemId = draggedItemId.value
    const canReorder =
      !isReorderDisabled() &&
      sourceItemId !== null &&
      targetItemId !== null &&
      sourceItemId !== targetItemId &&
      itemExists(sourceItemId) &&
      itemExists(targetItemId)

    clearVisualDragState()
    if (canReorder) options.onReorder(sourceItemId, targetItemId)
  }

  function onDragStart(item: Readonly<TItem>, event: DragEvent) {
    if (!beginHtmlDrag(item.id)) {
      event.preventDefault()
      return
    }
    event.dataTransfer?.setData('text/plain', String(item.id))
    if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move'
  }

  function onDragOver(item: Readonly<TItem>, event: DragEvent) {
    if (!setDragTarget(item.id)) return
    event.preventDefault()
    if (event.dataTransfer) event.dataTransfer.dropEffect = 'move'
  }

  function onDrop(item: Readonly<TItem>, event: DragEvent) {
    event.preventDefault()
    completeDrag(item.id)
  }

  function targetItemIdAtPoint(event: PointerEvent) {
    const target = document
      .elementFromPoint(event.clientX, event.clientY)
      ?.closest<HTMLElement>(options.dropTargetSelector)
    const rawItemId = target?.getAttribute(options.dropTargetIdAttribute)
    if (!rawItemId) return null

    const itemId = Number(rawItemId)
    return Number.isSafeInteger(itemId) && itemId > 0 && itemExists(itemId) ? itemId : null
  }

  function pointerMatches(event: PointerEvent) {
    return activePointerId.value !== null && event.pointerId === activePointerId.value
  }

  function onPointerDown(item: Readonly<TItem>, event: PointerEvent) {
    if (
      isReorderDisabled() ||
      activePointerId.value !== null ||
      (event.pointerType !== 'touch' && event.pointerType !== 'pen') ||
      !event.isPrimary ||
      event.button !== 0 ||
      !itemExists(item.id)
    ) {
      return
    }

    const captureElement = event.currentTarget
    if (!(captureElement instanceof HTMLElement)) return

    event.preventDefault()
    try {
      captureElement.setPointerCapture?.(event.pointerId)
    } catch {
      return
    }

    activePointerId.value = event.pointerId
    pointerSourceItemId.value = item.id
    pointerStart.value = { x: event.clientX, y: event.clientY }
    pointerCaptureElement.value = captureElement
  }

  function onPointerMove(event: PointerEvent) {
    if (!pointerMatches(event)) return

    event.preventDefault()
    const start = pointerStart.value
    const sourceItemId = pointerSourceItemId.value
    if (!start || sourceItemId === null) {
      clearPointerGesture()
      return
    }

    if (draggedItemId.value === null) {
      const distanceSquared = (event.clientX - start.x) ** 2 + (event.clientY - start.y) ** 2
      if (distanceSquared < POINTER_DRAG_THRESHOLD_PX ** 2) return
      if (isReorderDisabled() || !itemExists(sourceItemId)) {
        clearPointerGesture()
        return
      }
      draggedItemId.value = sourceItemId
    }

    setDragTarget(targetItemIdAtPoint(event))
  }

  function onPointerUp(event: PointerEvent) {
    if (!pointerMatches(event)) return

    event.preventDefault()
    const targetItemId = draggedItemId.value === null ? null : targetItemIdAtPoint(event)
    const { captureElement, pointerId } = consumePointerTracking()
    completeDrag(targetItemId)
    releasePointerCapture(captureElement, pointerId)
  }

  function onPointerCancel(event: PointerEvent) {
    if (!pointerMatches(event)) return
    event.preventDefault()
    clearPointerGesture()
  }

  function onLostPointerCapture(event: PointerEvent) {
    if (pointerMatches(event)) clearPointerGesture({ releaseCapture: false })
  }

  function isDropIndicator(itemId: number, placement: DropIndicatorPlacement) {
    return dropIndicator.value?.itemId === itemId && dropIndicator.value.placement === placement
  }

  watch(
    () => isReorderDisabled(),
    (disabled) => {
      if (disabled) clearPointerGesture()
    },
  )
  onScopeDispose(clearPointerGesture)

  return {
    draggedItemId: readonly(draggedItemId),
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
  }
}
