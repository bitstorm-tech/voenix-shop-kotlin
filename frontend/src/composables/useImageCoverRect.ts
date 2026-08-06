import { onBeforeUnmount, ref, watch, type Ref } from 'vue'
import { useResizeObserver } from '@vueuse/core'
import { computeCoverRect, type Rect } from '@/lib/geometry'

export function useImageCoverRect(
  containerEl: Ref<HTMLElement | null> | (() => HTMLElement | null),
  imgEl: Ref<HTMLImageElement | null> | (() => HTMLImageElement | null),
): Ref<Rect> {
  const imageRect = ref<Rect>({ x: 0, y: 0, width: 0, height: 0 })

  function getContainer() {
    return typeof containerEl === 'function' ? containerEl() : containerEl.value
  }

  function getImg() {
    return typeof imgEl === 'function' ? imgEl() : imgEl.value
  }

  function update() {
    const container = getContainer()
    const img = getImg()
    if (!container || !img) return

    const cw = container.clientWidth
    const ch = container.clientHeight
    const nw = img.naturalWidth
    const nh = img.naturalHeight
    if (nw === 0 || nh === 0) return

    imageRect.value = computeCoverRect({ x: 0, y: 0, width: cw, height: ch }, nw / nh)
  }

  useResizeObserver(containerEl, () => update())

  watch(
    () => getImg(),
    (img, prevImg) => {
      if (prevImg) prevImg.removeEventListener('load', update)
      if (img) {
        if (img.complete) update()
        img.addEventListener('load', update)
      }
    },
  )

  onBeforeUnmount(() => {
    getImg()?.removeEventListener('load', update)
  })

  return imageRect
}
