<script setup lang="ts">
import { computed, onMounted, shallowRef } from 'vue'
import { Plus, RefreshCw } from 'lucide-vue-next'
import AdminPromptSlotGroups from '@/components/admin/prompts/slots/AdminPromptSlotGroups.vue'
import AdminPromptSlotTypeDialog from '@/components/admin/prompts/slots/AdminPromptSlotTypeDialog.vue'
import AdminPromptSlotVariantDialog from '@/components/admin/prompts/slots/AdminPromptSlotVariantDialog.vue'
import AdminPageHeader from '@/components/admin/shared/AdminPageHeader.vue'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { useDialogCrud } from '@/composables/useDialogCrud'
import { useFormErrors } from '@/composables/useFormErrors'
import { useToast } from '@/composables/useToast'
import {
  type AdminPromptSlotTypeDto,
  type AdminPromptSlotVariantDetailDto,
  type CreateAdminPromptSlotTypeRequest,
  type CreateAdminPromptSlotVariantRequest,
  PromptSlotTypeInUseError,
  PromptSlotTypeNameConflictError,
  PromptSlotTypeNotFoundError,
  PromptSlotVariantInUseError,
  PromptSlotVariantNameConflictError,
  PromptSlotVariantNotFoundError,
  PromptSlotVariantSlotTypeNotFoundError,
  type UpdateAdminPromptSlotTypeRequest,
  type UpdateAdminPromptSlotVariantRequest,
  useAdminPromptSlotsStore,
} from '@/stores/admin/promptSlots'

const SLOT_TYPE_IN_USE_FALLBACK = 'Prompt slot type has variants and cannot be deleted.'
const SLOT_VARIANT_IN_USE_FALLBACK = 'Prompt slot variant is assigned to existing prompts.'

const promptSlotsStore = useAdminPromptSlotsStore()
const { toast } = useToast()

const initialVariantSlotType = shallowRef<AdminPromptSlotTypeDto | null>(null)

const {
  fieldErrors: slotTypeFieldErrors,
  generalError: slotTypeGeneralError,
  clearErrors: clearSlotTypeErrors,
} = useFormErrors<'name'>()

const {
  fieldErrors: slotVariantFieldErrors,
  generalError: slotVariantGeneralError,
  clearErrors: clearSlotVariantErrors,
} = useFormErrors<'slotType' | 'name' | 'prompt' | 'description' | 'llm'>()

async function reloadPromptSlots() {
  await Promise.all([promptSlotsStore.fetchSlotTypes(), promptSlotsStore.fetchSlotVariants()])
}

const {
  isOpen: isSlotTypeDialogOpen,
  selected: selectedSlotType,
  isSaving: isSavingSlotType,
  isDeleting: isDeletingSlotType,
  openCreate: openNewSlotTypeDialog,
  openEdit: openSlotTypeDialog,
  save: saveSlotType,
  deleteSelected: deleteSelectedSlotType,
} = useDialogCrud<
  AdminPromptSlotTypeDto,
  CreateAdminPromptSlotTypeRequest | UpdateAdminPromptSlotTypeRequest
>({
  errors: { generalError: slotTypeGeneralError, clearErrors: clearSlotTypeErrors },
  notFoundError: PromptSlotTypeNotFoundError,
  messages: {
    notFound: {
      title: 'Prompt slot type not found',
      fallbackDescription: 'The requested prompt slot type does not exist.',
    },
    saveFailed: {
      title: 'Failed to save prompt slot type',
      fallbackDescription: 'Failed to save prompt slot type.',
    },
    deleteFailed: {
      title: 'Failed to delete prompt slot type',
      fallbackDescription: 'Failed to delete prompt slot type.',
    },
  },
  createEntity: (payload) => promptSlotsStore.createSlotType(payload),
  updateEntity: (id, payload) => promptSlotsStore.updateSlotType(id, payload),
  deleteEntity: (id) => promptSlotsStore.deleteSlotType(id),
  getId: (slotType) => slotType.id,
  savedToast: (slotType, isEdit) => ({
    title: isEdit ? 'Prompt slot type saved' : 'Prompt slot type created',
    description: `${slotType.name} was saved.`,
  }),
  deletedToast: (slotType) => ({
    title: 'Prompt slot type deleted',
    description: `${slotType.name} was deleted.`,
  }),
  onNotFound: () => reloadPromptSlots(),
  handleSaveError: (error) => {
    if (error instanceof PromptSlotTypeNameConflictError) {
      slotTypeFieldErrors.name =
        error.message || 'A prompt slot type with this name already exists.'
      return true
    }

    return false
  },
  handleDeleteError: (error) => {
    if (error instanceof PromptSlotTypeInUseError) {
      slotTypeGeneralError.value = error.message || SLOT_TYPE_IN_USE_FALLBACK
      toast({
        title: 'Prompt slot type cannot be deleted',
        description: slotTypeGeneralError.value,
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
  AdminPromptSlotVariantDetailDto,
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
  handleSaveError: async (error, { close }) => {
    if (error instanceof PromptSlotVariantNameConflictError) {
      slotVariantFieldErrors.name =
        error.message || 'A prompt slot variant with this name already exists in this slot type.'
      return true
    }

    if (error instanceof PromptSlotVariantSlotTypeNotFoundError) {
      toast({
        title: 'Prompt slot type not found',
        description: error.message || 'The selected prompt slot type does not exist.',
        variant: 'destructive',
      })
      initialVariantSlotType.value = null
      close()
      await reloadPromptSlots()
      return true
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

const selectedSlotTypeVariants = computed(() => {
  if (!selectedSlotType.value) {
    return []
  }

  return promptSlotsStore.variantsBySlotTypeId[selectedSlotType.value.id] ?? []
})

const canDeleteSelectedSlotType = computed(
  () => !selectedSlotType.value || selectedSlotTypeVariants.value.length === 0,
)

const selectedSlotTypeDeleteReason = computed(() =>
  canDeleteSelectedSlotType.value ? null : 'Remove variants first.',
)

const selectedVariantParentSlotType = computed(() => {
  if (selectedSlotVariant.value) {
    return (
      promptSlotsStore.slotTypes.find(
        (slotType) => slotType.id === selectedSlotVariant.value?.slotType.id,
      ) ?? null
    )
  }

  return initialVariantSlotType.value
})

const canDeleteSelectedSlotVariant = computed(
  () => !selectedSlotVariant.value || selectedSlotVariant.value.assignedPromptCount === 0,
)

const selectedSlotVariantDeleteReason = computed(() =>
  canDeleteSelectedSlotVariant.value ? null : 'Remove this variant from prompts first.',
)

function openNewSlotVariantDialog(slotType: AdminPromptSlotTypeDto) {
  initialVariantSlotType.value = slotType
  openCreateSlotVariantDialog()
}

function openSlotVariantDialog(slotVariant: AdminPromptSlotVariantDetailDto) {
  initialVariantSlotType.value =
    promptSlotsStore.slotTypes.find((slotType) => slotType.id === slotVariant.slotType.id) ?? null
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
          <Button type="button" size="sm" @click="openNewSlotTypeDialog">
            <Plus class="size-4" />
            New Slot Type
          </Button>
        </div>
      </template>
    </AdminPageHeader>

    <Alert v-if="promptSlotsStore.error" variant="destructive">
      Failed to load prompt slots. {{ promptSlotsStore.error }}
    </Alert>

    <Card
      v-else-if="promptSlotsStore.isLoading && promptSlotsStore.slotTypes.length === 0"
      class="px-4 py-12 text-center text-sm text-muted-foreground"
    >
      Loading prompt slots...
    </Card>

    <Card
      v-else-if="promptSlotsStore.slotTypes.length === 0"
      class="px-4 py-12 text-center text-sm text-muted-foreground"
    >
      No prompt slot types found.
    </Card>

    <AdminPromptSlotGroups
      v-else
      :slot-types="promptSlotsStore.slotTypes"
      :variants-by-slot-type-id="promptSlotsStore.variantsBySlotTypeId"
      @edit-slot-type="openSlotTypeDialog"
      @delete-slot-type="openSlotTypeDialog"
      @add-variant="openNewSlotVariantDialog"
      @edit-variant="openSlotVariantDialog"
      @delete-variant="openSlotVariantDialog"
    />

    <AdminPromptSlotTypeDialog
      v-model:open="isSlotTypeDialogOpen"
      :slot-type="selectedSlotType"
      :saving="isSavingSlotType"
      :deleting="isDeletingSlotType"
      :can-delete="canDeleteSelectedSlotType"
      :delete-disabled-reason="selectedSlotTypeDeleteReason"
      :name-error="slotTypeFieldErrors.name ?? null"
      :general-error="slotTypeGeneralError"
      @save="saveSlotType"
      @delete="deleteSelectedSlotType"
      @clear-errors="clearSlotTypeErrors"
    />

    <AdminPromptSlotVariantDialog
      v-model:open="isSlotVariantDialogOpen"
      :slot-variant="selectedSlotVariant"
      :slot-type="selectedVariantParentSlotType"
      :saving="isSavingSlotVariant"
      :deleting="isDeletingSlotVariant"
      :can-delete="canDeleteSelectedSlotVariant"
      :delete-disabled-reason="selectedSlotVariantDeleteReason"
      :slot-type-error="slotVariantFieldErrors.slotType ?? null"
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
