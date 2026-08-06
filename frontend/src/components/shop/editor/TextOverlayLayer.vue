<script setup lang="ts">
import type { Rect } from '@/lib/geometry'
import type { TextOverlay } from '@/stores/shop/textOverlays'
import TextOverlayItem from './TextOverlayItem.vue'

const props = defineProps<{
  imageRect: Rect
  interactive: boolean
  overlays: TextOverlay[]
  selectedId: string | null
}>()

const emit = defineEmits<{
  select: [id: string | null]
  updateOverlay: [id: string, patch: Partial<Omit<TextOverlay, 'id'>>]
}>()

function onOverlayMove(id: string, rx: number, ry: number) {
  emit('updateOverlay', id, { rx, ry })
}

function onOverlaySelect(id: string) {
  emit('select', id)
}

function onBackgroundClick(event: MouseEvent) {
  if (event.target === event.currentTarget && props.interactive) {
    emit('select', null)
  }
}
</script>

<template>
  <div
    class="absolute inset-0 overflow-hidden"
    :class="interactive ? 'pointer-events-auto' : 'pointer-events-none'"
    @click="onBackgroundClick"
  >
    <TextOverlayItem
      v-for="overlay in overlays"
      :key="overlay.id"
      :overlay="overlay"
      :image-rect="imageRect"
      :selected="overlay.id === selectedId"
      :interactive="interactive"
      @select="onOverlaySelect"
      @move="onOverlayMove"
    />
  </div>
</template>
