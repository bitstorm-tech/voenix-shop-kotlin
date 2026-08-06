import { ref } from 'vue'
import { defineStore } from 'pinia'

export interface CropFrameTransform {
  scale: number
  panX: number
  panY: number
}

function defaultTransform(): CropFrameTransform {
  return { scale: 1, panX: 0, panY: 0 }
}

export const useCropFrameStore = defineStore('cropFrame', () => {
  const transforms = ref<Record<string, CropFrameTransform>>({})
  const lastScreenImageWidth = ref(0)

  function getTransform(id: string): CropFrameTransform {
    return transforms.value[id] ?? defaultTransform()
  }

  function setTransform(id: string, t: CropFrameTransform) {
    transforms.value[id] = { ...t }
  }

  function hasTransform(id: string): boolean {
    const t = transforms.value[id]
    if (!t) return false
    return t.scale !== 1 || t.panX !== 0 || t.panY !== 0
  }

  function resetTransform(id: string) {
    delete transforms.value[id]
  }

  function reset() {
    transforms.value = {}
    lastScreenImageWidth.value = 0
  }

  return {
    transforms,
    lastScreenImageWidth,
    getTransform,
    setTransform,
    hasTransform,
    resetTransform,
    reset,
  }
})
