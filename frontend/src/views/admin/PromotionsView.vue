<script setup lang="ts">
import { onMounted } from 'vue'
import { Plus, RefreshCw } from 'lucide-vue-next'
import AdminPromotionsTable from '@/components/admin/promotions/AdminPromotionsTable.vue'
import AdminPromotionDialog from '@/components/admin/promotions/AdminPromotionDialog.vue'
import AdminPageHeader from '@/components/admin/shared/AdminPageHeader.vue'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { type DialogCrudHandlerContext, useDialogCrud } from '@/composables/useDialogCrud'
import { useFormErrors } from '@/composables/useFormErrors'
import {
  type AdminPromotionDto,
  PromotionCodeConflictError,
  PromotionInUseError,
  PromotionLockedError,
  PromotionNotFoundError,
  type UpsertAdminPromotionRequest,
  useAdminPromotionsStore,
} from '@/stores/admin/promotions'

const promotionsStore = useAdminPromotionsStore()
const { fieldErrors, generalError, clearErrors } = useFormErrors<'couponCode'>()

/**
 * A refusal that says "the server knows more about this promotion than the dialog does" is answered
 * by re-reading the row, so the form shows the current representation instead of a guess.
 */
async function refreshSelectedPromotion(context: DialogCrudHandlerContext<AdminPromotionDto>) {
  const currentPromotion = context.selected
  if (!currentPromotion) {
    return
  }

  try {
    const promotion = await promotionsStore.fetchPromotion(currentPromotion.id)
    context.replaceSelected(promotion)
  } catch {
    await promotionsStore.fetchPromotions()
  }
}

async function handlePromotionLockedError(
  error: unknown,
  context: DialogCrudHandlerContext<AdminPromotionDto>,
) {
  if (!(error instanceof PromotionLockedError)) {
    return false
  }

  generalError.value = 'This Promotion has redemptions and is locked.'
  await refreshSelectedPromotion(context)
  return true
}

async function handlePromotionInUseError(
  error: unknown,
  context: DialogCrudHandlerContext<AdminPromotionDto>,
) {
  if (!(error instanceof PromotionInUseError)) {
    return false
  }

  generalError.value = 'This Promotion has been redeemed and can no longer be deleted.'
  await refreshSelectedPromotion(context)
  return true
}

const {
  isOpen: isPromotionDialogOpen,
  selected: selectedPromotion,
  isSaving,
  isDeleting,
  openCreate,
  openEdit,
  save,
  deleteSelected,
} = useDialogCrud<AdminPromotionDto, UpsertAdminPromotionRequest>({
  errors: { generalError, clearErrors },
  notFoundError: PromotionNotFoundError,
  messages: {
    notFound: {
      title: 'Promotion not found',
      fallbackDescription: 'The requested promotion does not exist.',
    },
    saveFailed: {
      title: 'Failed to save promotion',
      fallbackDescription: 'Failed to save promotion.',
    },
    deleteFailed: {
      title: 'Failed to delete promotion',
      fallbackDescription: 'Failed to delete promotion.',
    },
  },
  createEntity: (payload) => promotionsStore.createPromotion(payload),
  updateEntity: (id, payload) => promotionsStore.updatePromotion(id, payload),
  deleteEntity: (id) => promotionsStore.deletePromotion(id),
  getId: (promotion) => promotion.id,
  savedToast: (promotion, isEdit) => ({
    title: isEdit ? 'Promotion saved' : 'Promotion created',
    description: `${promotion.name} was saved.`,
  }),
  deletedToast: (promotion) => ({
    title: 'Promotion deleted',
    description: `${promotion.name} was deleted.`,
  }),
  resolveErrorDescription: (_error, fallbackDescription) => fallbackDescription,
  onNotFound: () => promotionsStore.fetchPromotions(),
  handleSaveError: async (error, context) => {
    if (error instanceof PromotionCodeConflictError) {
      fieldErrors.couponCode = 'A Promotion with this Promotion Code already exists.'
      return true
    }

    return handlePromotionLockedError(error, context)
  },
  handleDeleteError: handlePromotionInUseError,
})

onMounted(async () => {
  await promotionsStore.fetchPromotions()
})
</script>

<template>
  <section class="space-y-4">
    <AdminPageHeader title="Promotions" breakpoint="lg">
      <template #actions>
        <div class="flex flex-wrap items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            :disabled="promotionsStore.isLoading"
            @click="promotionsStore.fetchPromotions"
          >
            <RefreshCw :class="['size-4', promotionsStore.isLoading && 'animate-spin']" />
            Reload
          </Button>
          <Button size="sm" @click="openCreate">
            <Plus class="size-4" />
            New Promotion
          </Button>
        </div>
      </template>
    </AdminPageHeader>

    <Alert v-if="promotionsStore.error" variant="destructive">Failed to load promotions.</Alert>

    <Card
      v-else-if="promotionsStore.isLoading && promotionsStore.promotions.length === 0"
      class="px-4 py-12 text-center text-sm text-muted-foreground"
    >
      Loading promotions...
    </Card>

    <Card
      v-else-if="promotionsStore.promotions.length === 0"
      class="px-4 py-12 text-center text-sm text-muted-foreground"
    >
      No promotions found.
    </Card>

    <AdminPromotionsTable v-else :promotions="promotionsStore.promotions" @edit="openEdit" />

    <AdminPromotionDialog
      v-model:open="isPromotionDialogOpen"
      :promotion="selectedPromotion"
      :saving="isSaving"
      :deleting="isDeleting"
      :coupon-code-error="fieldErrors.couponCode ?? null"
      :general-error="generalError"
      @save="save"
      @delete="deleteSelected"
      @clear-errors="clearErrors"
    />
  </section>
</template>
