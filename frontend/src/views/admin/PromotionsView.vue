<script setup lang="ts">
import { onMounted } from 'vue'
import { Plus, RefreshCw } from 'lucide-vue-next'
import { useI18n } from 'vue-i18n'
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
const { t } = useI18n()
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

  generalError.value = t('admin.promotions.errors.locked')
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

  generalError.value = t('admin.promotions.errors.inUse')
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
      title: t('admin.promotions.errors.notFoundTitle'),
      fallbackDescription: t('admin.promotions.errors.notFoundDescription'),
    },
    saveFailed: {
      title: t('admin.promotions.errors.saveFailedTitle'),
      fallbackDescription: t('admin.promotions.errors.saveFailedDescription'),
    },
    deleteFailed: {
      title: t('admin.promotions.errors.deleteFailedTitle'),
      fallbackDescription: t('admin.promotions.errors.deleteFailedDescription'),
    },
  },
  createEntity: (payload) => promotionsStore.createPromotion(payload),
  updateEntity: (id, payload) => promotionsStore.updatePromotion(id, payload),
  deleteEntity: (id) => promotionsStore.deletePromotion(id),
  getId: (promotion) => promotion.id,
  savedToast: (promotion, isEdit) => ({
    title: t(isEdit ? 'admin.promotions.toasts.saved' : 'admin.promotions.toasts.created'),
    description: t('admin.promotions.toasts.savedDescription', { name: promotion.name }),
  }),
  deletedToast: (promotion) => ({
    title: t('admin.promotions.toasts.deleted'),
    description: t('admin.promotions.toasts.deletedDescription', { name: promotion.name }),
  }),
  resolveErrorDescription: (_error, fallbackDescription) => fallbackDescription,
  onNotFound: () => promotionsStore.fetchPromotions(),
  handleSaveError: async (error, context) => {
    if (error instanceof PromotionCodeConflictError) {
      fieldErrors.couponCode = t('admin.promotions.errors.codeConflict')
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
    <AdminPageHeader :title="t('admin.promotions.title')" breakpoint="lg">
      <template #actions>
        <div class="flex flex-wrap items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            :disabled="promotionsStore.isLoading"
            @click="promotionsStore.fetchPromotions"
          >
            <RefreshCw :class="['size-4', promotionsStore.isLoading && 'animate-spin']" />
            {{ t('admin.promotions.reload') }}
          </Button>
          <Button size="sm" @click="openCreate">
            <Plus class="size-4" />
            {{ t('admin.promotions.new') }}
          </Button>
        </div>
      </template>
    </AdminPageHeader>

    <Alert v-if="promotionsStore.error" variant="destructive">
      {{ t('admin.promotions.loadFailed') }}
    </Alert>

    <Card
      v-else-if="promotionsStore.isLoading && promotionsStore.promotions.length === 0"
      class="px-4 py-12 text-center text-sm text-muted-foreground"
    >
      {{ t('admin.promotions.loading') }}
    </Card>

    <Card
      v-else-if="promotionsStore.promotions.length === 0"
      class="px-4 py-12 text-center text-sm text-muted-foreground"
    >
      {{ t('admin.promotions.empty') }}
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
