import { watch, ref, onBeforeUnmount, type Ref, type ComputedRef } from 'vue'
import type { ModelViewerElement } from '@google/model-viewer'
import type { CropFrameTransform } from '@/stores/shop/cropFrame'
import type { TextOverlay } from '@/stores/shop/textOverlays'
import { composeImage } from '@/lib/composeImage'

export function useMugTexture(options: {
  modelViewerRef: Ref<HTMLElement | null>
  isPreviewMode: ComputedRef<boolean>
  imageUrl: ComputedRef<string | null>
  screenFrameWidth: Ref<number>
  frameAspectRatio: ComputedRef<number>
  cropTransform: ComputedRef<CropFrameTransform>
  textOverlays: ComputedRef<ReadonlyArray<TextOverlay>>
}) {
  const {
    modelViewerRef,
    isPreviewMode,
    imageUrl,
    screenFrameWidth,
    frameAspectRatio,
    cropTransform,
    textOverlays,
  } = options

  let generation = 0
  const pendingCanvas = ref<HTMLCanvasElement | null>(null)

  watch(isPreviewMode, async (preview) => {
    if (!preview) {
      if (pendingCanvas.value) {
        pendingCanvas.value.width = 0
        pendingCanvas.value.height = 0
      }
      pendingCanvas.value = null
      return
    }

    const url = imageUrl.value
    if (!url) return

    const gen = ++generation

    const canvas = await composeImage({
      imageUrl: url,
      frameAspectRatio: frameAspectRatio.value,
      cropTransform: cropTransform.value,
      screenFrameWidth: screenFrameWidth.value,
      textOverlays: textOverlays.value,
    })

    if (gen !== generation) return

    pendingCanvas.value = canvas
    tryApplyTexture()
  })

  watch(modelViewerRef, (el, prevEl) => {
    if (prevEl) prevEl.removeEventListener('load', onModelLoad)
    if (el) el.addEventListener('load', onModelLoad)
  })

  onBeforeUnmount(() => {
    modelViewerRef.value?.removeEventListener('load', onModelLoad)
  })

  function onModelLoad() {
    // Delay slightly to let model-viewer finish its internal post-load setup
    setTimeout(tryApplyTexture, 100)
  }

  async function tryApplyTexture() {
    const mv = modelViewerRef.value as ModelViewerElement | null
    const canvas = pendingCanvas.value
    if (!mv || !canvas || !mv.model) return

    try {
      const texture = await mv.createTexture(canvas.toDataURL('image/png'))
      const material =
        mv.model.materials.find((m) => m.name === 'PrintArea') ?? mv.model.materials[0]
      if (material?.pbrMetallicRoughness?.baseColorTexture) {
        material.pbrMetallicRoughness.baseColorTexture.setTexture(texture)
      }
    } catch (e) {
      console.error('Failed to apply texture to mug:', e)
    }
  }
}
