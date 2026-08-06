<script setup lang="ts">
import AdminPromptSlotGroup from './AdminPromptSlotGroup.vue'
import { useExpandableItems } from '@/composables/useExpandableItems'
import type { AdminPromptSlotDto, AdminPromptSlotVariantDto } from '@/stores/admin/promptSlots'

interface Props {
  slots: AdminPromptSlotDto[]
  variantsBySlotId: Record<number, AdminPromptSlotVariantDto[]>
}

defineProps<Props>()

const emit = defineEmits<{
  (event: 'editSlot', slot: AdminPromptSlotDto): void
  (event: 'deleteSlot', slot: AdminPromptSlotDto): void
  (event: 'addVariant', slot: AdminPromptSlotDto): void
  (event: 'editVariant', variant: AdminPromptSlotVariantDto): void
  (event: 'deleteVariant', variant: AdminPromptSlotVariantDto): void
}>()

const { isExpanded: isSlotExpanded, setExpanded: setSlotExpanded } = useExpandableItems<number>()
</script>

<template>
  <div class="space-y-3">
    <AdminPromptSlotGroup
      v-for="promptSlot in slots"
      :key="promptSlot.id"
      :slot-item="promptSlot"
      :variants="variantsBySlotId[promptSlot.id] ?? []"
      :expanded="isSlotExpanded(promptSlot.id)"
      @update:expanded="setSlotExpanded(promptSlot.id, $event)"
      @edit-slot="emit('editSlot', $event)"
      @delete-slot="emit('deleteSlot', $event)"
      @add-variant="emit('addVariant', $event)"
      @edit-variant="emit('editVariant', $event)"
      @delete-variant="emit('deleteVariant', $event)"
    />
  </div>
</template>
