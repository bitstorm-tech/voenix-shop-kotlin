<script setup lang="ts">
import { clampCropTransform } from '@/lib/cropTransform'
import type { Rect } from '@/lib/geometry'
import type { CropFrameTransform } from '@/stores/shop/cropFrame'

const props = withDefaults(
  defineProps<{
    imageRect: Rect
    frameRect: Rect
    active: boolean
    interactive?: boolean
    transform: CropFrameTransform
  }>(),
  {
    interactive: true,
  },
)

const emit = defineEmits<{ 'update:transform': [transform: CropFrameTransform] }>()

function emitClamped(scale: number, panX: number, panY: number) {
  emit(
    'update:transform',
    clampCropTransform({ scale, panX, panY }, props.imageRect, props.frameRect),
  )
}

let dragStart: { x: number; y: number; panX: number; panY: number } | null = null

function onPointerDown(event: PointerEvent) {
  if (!props.active || !props.interactive) return
  ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
  dragStart = {
    x: event.clientX,
    y: event.clientY,
    panX: props.transform.panX,
    panY: props.transform.panY,
  }
}

function onPointerMove(event: PointerEvent) {
  if (!dragStart || !props.active || !props.interactive) return
  if (pointers.size >= 2) return

  const deltaX = event.clientX - dragStart.x
  const deltaY = event.clientY - dragStart.y
  emitClamped(props.transform.scale, dragStart.panX + deltaX, dragStart.panY + deltaY)
}

function onPointerUp(event: PointerEvent) {
  ;(event.currentTarget as HTMLElement).releasePointerCapture(event.pointerId)
  dragStart = null
}

function onWheel(event: WheelEvent) {
  if (!props.active || !props.interactive) return

  event.preventDefault()
  const factor = event.deltaY > 0 ? 0.95 : 1.05
  emitClamped(props.transform.scale * factor, props.transform.panX, props.transform.panY)
}

const pointers = new Map<number, { x: number; y: number }>()
let pinchStartDist = 0
let pinchStartScale = 1
let pinchStartMid = { x: 0, y: 0 }
let pinchStartPan = { panX: 0, panY: 0 }

function getDistance(a: { x: number; y: number }, b: { x: number; y: number }) {
  return Math.hypot(a.x - b.x, a.y - b.y)
}

function getMidpoint(a: { x: number; y: number }, b: { x: number; y: number }) {
  return { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 }
}

function getTwoPointers(): [{ x: number; y: number }, { x: number; y: number }] | null {
  const values = [...pointers.values()]
  if (values.length < 2) return null

  return [values[0]!, values[1]!]
}

function onTouchPointerDown(event: PointerEvent) {
  if (!props.interactive) return

  pointers.set(event.pointerId, { x: event.clientX, y: event.clientY })
  const pair = getTwoPointers()
  if (!pair) return

  pinchStartDist = getDistance(pair[0], pair[1])
  pinchStartScale = props.transform.scale
  pinchStartMid = getMidpoint(pair[0], pair[1])
  pinchStartPan = { panX: props.transform.panX, panY: props.transform.panY }
}

function onTouchPointerMove(event: PointerEvent) {
  if (!props.interactive) return

  pointers.set(event.pointerId, { x: event.clientX, y: event.clientY })
  const pair = getTwoPointers()
  if (!pair) return

  const distance = getDistance(pair[0], pair[1])
  const midpoint = getMidpoint(pair[0], pair[1])
  const scaleFactor = distance / pinchStartDist
  const deltaX = midpoint.x - pinchStartMid.x
  const deltaY = midpoint.y - pinchStartMid.y
  emitClamped(
    pinchStartScale * scaleFactor,
    pinchStartPan.panX + deltaX,
    pinchStartPan.panY + deltaY,
  )
}

function onTouchPointerUp(event: PointerEvent) {
  if (!props.interactive) return

  pointers.delete(event.pointerId)
}

function onCombinedPointerDown(event: PointerEvent) {
  onPointerDown(event)
  onTouchPointerDown(event)
}

function onCombinedPointerMove(event: PointerEvent) {
  onPointerMove(event)
  onTouchPointerMove(event)
}

function onCombinedPointerUp(event: PointerEvent) {
  onPointerUp(event)
  onTouchPointerUp(event)
}
</script>

<template>
  <div
    v-if="active"
    class="absolute inset-0 z-10 select-none"
    :class="
      interactive
        ? 'pointer-events-auto cursor-grab touch-none active:cursor-grabbing'
        : 'pointer-events-none'
    "
    data-testid="crop-frame-layer"
    @pointerdown="onCombinedPointerDown"
    @pointermove="onCombinedPointerMove"
    @pointerup="onCombinedPointerUp"
    @pointercancel="onCombinedPointerUp"
    @wheel="onWheel"
  >
    <div
      class="pointer-events-none absolute box-border rounded-[1px] border-2 border-white"
      :style="{
        left: `${frameRect.x}px`,
        top: `${frameRect.y}px`,
        width: `${frameRect.width}px`,
        height: `${frameRect.height}px`,
      }"
    />
  </div>
</template>
