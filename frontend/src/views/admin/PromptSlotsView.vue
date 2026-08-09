<script setup lang="ts">
import { computed, onMounted, shallowRef } from 'vue'
import { Plus, RefreshCw } from 'lucide-vue-next'
import AdminPromptSlotDialog from '@/components/admin/prompts/slots/AdminPromptSlotDialog.vue'
import AdminPromptSlotGroups from '@/components/admin/prompts/slots/AdminPromptSlotGroups.vue'
import AdminPromptSlotVariantDialog from '@/components/admin/prompts/slots/AdminPromptSlotVariantDialog.vue'
import AdminPageHeader from '@/components/admin/shared/AdminPageHeader.vue'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { useDialogCrud } from '@/composables/useDialogCrud'
import { useFormErrors } from '@/composables/useFormErrors'
import { useToast } from '@/composables/useToast'
import {
  type AdminPromptSlotDto,
  type AdminPromptSlotVariantDto,
  type CreateAdminPromptSlotVariantRequest,
  PromptSlotInUseError,
  PromptSlotNameConflictError,
  PromptSlotNotFoundError,
  PromptSlotValidationError,
  PromptSlotVariantInUseError,
  PromptSlotVariantNameConflictError,
  PromptSlotVariantNotFoundError,
  type SaveAdminPromptSlotRequest,
  type UpdateAdminPromptSlotVariantRequest,
  useAdminPromptSlotsStore,
} from '@/stores/admin/promptSlots'

const SLOT_IN_USE_FALLBACK = 'Prompt slot has variants and cannot be deleted.'
const SLOT_VARIANT_IN_USE_FALLBACK = 'Prompt slot variant is assigned to existing prompts.'

const promptSlotsStore = useAdminPromptSlotsStore()
const { toast } = useToast()

const initialVariantSlot = shallowRef<AdminPromptSlotDto | null>(null)

const {
  fieldErrors: slotFieldErrors,
  generalError: slotGeneralError,
  clearErrors: clearSlotErrors,
} = useFormErrors<'name'>()

const {
  fieldErrors: slotVariantFieldErrors,
  generalError: slotVariantGeneralError,
  clearErrors: clearSlotVariantErrors,
} = useFormErrors<'slot' | 'name' | 'prompt' | 'description' | 'llm'>()

async function reloadPromptSlots() {
  await Promise.all([promptSlotsStore.fetchSlots(), promptSlotsStore.fetchSlotVariants()])
}

/**
 * Where a field error of a rejected slot-variant write is shown. `slotId` carries the slot that
 * does not exist, which is a `400` field error rather than a `404`.
 */
const VARIANT_FIELD_ERROR_TARGETS: Array<
  [string, 'slot' | 'name' | 'prompt' | 'description' | 'llm']
> = [
  ['slotId', 'slot'],
  ['name', 'name'],
  ['prompt', 'prompt'],
  ['description', 'description'],
  ['llm', 'llm'],
]

const {
  isOpen: isSlotDialogOpen,
  selected: selectedSlot,
  isSaving: isSavingSlot,
  isDeleting: isDeletingSlot,
  openCreate: openNewSlotDialog,
  openEdit: openSlotDialog,
  save: saveSlot,
  deleteSelected: deleteSelectedSlot,
} = useDialogCrud<AdminPromptSlotDto, SaveAdminPromptSlotRequest>({
  errors: { generalError: slotGeneralError, clearErrors: clearSlotErrors },
  notFoundError: PromptSlotNotFoundError,
  messages: {
    notFound: {
      title: 'Prompt slot not found',
      fallbackDescription: 'The requested prompt slot does not exist.',
    },
    saveFailed: {
      title: 'Failed to save prompt slot',
      fallbackDescription: 'Failed to save prompt slot.',
    },
    deleteFailed: {
      title: 'Failed to delete prompt slot',
      fallbackDescription: 'Failed to delete prompt slot.',
    },
  },
  createEntity: (payload) => promptSlotsStore.createSlot(payload),
  updateEntity: (id, payload) => promptSlotsStore.updateSlot(id, payload),
  deleteEntity: (id) => promptSlotsStore.deleteSlot(id),
  getId: (slot) => slot.id,
  savedToast: (slot, isEdit) => ({
    title: isEdit ? 'Prompt slot saved' : 'Prompt slot created',
    description: `${slot.name} was saved.`,
  }),
  deletedToast: (slot) => ({
    title: 'Prompt slot deleted',
    description: `${slot.name} was deleted.`,
  }),
  onNotFound: () => reloadPromptSlots(),
  handleSaveError: (error) => {
    if (error instanceof PromptSlotNameConflictError) {
      slotFieldErrors.name = error.message || 'A prompt slot with this name already exists.'
      return true
    }

    if (error instanceof PromptSlotValidationError) {
      const message = error.fieldError('name')
      if (message !== null) {
        slotFieldErrors.name = message
        return true
      }
    }

    return false
  },
  handleDeleteError: (error) => {
    if (error instanceof PromptSlotInUseError) {
      slotGeneralError.value = error.message || SLOT_IN_USE_FALLBACK
      toast({
        title: 'Prompt slot cannot be deleted',
        description: slotGeneralError.value,
        variant: 'destructive',
      })
      return true
    }

    return false
  },
})

const {
  isOpen: isSlotVariantDialogOpen,
  selected: selectedSlotVariant,
  isSaving: isSavingSlotVariant,
  isDeleting: isDeletingSlotVariant,
  openCreate: openCreateSlotVariantDialog,
  openEdit: openEditSlotVariantDialog,
  save: saveSlotVariant,
  deleteSelected: deleteSelectedSlotVariant,
} = useDialogCrud<
  AdminPromptSlotVariantDto,
  CreateAdminPromptSlotVariantRequest | UpdateAdminPromptSlotVariantRequest
>({
  errors: { generalError: slotVariantGeneralError, clearErrors: clearSlotVariantErrors },
  notFoundError: PromptSlotVariantNotFoundError,
  messages: {
    notFound: {
      title: 'Prompt slot variant not found',
      fallbackDescription: 'The requested prompt slot variant does not exist.',
    },
    saveFailed: {
      title: 'Failed to save prompt slot variant',
      fallbackDescription: 'Failed to save prompt slot variant.',
    },
    deleteFailed: {
      title: 'Failed to delete prompt slot variant',
      fallbackDescription: 'Failed to delete prompt slot variant.',
    },
  },
  createEntity: (payload) =>
    promptSlotsStore.createSlotVariant(payload as CreateAdminPromptSlotVariantRequest),
  updateEntity: (id, payload) => promptSlotsStore.updateSlotVariant(id, payload),
  deleteEntity: (id) => promptSlotsStore.deleteSlotVariant(id),
  getId: (slotVariant) => slotVariant.id,
  savedToast: (slotVariant, isEdit) => ({
    title: isEdit ? 'Prompt slot variant saved' : 'Prompt slot variant created',
    description: `${slotVariant.name} was saved.`,
  }),
  deletedToast: (slotVariant) => ({
    title: 'Prompt slot variant deleted',
    description: `${slotVariant.name} was deleted.`,
  }),
  onNotFound: () => reloadPromptSlots(),
  handleSaveError: (error) => {
    if (error instanceof PromptSlotVariantNameConflictError) {
      slotVariantFieldErrors.name =
        error.message || 'A prompt slot variant with this name already exists.'
      return true
    }

    if (error instanceof PromptSlotValidationError) {
      let handled = false
      for (const [field, target] of VARIANT_FIELD_ERROR_TARGETS) {
        const message = error.fieldError(field)
        if (message !== null && slotVariantFieldErrors[target] === undefined) {
          slotVariantFieldErrors[target] = message
          handled = true
        }
      }

      return handled
    }

    return false
  },
  handleDeleteError: (error) => {
    if (error instanceof PromptSlotVariantInUseError) {
      slotVariantGeneralError.value = error.message || SLOT_VARIANT_IN_USE_FALLBACK
      toast({
        title: 'Prompt slot variant cannot be deleted',
        description: slotVariantGeneralError.value,
        variant: 'destructive',
      })
      return true
    }

    return false
  },
})

const selectedSlotVariants = computed(() => {
  if (!selectedSlot.value) {
    return []
  }

  return promptSlotsStore.variantsBySlotId[selectedSlot.value.id] ?? []
})

const canDeleteSelectedSlot = computed(
  () => !selectedSlot.value || selectedSlotVariants.value.length === 0,
)

const selectedSlotDeleteReason = computed(() =>
  canDeleteSelectedSlot.value ? null : 'Remove variants first.',
)

const selectedVariantParentSlot = computed(() => {
  if (selectedSlotVariant.value) {
    return (
      promptSlotsStore.slots.find((slot) => slot.id === selectedSlotVariant.value?.slotId) ?? null
    )
  }

  return initialVariantSlot.value
})

const canDeleteSelectedSlotVariant = computed(
  () => !selectedSlotVariant.value || selectedSlotVariant.value.assignedPromptCount === 0,
)

const selectedSlotVariantDeleteReason = computed(() =>
  canDeleteSelectedSlotVariant.value ? null : 'Remove this variant from prompts first.',
)

function openNewSlotVariantDialog(slot: AdminPromptSlotDto) {
  initialVariantSlot.value = slot
  openCreateSlotVariantDialog()
}

function openSlotVariantDialog(slotVariant: AdminPromptSlotVariantDto) {
  initialVariantSlot.value =
    promptSlotsStore.slots.find((slot) => slot.id === slotVariant.slotId) ?? null
  openEditSlotVariantDialog(slotVariant)
}

onMounted(async () => {
  await reloadPromptSlots()
})
</script>

<template>
  <section class="space-y-4">
    <AdminPageHeader title="Prompt Slots" breakpoint="lg">
      <template #actions>
        <div class="flex flex-wrap items-center gap-2">
          <Button
            type="button"
            variant="outline"
            size="sm"
            :disabled="promptSlotsStore.isLoading"
            @click="reloadPromptSlots"
          >
            <RefreshCw :class="['size-4', promptSlotsStore.isLoading && 'animate-spin']" />
            Reload
          </Button>
          <Button type="button" size="sm" @click="openNewSlotDialog">
            <Plus class="size-4" />
            New Slot
          </Button>
        </div>
      </template>
    </AdminPageHeader>

    <Alert v-if="promptSlotsStore.error" variant="destructive">
      Failed to load prompt slots. {{ promptSlotsStore.error }}
    </Alert>

    <Card
      v-else-if="promptSlotsStore.isLoading && promptSlotsStore.slots.length === 0"
      class="px-4 py-12 text-center text-sm text-muted-foreground"
    >
      Loading prompt slots...
    </Card>

    <Card
      v-else-if="promptSlotsStore.slots.length === 0"
      class="px-4 py-12 text-center text-sm text-muted-foreground"
    >
      No prompt slots found.
    </Card>

    <AdminPromptSlotGroups
      v-else
      :slots="promptSlotsStore.slots"
      :variants-by-slot-id="promptSlotsStore.variantsBySlotId"
      @edit-slot="openSlotDialog"
      @delete-slot="openSlotDialog"
      @add-variant="openNewSlotVariantDialog"
      @edit-variant="openSlotVariantDialog"
      @delete-variant="openSlotVariantDialog"
    />

    <AdminPromptSlotDialog
      v-model:open="isSlotDialogOpen"
      :slot-item="selectedSlot"
      :slots="promptSlotsStore.slots"
      :saving="isSavingSlot"
      :deleting="isDeletingSlot"
      :can-delete="canDeleteSelectedSlot"
      :delete-disabled-reason="selectedSlotDeleteReason"
      :name-error="slotFieldErrors.name ?? null"
      :general-error="slotGeneralError"
      @save="saveSlot"
      @delete="deleteSelectedSlot"
      @clear-errors="clearSlotErrors"
    />

    <AdminPromptSlotVariantDialog
      v-model:open="isSlotVariantDialogOpen"
      :slot-variant="selectedSlotVariant"
      :slot-item="selectedVariantParentSlot"
      :slot-variants="promptSlotsStore.slotVariants"
      :saving="isSavingSlotVariant"
      :deleting="isDeletingSlotVariant"
      :can-delete="canDeleteSelectedSlotVariant"
      :delete-disabled-reason="selectedSlotVariantDeleteReason"
      :slot-error="slotVariantFieldErrors.slot ?? null"
      :name-error="slotVariantFieldErrors.name ?? null"
      :prompt-error="slotVariantFieldErrors.prompt ?? null"
      :description-error="slotVariantFieldErrors.description ?? null"
      :llm-error="slotVariantFieldErrors.llm ?? null"
      :general-error="slotVariantGeneralError"
      @save="saveSlotVariant"
      @delete="deleteSelectedSlotVariant"
      @clear-errors="clearSlotVariantErrors"
    />
  </section>
</template>
