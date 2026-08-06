import { ref, computed } from 'vue'
import { defineStore } from 'pinia'

export interface TextOverlay {
  id: string
  text: string
  rx: number // X-position as fraction of image width (0-1)
  ry: number // Y-position as fraction of image height (0-1)
  fontFamily: string
  fontSize: number // px at reference width 1024px, scales proportionally
  color: string
  bold: boolean
  italic: boolean
  underline: boolean
  rotation: number // degrees, -180 to 180
}

const DEFAULTS: Omit<TextOverlay, 'id'> = {
  text: 'Your Text',
  rx: 0.5,
  ry: 0.5,
  fontFamily: 'Plus Jakarta Sans',
  fontSize: 64,
  color: 'oklch(0.99 0 0)',
  bold: false,
  italic: false,
  underline: false,
  rotation: 0,
}

export const useTextOverlayStore = defineStore('textOverlays', () => {
  const overlays = ref<TextOverlay[]>([])
  const selectedId = ref<string | null>(null)

  const selectedOverlay = computed(
    () => overlays.value.find((o) => o.id === selectedId.value) ?? null,
  )

  function addOverlay() {
    const overlay: TextOverlay = {
      ...DEFAULTS,
      id: crypto.randomUUID(),
    }
    overlays.value.push(overlay)
    selectedId.value = overlay.id
    return overlay.id
  }

  function updateOverlay(id: string, patch: Partial<Omit<TextOverlay, 'id'>>) {
    const overlay = overlays.value.find((o) => o.id === id)
    if (overlay) {
      Object.assign(overlay, patch)
    }
  }

  function removeOverlay(id: string) {
    const index = overlays.value.findIndex((o) => o.id === id)
    if (index !== -1) {
      overlays.value.splice(index, 1)
      if (selectedId.value === id) {
        selectedId.value = null
      }
    }
  }

  function selectOverlay(id: string | null) {
    selectedId.value = id
  }

  function reset() {
    overlays.value = []
    selectedId.value = null
  }

  return {
    overlays,
    selectedId,
    selectedOverlay,
    addOverlay,
    updateOverlay,
    removeOverlay,
    selectOverlay,
    reset,
  }
})
