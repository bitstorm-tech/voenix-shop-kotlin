<script setup lang="ts">
import { computed } from 'vue'
import { ChevronDown } from 'lucide-vue-next'
import { Alert } from '@/components/ui/alert'
import { CheckboxCard } from '@/components/ui/checkbox-card'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible'
import type { AdminPromptSlotDto, AdminPromptSlotVariantDto } from '@/stores/admin/promptSlots'
import { useAdminPromptSlotsStore } from '@/stores/admin/promptSlots'

interface SlotGroup {
  slotItem: AdminPromptSlotDto
  variants: AdminPromptSlotVariantDto[]
  selectedCount: number
}

const modelValue = defineModel<number[]>({ required: true })
const props = withDefaults(defineProps<{ disabled?: boolean }>(), { disabled: false })

const slotsStore = useAdminPromptSlotsStore()

const slotGroups = computed<SlotGroup[]>(() => {
  const selectedIds = new Set(modelValue.value)
  return slotsStore.slots
    .map((slotItem) => {
      const variants = slotsStore.variantsBySlotId[slotItem.id] ?? []
      return {
        slotItem,
        variants,
        selectedCount: variants.filter((variant) => selectedIds.has(variant.id)).length,
      }
    })
    .filter((group) => group.variants.length > 0)
})

function isSelected(variantId: number) {
  return modelValue.value.includes(variantId)
}

function toggleVariant(variantId: number, checked: boolean) {
  if (checked) {
    if (!modelValue.value.includes(variantId)) {
      modelValue.value = [...modelValue.value, variantId]
    }
  } else {
    modelValue.value = modelValue.value.filter((id) => id !== variantId)
  }
}
</script>

<template>
  <div
    v-if="slotsStore.isLoading"
    class="rounded-lg border border-border bg-muted/20 px-4 py-6 text-center text-sm text-muted-foreground"
  >
    Loading prompt slots...
  </div>

  <Alert v-else-if="slotsStore.error" variant="destructive">
    Failed to load prompt slots. {{ slotsStore.error }}
  </Alert>

  <p v-else-if="slotGroups.length === 0" class="text-sm text-muted-foreground">
    No prompt slot variants available yet.
  </p>

  <div v-else class="min-w-0 space-y-2">
    <Collapsible
      v-for="group in slotGroups"
      :key="group.slotItem.id"
      v-slot="{ open }"
      class="min-w-0 rounded-lg border border-border bg-muted/20"
    >
      <CollapsibleTrigger
        type="button"
        class="flex w-full min-w-0 items-center justify-between gap-3 px-4 py-3 text-left"
        :aria-label="`Toggle ${group.slotItem.name} variants`"
        :disabled="props.disabled"
      >
        <span class="min-w-0 flex-1 truncate font-medium text-foreground">
          {{ group.slotItem.name }}
        </span>
        <span class="flex shrink-0 items-center gap-2">
          <span
            class="rounded-md border border-border bg-background px-2 py-0.5 text-xs text-muted-foreground"
          >
            {{ group.selectedCount }}/{{ group.variants.length }} selected
          </span>
          <ChevronDown
            class="size-4 text-muted-foreground transition-transform"
            :class="{ 'rotate-180': open }"
          />
        </span>
      </CollapsibleTrigger>

      <CollapsibleContent>
        <ul class="space-y-3 border-t border-border px-4 py-3">
          <li v-for="variant in group.variants" :key="variant.id">
            <CheckboxCard
              :id="`prompt-slot-variant-${variant.id}`"
              class="cursor-pointer bg-background/60 p-3 shadow-none"
              content-class="block min-w-0"
              :model-value="isSelected(variant.id)"
              :disabled="props.disabled"
              @update:model-value="toggleVariant(variant.id, $event)"
            >
              <span class="block text-sm font-medium text-foreground">{{ variant.name }}</span>
              <span class="line-clamp-2 text-sm text-muted-foreground">
                {{ variant.prompt }}
              </span>
            </CheckboxCard>
          </li>
        </ul>
      </CollapsibleContent>
    </Collapsible>
  </div>
</template>
