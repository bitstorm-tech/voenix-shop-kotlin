<script setup lang="ts">
import { computed, ref, shallowRef } from 'vue'
import { clamp } from '@vueuse/shared'
import { CLIPART_MAX_SIZE, CLIPART_MIN_SIZE, clipartUrl } from '@/lib/cliparts'
import type { Rect } from '@/lib/geometry'
import type { EditorClipart } from '@/stores/shop/editor'

const props = defineProps<{
  clipart: EditorClipart
  frameRect: Rect
  selected: boolean
  interactive: boolean
}>()

const emit = defineEmits<{
  select: [id: string]
  update: [id: string, patch: Partial<Omit<EditorClipart, 'id' | 'clipartId'>>]
}>()

const DRAG_THRESHOLD = 4

const CORNERS = [
  { id: 'top-left', class: 'left-0 top-0 cursor-nwse-resize' },
  { id: 'top-right', class: 'left-full top-0 cursor-nesw-resize' },
  { id: 'bottom-left', class: 'left-0 top-full cursor-nesw-resize' },
  { id: 'bottom-right', class: 'left-full top-full cursor-nwse-resize' },
] as const

const rootRef = ref<HTMLElement | null>(null)
const mode = shallowRef<'move' | 'resize' | null>(null)
const hasMoved = shallowRef(false)
const dragStart = ref({ px: 0, py: 0, rx: 0, ry: 0 })
/** The clipart's centre in viewport pixels, measured when a resize starts. */
const resizeCenter = ref({ x: 0, y: 0 })

const imageUrl = computed(() => clipartUrl(props.clipart.clipartId))

const style = computed(() => {
  const frame = props.frameRect
  const edge = props.clipart.size * frame.width

  return {
    left: `${frame.x + props.clipart.rx * frame.width}px`,
    top: `${frame.y + props.clipart.ry * frame.height}px`,
    width: `${edge}px`,
    height: `${edge}px`,
  }
})

const showHandles = computed(() => props.selected && props.interactive)

function onPointerDown(event: PointerEvent) {
  event.preventDefault()
  ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
  mode.value = 'move'
  hasMoved.value = false
  dragStart.value = {
    px: event.clientX,
    py: event.clientY,
    rx: props.clipart.rx,
    ry: props.clipart.ry,
  }
}

function onPointerMove(event: PointerEvent) {
  if (mode.value !== 'move' || !props.interactive) return

  event.preventDefault()

  const deltaX = event.clientX - dragStart.value.px
  const deltaY = event.clientY - dragStart.value.py

  if (!hasMoved.value && Math.abs(deltaX) < DRAG_THRESHOLD && Math.abs(deltaY) < DRAG_THRESHOLD) {
    return
  }

  hasMoved.value = true

  const frame = props.frameRect
  emit('update', props.clipart.id, {
    rx: clamp(dragStart.value.rx + deltaX / frame.width, 0, 1),
    ry: clamp(dragStart.value.ry + deltaY / frame.height, 0, 1),
  })
}

function onPointerUp() {
  if (mode.value !== 'move') return

  mode.value = null
  if (!hasMoved.value) {
    emit('select', props.clipart.id)
  }
}

/** A corner handle scales the clipart around its centre: the corner follows the pointer. */
function onResizeStart(event: PointerEvent) {
  event.preventDefault()
  event.stopPropagation()
  ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)

  const box = rootRef.value!.getBoundingClientRect()
  resizeCenter.value = { x: box.left + box.width / 2, y: box.top + box.height / 2 }
  mode.value = 'resize'
}

function onResizeMove(event: PointerEvent) {
  if (mode.value !== 'resize') return

  event.preventDefault()
  event.stopPropagation()

  const halfEdge = Math.max(
    Math.abs(event.clientX - resizeCenter.value.x),
    Math.abs(event.clientY - resizeCenter.value.y),
  )
  const size = clamp(
    (2 * halfEdge) / (props.frameRect.width || 1),
    CLIPART_MIN_SIZE,
    CLIPART_MAX_SIZE,
  )
  emit('update', props.clipart.id, { size })
}

function onResizeEnd(event: PointerEvent) {
  if (mode.value !== 'resize') return

  event.stopPropagation()
  mode.value = null
}
</script>

<template>
  <div
    ref="rootRef"
    class="pointer-events-auto absolute -translate-x-1/2 -translate-y-1/2 touch-none"
    :class="[
      selected
        ? 'z-[2] outline-2 outline-dashed outline-[oklch(0.61_0.19_35)] outline-offset-2'
        : 'z-[1]',
      interactive ? 'cursor-grab select-none active:cursor-grabbing' : 'cursor-pointer',
    ]"
    :style="style"
    data-testid="editor-clipart"
    :data-clipart-id="clipart.clipartId"
    @pointerdown="onPointerDown"
    @pointermove="onPointerMove"
    @pointerup="onPointerUp"
    @pointercancel="onPointerUp"
  >
    <img :src="imageUrl" alt="" class="block size-full select-none" draggable="false" />
    <template v-if="showHandles">
      <div
        v-for="corner in CORNERS"
        :key="corner.id"
        class="absolute grid size-7 -translate-x-1/2 -translate-y-1/2 touch-none place-items-center"
        :class="corner.class"
        :data-testid="`editor-clipart-resize-${corner.id}`"
        @pointerdown="onResizeStart"
        @pointermove="onResizeMove"
        @pointerup="onResizeEnd"
        @pointercancel="onResizeEnd"
      >
        <span
          class="block size-3.5 rounded-full border-2 border-[oklch(0.61_0.19_35)] bg-white shadow-sm"
        />
      </div>
    </template>
  </div>
</template>
