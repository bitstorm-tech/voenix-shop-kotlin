<script setup lang="ts">
import { computed, ref, shallowRef } from 'vue'
import { clamp } from '@vueuse/shared'
import type { TextOverlay } from '@/stores/shop/textOverlays'

const props = defineProps<{
  overlay: TextOverlay
  imageRect: { x: number; y: number; width: number; height: number }
  selected: boolean
  interactive: boolean
}>()

const emit = defineEmits<{
  select: [id: string]
  move: [id: string, rx: number, ry: number]
}>()

const DRAG_THRESHOLD = 4

const dragging = shallowRef(false)
const hasMoved = shallowRef(false)
const dragStart = ref({ px: 0, py: 0, rx: 0, ry: 0 })

const scaledFontSize = computed(() => props.overlay.fontSize * (props.imageRect.width / 1024))

const style = computed(() => {
  const image = props.imageRect

  return {
    left: `${image.x + props.overlay.rx * image.width}px`,
    top: `${image.y + props.overlay.ry * image.height}px`,
    fontSize: `${scaledFontSize.value}px`,
    fontFamily: props.overlay.fontFamily,
    fontWeight: props.overlay.bold ? 'bold' : 'normal',
    fontStyle: props.overlay.italic ? 'italic' : 'normal',
    textDecoration: props.overlay.underline ? 'underline' : 'none',
    color: props.overlay.color,
    transform: `translate(-50%, -50%) rotate(${props.overlay.rotation}deg)`,
  }
})

function onPointerDown(event: PointerEvent) {
  event.preventDefault()
  ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
  dragging.value = true
  hasMoved.value = false
  dragStart.value = {
    px: event.clientX,
    py: event.clientY,
    rx: props.overlay.rx,
    ry: props.overlay.ry,
  }
}

function onPointerMove(event: PointerEvent) {
  if (!dragging.value || !props.interactive) return

  event.preventDefault()

  const deltaX = event.clientX - dragStart.value.px
  const deltaY = event.clientY - dragStart.value.py

  if (!hasMoved.value && Math.abs(deltaX) < DRAG_THRESHOLD && Math.abs(deltaY) < DRAG_THRESHOLD) {
    return
  }

  hasMoved.value = true

  const image = props.imageRect
  const nextRx = clamp(dragStart.value.rx + deltaX / image.width, 0, 1)
  const nextRy = clamp(dragStart.value.ry + deltaY / image.height, 0, 1)
  emit('move', props.overlay.id, nextRx, nextRy)
}

function onPointerUp() {
  if (!dragging.value) return

  dragging.value = false
  if (!hasMoved.value) {
    emit('select', props.overlay.id)
  }
}
</script>

<template>
  <div
    class="pointer-events-auto absolute touch-none whitespace-nowrap text-white leading-[1.2] [text-shadow:0_1px_3px_oklch(0_0_0_/_0.3)]"
    :class="[
      selected
        ? 'z-[2] rounded-[2px] outline-2 outline-dashed outline-[oklch(0.61_0.19_35)] outline-offset-4'
        : 'z-[1]',
      interactive ? 'cursor-grab select-none active:cursor-grabbing' : 'cursor-pointer',
    ]"
    :style="style"
    @pointerdown="onPointerDown"
    @pointermove="onPointerMove"
    @pointerup="onPointerUp"
  >
    {{ overlay.text || '\u00A0' }}
  </div>
</template>
