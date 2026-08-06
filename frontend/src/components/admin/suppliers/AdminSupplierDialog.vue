<script setup lang="ts">
import { computed, reactive } from 'vue'
import { Trash2 } from 'lucide-vue-next'
import ConfirmDeleteDialog from '@/components/admin/shared/ConfirmDeleteDialog.vue'
import FormField from '@/components/admin/shared/FormField.vue'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogFooter,
  DialogHeader,
  DialogContent,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { useDialogForm } from '@/composables/useDialogForm'
import { useFormErrors } from '@/composables/useFormErrors'
import type { AdminCountryDto } from '@/composables/useAdminCountries'
import { optionalText } from '@/lib/forms'
import type { AdminSupplierDto, CreateAdminSupplierRequest } from '@/stores/admin/suppliers'

interface Props {
  supplier: AdminSupplierDto | null
  loading?: boolean
  countries: AdminCountryDto[]
  countriesLoading?: boolean
  countriesError?: string | null
  saving?: boolean
  deleting?: boolean
  generalError?: string | null
}

const props = withDefaults(defineProps<Props>(), {
  loading: false,
  countriesLoading: false,
  countriesError: null,
  saving: false,
  deleting: false,
  generalError: null,
})

const open = defineModel<boolean>('open', { required: true })

const emit = defineEmits<{
  (event: 'save', payload: CreateAdminSupplierRequest): void
  (event: 'delete'): void
  (event: 'clearErrors'): void
}>()

const NONE_COUNTRY_VALUE = 'none'

interface FormState {
  name: string
  title: string
  firstName: string
  lastName: string
  street: string
  houseNumber: string
  city: string
  postalCode: string
  countryId: number | null
  phoneNumber1: string
  phoneNumber2: string
  phoneNumber3: string
  email: string
  website: string
}

const form = reactive<FormState>({
  name: '',
  title: '',
  firstName: '',
  lastName: '',
  street: '',
  houseNumber: '',
  city: '',
  postalCode: '',
  countryId: null,
  phoneNumber1: '',
  phoneNumber2: '',
  phoneNumber3: '',
  email: '',
  website: '',
})
const { fieldErrors, clearFieldErrors } = useFormErrors<'name'>()

const isEditMode = computed(() => props.supplier !== null || props.loading)
const dialogTitle = computed(() => (isEditMode.value ? 'Edit Supplier' : 'New Supplier'))
const nameErrorMessage = computed(() => fieldErrors.name ?? null)

const countrySelectValue = computed({
  get: () => form.countryId?.toString() ?? NONE_COUNTRY_VALUE,
  set: (value: string) => {
    form.countryId = value === NONE_COUNTRY_VALUE ? null : Number(value)
    emit('clearErrors')
  },
})

function resetForm() {
  form.name = props.supplier?.name ?? ''
  form.title = props.supplier?.title ?? ''
  form.firstName = props.supplier?.firstName ?? ''
  form.lastName = props.supplier?.lastName ?? ''
  form.street = props.supplier?.street ?? ''
  form.houseNumber = props.supplier?.houseNumber ?? ''
  form.city = props.supplier?.city ?? ''
  form.postalCode = props.supplier?.postalCode ?? ''
  form.countryId = props.supplier?.countryId ?? null
  form.phoneNumber1 = props.supplier?.phoneNumber1 ?? ''
  form.phoneNumber2 = props.supplier?.phoneNumber2 ?? ''
  form.phoneNumber3 = props.supplier?.phoneNumber3 ?? ''
  form.email = props.supplier?.email ?? ''
  form.website = props.supplier?.website ?? ''
  clearFieldErrors()
}

const { isDeleteDialogOpen } = useDialogForm({
  open,
  resetKeys: () => [props.supplier?.id, props.loading],
  resetForm,
})

function updateName(value: string | number) {
  form.name = String(value)
  fieldErrors.name = undefined
  emit('clearErrors')
}

function validate() {
  clearFieldErrors()

  if (form.name.trim() === '') {
    fieldErrors.name = 'Name is required.'
    return false
  }

  return true
}

function saveSupplier() {
  if (props.saving || props.deleting || props.loading || !validate()) {
    return
  }

  emit('save', {
    name: form.name.trim(),
    title: optionalText(form.title),
    firstName: optionalText(form.firstName),
    lastName: optionalText(form.lastName),
    street: optionalText(form.street),
    houseNumber: optionalText(form.houseNumber),
    city: optionalText(form.city),
    postalCode: optionalText(form.postalCode),
    countryId: form.countryId,
    phoneNumber1: optionalText(form.phoneNumber1),
    phoneNumber2: optionalText(form.phoneNumber2),
    phoneNumber3: optionalText(form.phoneNumber3),
    email: optionalText(form.email),
    website: optionalText(form.website),
  })
}

function deleteSupplier() {
  if (props.saving || props.deleting || props.loading) {
    return
  }

  emit('delete')
}
</script>

<template>
  <Dialog v-model:open="open">
    <DialogContent class="w-[calc(100%-2rem)] max-w-3xl rounded-xl">
      <DialogHeader>
        <DialogTitle>{{ dialogTitle }}</DialogTitle>
      </DialogHeader>

      <div v-if="loading" class="px-4 py-12 text-center text-sm text-muted-foreground">
        Loading supplier...
      </div>

      <form v-else class="space-y-6" @submit.prevent="saveSupplier">
        <Alert v-if="generalError" variant="destructive">
          {{ generalError }}
        </Alert>

        <Alert v-if="countriesError" variant="destructive">
          Failed to load countries. {{ countriesError }}
        </Alert>

        <fieldset class="space-y-4">
          <legend class="text-base font-semibold text-foreground">Company</legend>
          <FormField label="Name" for="supplier-name" :error="nameErrorMessage">
            <Input
              id="supplier-name"
              :model-value="form.name"
              type="text"
              placeholder="Supplier name"
              :aria-invalid="nameErrorMessage ? true : undefined"
              @update:model-value="updateName"
            />
          </FormField>
        </fieldset>

        <fieldset class="space-y-4 border-t border-border pt-5">
          <legend class="text-base font-semibold text-foreground">Contact person</legend>
          <div class="grid gap-4 md:grid-cols-3">
            <FormField label="Title" for="supplier-title">
              <Input id="supplier-title" v-model="form.title" type="text" placeholder="Ms." />
            </FormField>
            <FormField label="First name" for="supplier-first-name">
              <Input id="supplier-first-name" v-model="form.firstName" type="text" />
            </FormField>
            <FormField label="Last name" for="supplier-last-name">
              <Input id="supplier-last-name" v-model="form.lastName" type="text" />
            </FormField>
          </div>
        </fieldset>

        <fieldset class="space-y-4 border-t border-border pt-5">
          <legend class="text-base font-semibold text-foreground">Address</legend>
          <div class="grid gap-4 md:grid-cols-2">
            <FormField label="Street" for="supplier-street">
              <Input
                id="supplier-street"
                v-model="form.street"
                type="text"
                autocomplete="address-line1"
              />
            </FormField>
            <FormField label="House number" for="supplier-house-number">
              <Input
                id="supplier-house-number"
                v-model="form.houseNumber"
                type="text"
                autocomplete="address-line2"
              />
            </FormField>
            <FormField label="Postal code" for="supplier-postal-code">
              <Input
                id="supplier-postal-code"
                v-model="form.postalCode"
                type="text"
                autocomplete="postal-code"
              />
            </FormField>
            <FormField label="City" for="supplier-city">
              <Input
                id="supplier-city"
                v-model="form.city"
                type="text"
                autocomplete="address-level2"
              />
            </FormField>
            <FormField
              label="Country"
              for="supplier-country"
              class="md:col-span-2"
              :hint="countriesLoading ? 'Loading countries...' : 'Optional country assignment.'"
            >
              <Select v-model="countrySelectValue" :disabled="countriesLoading">
                <SelectTrigger id="supplier-country">
                  <SelectValue placeholder="Select country" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem :value="NONE_COUNTRY_VALUE">No country</SelectItem>
                  <SelectItem
                    v-for="country in countries"
                    :key="country.id"
                    :value="country.id.toString()"
                  >
                    {{ country.name }} ({{ country.countryCode }})
                  </SelectItem>
                </SelectContent>
              </Select>
            </FormField>
          </div>
        </fieldset>

        <fieldset class="space-y-4 border-t border-border pt-5">
          <legend class="text-base font-semibold text-foreground">Contact</legend>
          <div class="grid gap-4 md:grid-cols-2">
            <FormField label="Phone 1" for="supplier-phone-1">
              <Input
                id="supplier-phone-1"
                v-model="form.phoneNumber1"
                type="tel"
                autocomplete="tel"
              />
            </FormField>
            <FormField label="Phone 2" for="supplier-phone-2">
              <Input id="supplier-phone-2" v-model="form.phoneNumber2" type="tel" />
            </FormField>
            <FormField label="Phone 3" for="supplier-phone-3">
              <Input id="supplier-phone-3" v-model="form.phoneNumber3" type="tel" />
            </FormField>
            <FormField label="Email" for="supplier-email">
              <Input id="supplier-email" v-model="form.email" type="email" autocomplete="email" />
            </FormField>
            <FormField label="Website" for="supplier-website" class="md:col-span-2">
              <Input
                id="supplier-website"
                v-model="form.website"
                type="url"
                placeholder="https://example.com"
              />
            </FormField>
          </div>
        </fieldset>

        <DialogFooter class="gap-2 border-t border-border pt-5">
          <template v-if="supplier">
            <Button
              type="button"
              variant="destructive"
              class="sm:mr-auto"
              :disabled="saving || deleting"
              @click="isDeleteDialogOpen = true"
            >
              <Trash2 class="size-4" />
              Delete Supplier
            </Button>
            <ConfirmDeleteDialog
              v-model:open="isDeleteDialogOpen"
              title="Delete supplier?"
              :description="`This permanently deletes ${form.name || 'this supplier'}. This action cannot be undone.`"
              confirm-label="Delete Supplier"
              :deleting="deleting"
              confirm-test-id="confirm-delete-supplier"
              @confirm="deleteSupplier"
            />
          </template>

          <Button
            type="button"
            variant="outline"
            :disabled="saving || deleting"
            @click="open = false"
          >
            Cancel
          </Button>
          <Button type="submit" :disabled="saving || deleting">
            {{ saving ? 'Saving...' : 'Save Supplier' }}
          </Button>
        </DialogFooter>
      </form>
    </DialogContent>
  </Dialog>
</template>
