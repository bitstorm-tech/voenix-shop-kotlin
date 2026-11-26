<script setup lang="ts">
import { computed, reactive } from 'vue'
import { Trash2 } from 'lucide-vue-next'
import ConfirmDeleteDialog from '@/components/admin/shared/ConfirmDeleteDialog.vue'
import FormField from '@/components/admin/shared/FormField.vue'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { useDialogForm } from '@/composables/useDialogForm'
import { useFormErrors } from '@/composables/useFormErrors'
import { formatCents, parseGermanDecimal } from '@/lib/adminPrice'
import type { AdminCountryDto, UpsertAdminCountryRequest } from '@/stores/admin/countries'

interface Props {
  country: AdminCountryDto | null
  saving?: boolean
  deleting?: boolean
  conflictError?: string | null
  generalError?: string | null
}

const props = withDefaults(defineProps<Props>(), {
  saving: false,
  deleting: false,
  conflictError: null,
  generalError: null,
})

const open = defineModel<boolean>('open', { required: true })

const emit = defineEmits<{
  (event: 'save', payload: UpsertAdminCountryRequest): void
  (event: 'delete'): void
  (event: 'clearErrors'): void
}>()

const MAX_NAME_LENGTH = 255
const MAX_SHIPPING_CENTS = 2_147_483_647

interface FormState {
  name: string
  countryCode: string
  shippingCost: string
  freeShippingThreshold: string
}

const form = reactive<FormState>({
  name: '',
  countryCode: '',
  shippingCost: '',
  freeShippingThreshold: '',
})

const { fieldErrors, clearFieldErrors } = useFormErrors<
  'name' | 'countryCode' | 'shippingCost' | 'freeShippingThreshold'
>()

const isEditMode = computed(() => props.country !== null)
const title = computed(() => (isEditMode.value ? 'Edit Country' : 'New Country'))
const nameErrorMessage = computed(() => fieldErrors.name ?? props.conflictError)

function resetForm() {
  form.name = props.country?.name ?? ''
  form.countryCode = props.country?.countryCode ?? ''
  form.shippingCost = props.country !== null ? formatCents(props.country.shippingCostCents) : ''
  form.freeShippingThreshold =
    props.country?.freeShippingThresholdCents != null
      ? formatCents(props.country.freeShippingThresholdCents)
      : ''
  clearFieldErrors()
}

const { isDeleteDialogOpen } = useDialogForm({
  open,
  resetKeys: () => [props.country?.id],
  resetForm,
})

function updateName(value: string | number) {
  form.name = String(value)
  fieldErrors.name = undefined
  emit('clearErrors')
}

function updateCountryCode(value: string | number) {
  form.countryCode = String(value)
    .replace(/[^A-Za-z]/g, '')
    .toUpperCase()
    .slice(0, 2)
  fieldErrors.countryCode = undefined
  emit('clearErrors')
}

function updateShippingCost(value: string | number) {
  form.shippingCost = String(value)
  fieldErrors.shippingCost = undefined
  emit('clearErrors')
}

function updateFreeShippingThreshold(value: string | number) {
  form.freeShippingThreshold = String(value)
  fieldErrors.freeShippingThreshold = undefined
  emit('clearErrors')
}

function parseEurToCents(raw: string): number | 'invalid' | 'precision' {
  const parsed = parseGermanDecimal(raw)
  if (parsed === null) {
    return 'invalid'
  }

  const cents = parsed * 100
  if (Math.abs(cents - Math.round(cents)) > 1e-6) {
    return 'precision'
  }

  return Math.round(cents)
}

function validate(): UpsertAdminCountryRequest | null {
  clearFieldErrors()
  let ok = true

  const name = form.name.trim()
  if (name === '') {
    fieldErrors.name = 'Name is required.'
    ok = false
  } else if (name.length > MAX_NAME_LENGTH) {
    fieldErrors.name = `Name must be at most ${MAX_NAME_LENGTH} characters.`
    ok = false
  }

  const countryCode = form.countryCode.trim().toUpperCase()
  if (countryCode === '') {
    fieldErrors.countryCode = 'Country code is required.'
    ok = false
  } else if (!/^[A-Z]{2}$/.test(countryCode)) {
    fieldErrors.countryCode = 'Country code must be exactly 2 letters.'
    ok = false
  }

  let shippingCostCents = 0
  if (form.shippingCost.trim() === '') {
    fieldErrors.shippingCost = 'Shipping cost is required.'
    ok = false
  } else {
    const parsedShipping = parseEurToCents(form.shippingCost)
    if (parsedShipping === 'invalid') {
      fieldErrors.shippingCost = 'Enter a valid EUR amount (e.g. 4,90).'
      ok = false
    } else if (parsedShipping === 'precision') {
      fieldErrors.shippingCost = 'Shipping cost may have at most 2 decimal places.'
      ok = false
    } else if (parsedShipping < 0) {
      fieldErrors.shippingCost = 'Shipping cost must not be negative.'
      ok = false
    } else if (parsedShipping > MAX_SHIPPING_CENTS) {
      fieldErrors.shippingCost = 'Shipping cost is too large.'
      ok = false
    } else {
      shippingCostCents = parsedShipping
    }
  }

  let freeShippingThresholdCents: number | null = null
  if (form.freeShippingThreshold.trim() !== '') {
    const parsedThreshold = parseEurToCents(form.freeShippingThreshold)
    if (parsedThreshold === 'invalid') {
      fieldErrors.freeShippingThreshold = 'Enter a valid EUR amount (e.g. 50,00).'
      ok = false
    } else if (parsedThreshold === 'precision') {
      fieldErrors.freeShippingThreshold =
        'Free shipping threshold may have at most 2 decimal places.'
      ok = false
    } else if (parsedThreshold <= 0) {
      fieldErrors.freeShippingThreshold = 'Free shipping threshold must be greater than zero.'
      ok = false
    } else if (parsedThreshold > MAX_SHIPPING_CENTS) {
      fieldErrors.freeShippingThreshold = 'Free shipping threshold is too large.'
      ok = false
    } else {
      freeShippingThresholdCents = parsedThreshold
    }
  }

  if (!ok) {
    return null
  }

  return {
    name,
    countryCode,
    shippingCostCents,
    freeShippingThresholdCents,
  }
}

function saveCountry() {
  if (props.saving || props.deleting) {
    return
  }

  const payload = validate()
  if (payload === null) {
    return
  }

  emit('save', payload)
}

function deleteCountry() {
  if (props.saving || props.deleting) {
    return
  }

  emit('delete')
}
</script>

<template>
  <Dialog v-model:open="open">
    <DialogContent @interact-outside.prevent class="w-[calc(100%-2rem)] max-w-lg rounded-xl">
      <DialogHeader>
        <DialogTitle>{{ title }}</DialogTitle>
      </DialogHeader>

      <form class="space-y-5" @submit.prevent="saveCountry">
        <Alert v-if="generalError" variant="destructive">
          {{ generalError }}
        </Alert>

        <FormField label="Name" for="country-name" :error="nameErrorMessage">
          <Input
            id="country-name"
            :model-value="form.name"
            type="text"
            placeholder="e.g. Germany"
            :maxlength="MAX_NAME_LENGTH"
            :aria-invalid="nameErrorMessage ? true : undefined"
            @update:model-value="updateName"
          />
        </FormField>

        <FormField
          label="Country code"
          for="country-code"
          :error="fieldErrors.countryCode"
          hint="Two-letter ISO 3166-1 alpha-2 code (e.g. DE, AT, FR)."
        >
          <Input
            id="country-code"
            :model-value="form.countryCode"
            type="text"
            maxlength="2"
            autocapitalize="characters"
            placeholder="e.g. DE"
            class="font-mono uppercase"
            :aria-invalid="fieldErrors.countryCode ? true : undefined"
            @update:model-value="updateCountryCode"
          />
        </FormField>

        <FormField
          label="Shipping cost (EUR)"
          for="country-shipping-cost"
          :error="fieldErrors.shippingCost"
          hint="Gross shipping cost in EUR (enter 0,00 for free shipping)."
        >
          <Input
            id="country-shipping-cost"
            :model-value="form.shippingCost"
            type="text"
            inputmode="decimal"
            placeholder="e.g. 4,90"
            :aria-invalid="fieldErrors.shippingCost ? true : undefined"
            @update:model-value="updateShippingCost"
          />
        </FormField>

        <FormField
          label="Free shipping from (EUR)"
          for="country-free-threshold"
          :error="fieldErrors.freeShippingThreshold"
          hint="Optional order subtotal from which shipping is free. Leave empty for no free threshold."
        >
          <Input
            id="country-free-threshold"
            :model-value="form.freeShippingThreshold"
            type="text"
            inputmode="decimal"
            placeholder="e.g. 50,00"
            :aria-invalid="fieldErrors.freeShippingThreshold ? true : undefined"
            @update:model-value="updateFreeShippingThreshold"
          />
        </FormField>

        <DialogFooter class="gap-2 border-t border-border pt-5">
          <template v-if="isEditMode">
            <Button
              type="button"
              variant="destructive"
              class="sm:mr-auto"
              :disabled="saving || deleting"
              @click="isDeleteDialogOpen = true"
            >
              <Trash2 class="size-4" />
              Delete Country
            </Button>
            <ConfirmDeleteDialog
              v-model:open="isDeleteDialogOpen"
              title="Delete country?"
              :description="`This removes ${form.name || 'this country'} as a shipping destination and clears it from any supplier referencing it. Existing orders are not affected.`"
              confirm-label="Delete Country"
              :deleting="deleting"
              confirm-test-id="confirm-delete-country"
              @confirm="deleteCountry"
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
            {{ saving ? 'Saving...' : 'Save Country' }}
          </Button>
        </DialogFooter>
      </form>
    </DialogContent>
  </Dialog>
</template>
