<script setup lang="ts">
import { ref, computed, watch, nextTick } from 'vue'
import { clamp } from '@vueuse/shared'
import { useI18n } from 'vue-i18n'
import { RotateCcw, RotateCw } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from '@/components/ui/dialog'
import { type CropState } from '@/stores/shop/wizard'

const props = defineProps<{
  open: boolean
  imageSrc: string
  mimeType: string
  initialCropState?: CropState | null
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
  crop: [blob: Blob, cropState: CropState]
}>()

const { t } = useI18n()

const MIN_CROP = 50

const containerRef = ref<HTMLElement | null>(null)
const imgRef = ref<HTMLImageElement | null>(null)

const imgTransform = ref('')
const totalRotation = ref(0)

// Image rect within the container (where the rendered image actually is)
const imageRect = ref({ x: 0, y: 0, width: 0, height: 0 })

// Crop rect relative to the container
const crop = ref({ x: 0, y: 0, width: 0, height: 0 })

// Transformed relative crop coordinates to apply after rotation
const pendingRelCrop = ref<{ rx: number; ry: number; rw: number; rh: number } | null>(null)

// Interaction state
const mode = ref<'idle' | 'drag' | 'resize'>('idle')
const activeHandle = ref<string>('')
const dragStart = ref({ px: 0, py: 0, cx: 0, cy: 0, cw: 0, ch: 0 })

const cropStyle = computed(() => ({
  left: `${crop.value.x}px`,
  top: `${crop.value.y}px`,
  width: `${crop.value.width}px`,
  height: `${crop.value.height}px`,
}))

watch(
  () => props.open,
  (open) => {
    if (open) {
      mode.value = 'idle'
      totalRotation.value = props.initialCropState?.rotation ?? 0
    }
  },
)

function onImageLoad() {
  nextTick(() => computeLayout())
}

function computeLayout() {
  const container = containerRef.value
  const img = imgRef.value
  if (!container || !img) return

  const cw = container.clientWidth
  const ch = container.clientHeight
  const nw = img.naturalWidth
  const nh = img.naturalHeight

  if (nw === 0 || nh === 0) return

  // Effective dimensions: swapped when rotated 90°/270°
  const isOrtho = totalRotation.value % 180 !== 0
  const ew = isOrtho ? nh : nw
  const eh = isOrtho ? nw : nh

  const scale = Math.min(cw / ew, ch / eh)
  const rw = ew * scale
  const rh = eh * scale
  const rx = (cw - rw) / 2
  const ry = (ch - rh) / 2

  imageRect.value = { x: rx, y: ry, width: rw, height: rh }

  // CSS transform to visually rotate the image
  if (totalRotation.value === 0) {
    imgTransform.value = ''
  } else {
    const s1 = Math.min(cw / nw, ch / nh)
    const scaleFactor = scale / s1
    imgTransform.value = `rotate(${totalRotation.value}deg) scale(${scaleFactor})`
  }

  if (pendingRelCrop.value) {
    // Apply transformed crop from rotation
    const p = pendingRelCrop.value
    crop.value = {
      x: p.rx * rw + rx,
      y: p.ry * rh + ry,
      width: p.rw * rw,
      height: p.rh * rh,
    }
    pendingRelCrop.value = null
  } else if (props.initialCropState) {
    // Restore saved crop position
    crop.value = {
      x: props.initialCropState.rx * rw + rx,
      y: props.initialCropState.ry * rh + ry,
      width: props.initialCropState.rw * rw,
      height: props.initialCropState.rh * rh,
    }
  } else {
    // Default: 80% centered
    const cropW = rw * 0.8
    const cropH = rh * 0.8
    crop.value = {
      x: rx + (rw - cropW) / 2,
      y: ry + (rh - cropH) / 2,
      width: cropW,
      height: cropH,
    }
  }
}

function onCropPointerDown(e: PointerEvent) {
  if ((e.target as HTMLElement).classList.contains('crop-handle')) return
  e.preventDefault()
  mode.value = 'drag'
  ;(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)
  dragStart.value = {
    px: e.clientX,
    py: e.clientY,
    cx: crop.value.x,
    cy: crop.value.y,
    cw: crop.value.width,
    ch: crop.value.height,
  }
}

function onHandleDown(handle: string, e: PointerEvent) {
  e.preventDefault()
  e.stopPropagation()
  mode.value = 'resize'
  activeHandle.value = handle
  const cropArea = (e.target as HTMLElement).closest('.crop-area')
  if (cropArea) (cropArea as HTMLElement).setPointerCapture(e.pointerId)
  dragStart.value = {
    px: e.clientX,
    py: e.clientY,
    cx: crop.value.x,
    cy: crop.value.y,
    cw: crop.value.width,
    ch: crop.value.height,
  }
}

function onPointerMove(e: PointerEvent) {
  if (mode.value === 'idle') return
  e.preventDefault()

  const dx = e.clientX - dragStart.value.px
  const dy = e.clientY - dragStart.value.py
  const ir = imageRect.value
  const ds = dragStart.value

  if (mode.value === 'drag') {
    crop.value = {
      ...crop.value,
      x: clamp(ds.cx + dx, ir.x, ir.x + ir.width - crop.value.width),
      y: clamp(ds.cy + dy, ir.y, ir.y + ir.height - crop.value.height),
    }
  } else if (mode.value === 'resize') {
    let newX = ds.cx
    let newY = ds.cy
    let newW = ds.cw
    let newH = ds.ch

    const h = activeHandle.value
    if (h.includes('w')) {
      newX = clamp(ds.cx + dx, ir.x, ds.cx + ds.cw - MIN_CROP)
      newW = ds.cw - (newX - ds.cx)
    }
    if (h.includes('e')) {
      newW = clamp(ds.cw + dx, MIN_CROP, ir.x + ir.width - ds.cx)
    }
    if (h.includes('n')) {
      newY = clamp(ds.cy + dy, ir.y, ds.cy + ds.ch - MIN_CROP)
      newH = ds.ch - (newY - ds.cy)
    }
    if (h.includes('s')) {
      newH = clamp(ds.ch + dy, MIN_CROP, ir.y + ir.height - ds.cy)
    }

    crop.value = { x: newX, y: newY, width: newW, height: newH }
  }
}

function onPointerUp() {
  mode.value = 'idle'
  activeHandle.value = ''
}

function canvasToBlob(canvas: HTMLCanvasElement, type?: string, quality?: number): Promise<Blob> {
  return new Promise((resolve, reject) => {
    canvas.toBlob(
      (b) => {
        if (!b) return reject(new Error('Failed to create image blob'))
        resolve(b)
      },
      type,
      quality,
    )
  })
}

function rotate(angle: number) {
  // Transform current crop to relative coordinates and rotate them
  const ir = imageRect.value
  if (ir.width > 0 && ir.height > 0) {
    const relX = (crop.value.x - ir.x) / ir.width
    const relY = (crop.value.y - ir.y) / ir.height
    const relW = crop.value.width / ir.width
    const relH = crop.value.height / ir.height

    if (angle === 90) {
      pendingRelCrop.value = { rx: 1 - relY - relH, ry: relX, rw: relH, rh: relW }
    } else if (angle === -90) {
      pendingRelCrop.value = { rx: relY, ry: 1 - relX - relW, rw: relH, rh: relW }
    }
  }

  totalRotation.value = (totalRotation.value + angle + 360) % 360
  computeLayout()
}

async function applyCrop() {
  const img = imgRef.value
  if (!img) return

  const ir = imageRect.value
  const origW = img.naturalWidth
  const origH = img.naturalHeight

  const isOrtho = totalRotation.value % 180 !== 0
  const effW = isOrtho ? origH : origW
  const effH = isOrtho ? origW : origH

  const scaleX = effW / ir.width
  const scaleY = effH / ir.height

  const sourceX = (crop.value.x - ir.x) * scaleX
  const sourceY = (crop.value.y - ir.y) * scaleY
  const sourceW = crop.value.width * scaleX
  const sourceH = crop.value.height * scaleY

  const maxDim = 2048
  let outW = Math.round(sourceW)
  let outH = Math.round(sourceH)
  if (outW > maxDim || outH > maxDim) {
    const downscale = maxDim / Math.max(outW, outH)
    outW = Math.round(outW * downscale)
    outH = Math.round(outH * downscale)
  }

  const canvas = document.createElement('canvas')
  canvas.width = outW
  canvas.height = outH

  const ctx = canvas.getContext('2d')!

  if (totalRotation.value === 0) {
    ctx.drawImage(
      img,
      Math.round(sourceX),
      Math.round(sourceY),
      Math.round(sourceW),
      Math.round(sourceH),
      0,
      0,
      outW,
      outH,
    )
  } else {
    const drawScale = outW / sourceW
    ctx.scale(drawScale, drawScale)
    ctx.translate(-sourceX, -sourceY)
    ctx.translate(effW / 2, effH / 2)
    ctx.rotate((totalRotation.value * Math.PI) / 180)
    ctx.drawImage(img, -origW / 2, -origH / 2)
  }

  const blob = await canvasToBlob(canvas, 'image/webp', 0.85)
  canvas.width = 0
  canvas.height = 0

  const state: CropState = {
    rx: sourceX / effW,
    ry: sourceY / effH,
    rw: sourceW / effW,
    rh: sourceH / effH,
    rotation: totalRotation.value,
  }
  emit('crop', blob, state)
  close()
}

function close() {
  emit('update:open', false)
}
</script>

<template>
  <Dialog :open="open" @update:open="emit('update:open', $event)">
    <DialogContent class="crop-dialog gap-0 p-0 sm:max-w-2xl">
      <DialogHeader class="px-5 pt-5 pb-3 sm:px-6 sm:pt-6">
        <DialogTitle>{{ t('mugConfigurator.steps.uploadImage.cropDialog.title') }}</DialogTitle>
      </DialogHeader>

      <!-- Cropper area -->
      <div
        ref="containerRef"
        class="relative h-[50vh] max-h-[500px] touch-none select-none overflow-hidden"
        @pointermove="onPointerMove"
        @pointerup="onPointerUp"
      >
        <img
          ref="imgRef"
          :src="props.imageSrc"
          :style="imgTransform ? { transform: imgTransform } : undefined"
          class="block h-full w-full object-contain"
          draggable="false"
          @load="onImageLoad"
        />

        <!-- Crop area with shadow overlay -->
        <div
          class="crop-area absolute cursor-move border-2 border-white shadow-[0_0_0_9999px_oklch(0_0_0_/_0.5)]"
          :style="cropStyle"
          @pointerdown="onCropPointerDown"
        >
          <!-- Rule-of-thirds grid -->
          <div class="crop-grid pointer-events-none absolute inset-0">
            <div
              class="absolute left-0 right-0 h-px bg-[oklch(1_0_0_/_0.25)]"
              style="top: 33.33%"
            />
            <div
              class="absolute left-0 right-0 h-px bg-[oklch(1_0_0_/_0.25)]"
              style="top: 66.67%"
            />
            <div
              class="absolute bottom-0 top-0 w-px bg-[oklch(1_0_0_/_0.25)]"
              style="left: 33.33%"
            />
            <div
              class="absolute bottom-0 top-0 w-px bg-[oklch(1_0_0_/_0.25)]"
              style="left: 66.67%"
            />
          </div>

          <!-- Corner handles -->
          <div
            class="crop-handle crop-handle-nw absolute -left-1 -top-1 size-7 touch-none cursor-nw-resize"
            @pointerdown.stop="onHandleDown('nw', $event)"
          />
          <div
            class="crop-handle crop-handle-ne absolute -right-1 -top-1 size-7 touch-none cursor-ne-resize"
            @pointerdown.stop="onHandleDown('ne', $event)"
          />
          <div
            class="crop-handle crop-handle-sw absolute -bottom-1 -left-1 size-7 touch-none cursor-sw-resize"
            @pointerdown.stop="onHandleDown('sw', $event)"
          />
          <div
            class="crop-handle crop-handle-se absolute -bottom-1 -right-1 size-7 touch-none cursor-se-resize"
            @pointerdown.stop="onHandleDown('se', $event)"
          />

          <!-- Edge handles -->
          <div
            class="crop-handle crop-handle-n absolute -top-2 left-7 right-7 h-4 w-auto touch-none cursor-n-resize"
            @pointerdown.stop="onHandleDown('n', $event)"
          />
          <div
            class="crop-handle crop-handle-s absolute -bottom-2 left-7 right-7 h-4 w-auto touch-none cursor-s-resize"
            @pointerdown.stop="onHandleDown('s', $event)"
          />
          <div
            class="crop-handle crop-handle-e absolute -right-2 bottom-7 top-7 h-auto w-4 touch-none cursor-e-resize"
            @pointerdown.stop="onHandleDown('e', $event)"
          />
          <div
            class="crop-handle crop-handle-w absolute -left-2 bottom-7 top-7 h-auto w-4 touch-none cursor-w-resize"
            @pointerdown.stop="onHandleDown('w', $event)"
          />
        </div>
      </div>

      <!-- Controls: Rotate buttons -->
      <div class="flex items-center justify-center gap-3 px-5 py-3 sm:px-6">
        <Button variant="outline" size="sm" @click="rotate(-90)">
          <RotateCcw class="h-3.5 w-3.5" />
          {{ t('mugConfigurator.steps.uploadImage.cropDialog.rotateLeft') }}
        </Button>
        <Button variant="outline" size="sm" @click="rotate(90)">
          <RotateCw class="h-3.5 w-3.5" />
          {{ t('mugConfigurator.steps.uploadImage.cropDialog.rotateRight') }}
        </Button>
      </div>

      <DialogFooter class="px-5 pb-5 sm:px-6 sm:pb-6">
        <Button variant="ghost" @click="close">
          {{ t('mugConfigurator.steps.uploadImage.cropDialog.cancel') }}
        </Button>
        <Button @click="applyCrop">
          {{ t('mugConfigurator.steps.uploadImage.cropDialog.apply') }}
        </Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>

<style scoped>
/* CSS exception: pseudo-element handle markers keep crop hit targets readable. */
.crop-handle::before {
  content: '';
  position: absolute;
  background: white;
  border-radius: 1px;
  box-shadow: 0 1px 3px oklch(0 0 0 / 0.4);
}

/* NW handle (top-left corner lines) */
.crop-handle-nw::before {
  top: 0;
  left: 0;
  width: 20px;
  height: 3px;
}

.crop-handle-nw::after {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  width: 3px;
  height: 20px;
  background: white;
  border-radius: 1px;
  box-shadow: 0 1px 3px oklch(0 0 0 / 0.4);
}

/* NE handle (top-right corner lines) */
.crop-handle-ne::before {
  top: 0;
  right: 0;
  width: 20px;
  height: 3px;
}

.crop-handle-ne::after {
  content: '';
  position: absolute;
  top: 0;
  right: 0;
  width: 3px;
  height: 20px;
  background: white;
  border-radius: 1px;
  box-shadow: 0 1px 3px oklch(0 0 0 / 0.4);
}

/* SW handle (bottom-left corner lines) */
.crop-handle-sw::before {
  bottom: 0;
  left: 0;
  width: 20px;
  height: 3px;
}

.crop-handle-sw::after {
  content: '';
  position: absolute;
  bottom: 0;
  left: 0;
  width: 3px;
  height: 20px;
  background: white;
  border-radius: 1px;
  box-shadow: 0 1px 3px oklch(0 0 0 / 0.4);
}

/* SE handle (bottom-right corner lines) */
.crop-handle-se::before {
  bottom: 0;
  right: 0;
  width: 20px;
  height: 3px;
}

.crop-handle-se::after {
  content: '';
  position: absolute;
  bottom: 0;
  right: 0;
  width: 3px;
  height: 20px;
  background: white;
  border-radius: 1px;
  box-shadow: 0 1px 3px oklch(0 0 0 / 0.4);
}

/* Edge handles — wide invisible hit areas with a small white bar indicator */
.crop-handle-n::before {
  top: 2px;
  left: 50%;
  transform: translateX(-50%);
  width: 24px;
  height: 3px;
}

.crop-handle-s::before {
  bottom: 2px;
  left: 50%;
  transform: translateX(-50%);
  width: 24px;
  height: 3px;
}

.crop-handle-e::before {
  top: 50%;
  right: 2px;
  transform: translateY(-50%);
  width: 3px;
  height: 24px;
}

.crop-handle-w::before {
  top: 50%;
  left: 2px;
  transform: translateY(-50%);
  width: 3px;
  height: 24px;
}
</style>
