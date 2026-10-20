<script setup lang="ts">
import type { Rect } from '@/lib/geometry'
import type { EditorClipart } from '@/stores/shop/editor'
import ClipartItem from './ClipartItem.vue'

const props = defineProps<{
  frameRect: Rect
  interactive: boolean
  cliparts: EditorClipart[]
  selectedId: string | null
}>()

const emit = defineEmits<{
  select: [id: string | null]
  updateClipart: [id: string, patch: Partial<Omit<EditorClipart, 'id' | 'clipartId'>>]
}>()

function onBackgroundClick(event: MouseEvent) {
  if (event.target === event.currentTarget && props.interactive) {
    emit('select', null)
  }
}
</script>

<template>
  <div
    class="absolute inset-0 isolate overflow-hidden"
    :class="interactive ? 'pointer-events-auto' : 'pointer-events-none'"
    @click="onBackgroundClick"
  >
    <ClipartItem
      v-for="clipart in cliparts"
      :key="clipart.id"
      :clipart="clipart"
      :frame-rect="frameRect"
      :selected="clipart.id === selectedId"
      :interactive="interactive"
      @select="emit('select', $event)"
      @update="(id, patch) => emit('updateClipart', id, patch)"
    />
  </div>
</template>
