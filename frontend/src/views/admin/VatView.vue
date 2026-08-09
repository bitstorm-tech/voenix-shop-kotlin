<script setup lang="ts">
import { onMounted } from 'vue'
import { Plus, RefreshCw } from 'lucide-vue-next'
import AdminPageHeader from '@/components/admin/shared/AdminPageHeader.vue'
import AdminVatDialog from '@/components/admin/vat/AdminVatDialog.vue'
import AdminVatTable from '@/components/admin/vat/AdminVatTable.vue'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { useDialogCrud } from '@/composables/useDialogCrud'
import { useFormErrors } from '@/composables/useFormErrors'
import {
  type AdminVatDto,
  type CreateAdminVatRequest,
  useAdminVatStore,
  VatInUseError,
  VatNameConflictError,
  VatNotFoundError,
} from '@/stores/admin/vat'

const vatStore = useAdminVatStore()
const { fieldErrors, generalError, clearErrors } = useFormErrors<'name'>()

const {
  isOpen: isVatDialogOpen,
  selected: selectedVat,
  isSaving,
  isDeleting,
  openCreate,
  openEdit,
  save,
  deleteSelected,
} = useDialogCrud<AdminVatDto, CreateAdminVatRequest>({
  errors: { generalError, clearErrors },
  notFoundError: VatNotFoundError,
  messages: {
    notFound: {
      title: 'VAT not found',
      fallbackDescription: 'The requested VAT entry does not exist.',
    },
    saveFailed: { title: 'Failed to save VAT', fallbackDescription: 'Failed to save VAT.' },
    deleteFailed: { title: 'Failed to delete VAT', fallbackDescription: 'Failed to delete VAT.' },
  },
  createEntity: (payload) => vatStore.createVat(payload),
  updateEntity: (id, payload) => vatStore.updateVat(id, payload),
  deleteEntity: (id) => vatStore.deleteVat(id),
  getId: (vat) => vat.id,
  savedToast: (vat, isEdit) => ({
    title: isEdit ? 'VAT saved' : 'VAT created',
    description: `${vat.name} (${vat.percent}%) was saved.`,
  }),
  deletedToast: (vat) => ({
    title: 'VAT deleted',
    description: `${vat.name} was deleted.`,
  }),
  onNotFound: () => vatStore.fetchAll(),
  handleSaveError: (error) => {
    if (error instanceof VatNameConflictError) {
      fieldErrors.name = error.message || 'A VAT entry with this name already exists.'
      return true
    }

    return false
  },
  // A delete `409` is the entry still being referenced, not a name collision, so it belongs on the
  // dialog as a general error rather than on the name field.
  handleDeleteError: (error) => {
    if (error instanceof VatInUseError) {
      generalError.value =
        'This VAT entry is still in use and cannot be deleted. Remove it from the articles and prompts that reference it first.'
      return true
    }

    return false
  },
})

onMounted(async () => {
  await vatStore.fetchAll()
})
</script>

<template>
  <section class="space-y-4">
    <AdminPageHeader title="VAT" breakpoint="lg">
      <template #actions>
        <div class="flex flex-wrap items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            :disabled="vatStore.isLoading"
            @click="vatStore.fetchAll"
          >
            <RefreshCw :class="['size-4', vatStore.isLoading && 'animate-spin']" />
            Reload
          </Button>
          <Button size="sm" @click="openCreate">
            <Plus class="size-4" />
            New VAT
          </Button>
        </div>
      </template>
    </AdminPageHeader>

    <Alert v-if="vatStore.error" variant="destructive">
      Failed to load VAT entries. {{ vatStore.error }}
    </Alert>

    <Card
      v-else-if="vatStore.isLoading && vatStore.vats.length === 0"
      class="px-4 py-12 text-center text-sm text-muted-foreground"
    >
      Loading VAT entries...
    </Card>

    <Card
      v-else-if="vatStore.vats.length === 0"
      class="px-4 py-12 text-center text-sm text-muted-foreground"
    >
      No VAT entries found.
    </Card>

    <AdminVatTable v-else :vats="vatStore.vats" @edit="openEdit" />

    <AdminVatDialog
      v-model:open="isVatDialogOpen"
      :vat="selectedVat"
      :saving="isSaving"
      :deleting="isDeleting"
      :name-error="fieldErrors.name ?? null"
      :general-error="generalError"
      @save="save"
      @delete="deleteSelected"
      @clear-errors="clearErrors"
    />
  </section>
</template>
