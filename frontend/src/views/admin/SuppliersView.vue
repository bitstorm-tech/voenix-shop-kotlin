<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Plus, RefreshCw } from 'lucide-vue-next'
import AdminPageHeader from '@/components/admin/shared/AdminPageHeader.vue'
import AdminSupplierDialog from '@/components/admin/suppliers/AdminSupplierDialog.vue'
import AdminSupplierLoginsDialog from '@/components/admin/suppliers/AdminSupplierLoginsDialog.vue'
import AdminSuppliersTable from '@/components/admin/suppliers/AdminSuppliersTable.vue'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { useAdminCountries } from '@/composables/useAdminCountries'
import { useDialogCrud } from '@/composables/useDialogCrud'
import { useFormErrors } from '@/composables/useFormErrors'
import { useToast } from '@/composables/useToast'
import {
  type AdminSupplierDto,
  type CreateAdminSupplierRequest,
  SupplierCountryNotFoundError,
  SupplierInUseError,
  SupplierNotFoundError,
  useAdminSuppliersStore,
} from '@/stores/admin/suppliers'

const suppliersStore = useAdminSuppliersStore()
const { toast } = useToast()
const { generalError, clearErrors } = useFormErrors<never>()
const {
  countries,
  error: countriesError,
  isLoading: isLoadingCountries,
  loadCountries,
} = useAdminCountries()

const {
  isOpen: isSupplierDialogOpen,
  selected: selectedSupplier,
  isLoadingSelected: isLoadingSupplier,
  isSaving,
  isDeleting,
  openCreate,
  openEditById,
  save,
  deleteSelected,
} = useDialogCrud<AdminSupplierDto, CreateAdminSupplierRequest>({
  errors: { generalError, clearErrors },
  notFoundError: SupplierNotFoundError,
  messages: {
    notFound: {
      title: 'Supplier not found',
      fallbackDescription: 'The requested supplier does not exist.',
    },
    loadFailed: {
      title: 'Failed to load supplier',
      fallbackDescription: 'Failed to load supplier.',
    },
    saveFailed: {
      title: 'Failed to save supplier',
      fallbackDescription: 'Failed to save supplier.',
    },
    deleteFailed: {
      title: 'Failed to delete supplier',
      fallbackDescription: 'Failed to delete supplier.',
    },
  },
  fetchEntity: (id) => suppliersStore.fetchSupplier(id),
  createEntity: (payload) => suppliersStore.createSupplier(payload),
  updateEntity: (id, payload) => suppliersStore.updateSupplier(id, payload),
  deleteEntity: (id) => suppliersStore.deleteSupplier(id),
  getId: (supplier) => supplier.id,
  savedToast: (supplier, isEdit) => ({
    title: isEdit ? 'Supplier saved' : 'Supplier created',
    description: `${supplier.name} was saved.`,
  }),
  deletedToast: (supplier) => ({
    title: 'Supplier deleted',
    description: `${supplier.name} was deleted.`,
  }),
  onNotFound: () => suppliersStore.fetchSuppliers(),
  handleSaveError: (error) => {
    if (error instanceof SupplierCountryNotFoundError) {
      generalError.value = error.message || 'Selected country was not found.'
      toast({
        title: 'Country not found',
        description: generalError.value,
        variant: 'destructive',
      })
      return true
    }

    return false
  },
  handleDeleteError: (error) => {
    if (error instanceof SupplierInUseError) {
      generalError.value = 'Supplier is referenced by articles and cannot be deleted'
      toast({
        title: 'Supplier cannot be deleted',
        description: generalError.value,
        variant: 'destructive',
      })
      return true
    }

    return false
  },
})

function openEditSupplierDialog(supplier: AdminSupplierDto) {
  void openEditById(supplier.id)
}

/**
 * Who may sign in for a supplier is an account lifecycle, not supplier master data — it therefore
 * gets its own dialog and its own store instead of another tab in the supplier form.
 */
const isLoginsDialogOpen = ref(false)
const supplierForLogins = ref<AdminSupplierDto | null>(null)

function openLoginsDialog(supplier: AdminSupplierDto) {
  supplierForLogins.value = supplier
  isLoginsDialogOpen.value = true
}

onMounted(async () => {
  await Promise.all([suppliersStore.fetchSuppliers(), loadCountries()])
})
</script>

<template>
  <section class="space-y-4">
    <AdminPageHeader title="Suppliers" breakpoint="lg">
      <template #actions>
        <div class="flex flex-wrap items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            :disabled="suppliersStore.isLoading"
            @click="suppliersStore.fetchSuppliers"
          >
            <RefreshCw :class="['size-4', suppliersStore.isLoading && 'animate-spin']" />
            Reload
          </Button>
          <Button size="sm" @click="openCreate">
            <Plus class="size-4" />
            Add Supplier
          </Button>
        </div>
      </template>
    </AdminPageHeader>

    <Alert v-if="suppliersStore.error" variant="destructive">
      Failed to load suppliers. {{ suppliersStore.error }}
    </Alert>

    <Card
      v-else-if="suppliersStore.isLoading && suppliersStore.suppliers.length === 0"
      class="px-4 py-12 text-center text-sm text-muted-foreground"
    >
      Loading suppliers...
    </Card>

    <Card
      v-else-if="suppliersStore.suppliers.length === 0"
      class="px-4 py-12 text-center text-sm text-muted-foreground"
    >
      No suppliers found.
    </Card>

    <AdminSuppliersTable
      v-else
      :suppliers="suppliersStore.suppliers"
      @edit="openEditSupplierDialog"
      @manage-logins="openLoginsDialog"
    />

    <AdminSupplierLoginsDialog v-model:open="isLoginsDialogOpen" :supplier="supplierForLogins" />

    <AdminSupplierDialog
      v-model:open="isSupplierDialogOpen"
      :supplier="selectedSupplier"
      :loading="isLoadingSupplier"
      :countries="countries"
      :countries-loading="isLoadingCountries"
      :countries-error="countriesError"
      :saving="isSaving"
      :deleting="isDeleting"
      :general-error="generalError"
      @save="save"
      @delete="deleteSelected"
      @clear-errors="clearErrors"
    />
  </section>
</template>
