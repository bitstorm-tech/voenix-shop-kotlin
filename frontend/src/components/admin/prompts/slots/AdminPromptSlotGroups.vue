<script setup lang="ts">
import AdminPromptSlotTypeGroup from './AdminPromptSlotTypeGroup.vue'
import { useExpandableItems } from '@/composables/useExpandableItems'
import type { AdminPromptSlotTypeDto, AdminPromptSlotVariantDto } from '@/stores/admin/promptSlots'

interface Props {
  slotTypes: AdminPromptSlotTypeDto[]
  variantsBySlotTypeId: Record<number, AdminPromptSlotVariantDto[]>
}

defineProps<Props>()

const emit = defineEmits<{
  (event: 'editSlotType', slotType: AdminPromptSlotTypeDto): void
  (event: 'deleteSlotType', slotType: AdminPromptSlotTypeDto): void
  (event: 'addVariant', slotType: AdminPromptSlotTypeDto): void
  (event: 'editVariant', variant: AdminPromptSlotVariantDto): void
  (event: 'deleteVariant', variant: AdminPromptSlotVariantDto): void
}>()

const { isExpanded: isSlotTypeExpanded, setExpanded: setSlotTypeExpanded } =
  useExpandableItems<number>()
</script>

<template>
  <div class="space-y-3">
    <AdminPromptSlotTypeGroup
      v-for="slotType in slotTypes"
      :key="slotType.id"
      :slot-type="slotType"
      :variants="variantsBySlotTypeId[slotType.id] ?? []"
      :expanded="isSlotTypeExpanded(slotType.id)"
      @update:expanded="setSlotTypeExpanded(slotType.id, $event)"
      @edit-slot-type="emit('editSlotType', $event)"
      @delete-slot-type="emit('deleteSlotType', $event)"
      @add-variant="emit('addVariant', $event)"
      @edit-variant="emit('editVariant', $event)"
      @delete-variant="emit('deleteVariant', $event)"
    />
  </div>
</template>
