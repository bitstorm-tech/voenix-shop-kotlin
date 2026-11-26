<script setup lang="ts">
import { onMounted } from 'vue'
import { Plus, RefreshCw } from 'lucide-vue-next'
import AdminCountriesTable from '@/components/admin/countries/AdminCountriesTable.vue'
import AdminCountryDialog from '@/components/admin/countries/AdminCountryDialog.vue'
import AdminPageHeader from '@/components/admin/shared/AdminPageHeader.vue'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { useDialogCrud } from '@/composables/useDialogCrud'
import { useFormErrors } from '@/composables/useFormErrors'
import {
  type AdminCountryDto,
  CountryConflictError,
  CountryNotFoundError,
  type UpsertAdminCountryRequest,
  useAdminCountriesStore,
} from '@/stores/admin/countries'

const countriesStore = useAdminCountriesStore()
const { fieldErrors, generalError, clearErrors } = useFormErrors<'conflict'>()

const {
  isOpen: isCountryDialogOpen,
  selected: selectedCountry,
  isSaving,
  isDeleting,
  openCreate,
  openEdit,
  save,
  deleteSelected,
} = useDialogCrud<AdminCountryDto, UpsertAdminCountryRequest>({
  errors: { generalError, clearErrors },
  notFoundError: CountryNotFoundError,
  messages: {
    notFound: {
      title: 'Country not found',
      fallbackDescription: 'The requested country does not exist.',
    },
    saveFailed: {
      title: 'Failed to save country',
      fallbackDescription: 'Failed to save country.',
    },
    deleteFailed: {
      title: 'Failed to delete country',
      fallbackDescription: 'Failed to delete country.',
    },
  },
  createEntity: (payload) => countriesStore.createCountry(payload),
  updateEntity: (id, payload) => countriesStore.updateCountry(id, payload),
  deleteEntity: (id) => countriesStore.deleteCountry(id),
  getId: (country) => country.id,
  savedToast: (country, isEdit) => ({
    title: isEdit ? 'Country saved' : 'Country created',
    description: `${country.name} (${country.countryCode}) was saved.`,
  }),
  deletedToast: (country) => ({
    title: 'Country deleted',
    description: `${country.name} (${country.countryCode}) was deleted.`,
  }),
  onNotFound: () => countriesStore.fetchAll(),
  handleSaveError: (error) => {
    if (error instanceof CountryConflictError) {
      fieldErrors.conflict = error.message || 'Country name or code already exists.'
      return true
    }

    return false
  },
})

onMounted(async () => {
  await countriesStore.fetchAll()
})
</script>

<template>
  <section class="space-y-4">
    <AdminPageHeader title="Countries" breakpoint="lg">
      <template #actions>
        <div class="flex flex-wrap items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            :disabled="countriesStore.isLoading"
            @click="countriesStore.fetchAll"
          >
            <RefreshCw :class="['size-4', countriesStore.isLoading && 'animate-spin']" />
            Reload
          </Button>
          <Button size="sm" @click="openCreate">
            <Plus class="size-4" />
            New Country
          </Button>
        </div>
      </template>
    </AdminPageHeader>

    <Alert v-if="countriesStore.error" variant="destructive">
      Failed to load countries. {{ countriesStore.error }}
    </Alert>

    <Card
      v-else-if="countriesStore.isLoading && countriesStore.countries.length === 0"
      class="px-4 py-12 text-center text-sm text-muted-foreground"
    >
      Loading countries...
    </Card>

    <Card
      v-else-if="countriesStore.countries.length === 0"
      class="px-4 py-12 text-center text-sm text-muted-foreground"
    >
      No shipping countries configured.
    </Card>

    <AdminCountriesTable v-else :countries="countriesStore.countries" @edit="openEdit" />

    <AdminCountryDialog
      v-model:open="isCountryDialogOpen"
      :country="selectedCountry"
      :saving="isSaving"
      :deleting="isDeleting"
      :conflict-error="fieldErrors.conflict ?? null"
      :general-error="generalError"
      @save="save"
      @delete="deleteSelected"
      @clear-errors="clearErrors"
    />
  </section>
</template>
