<script setup lang="ts">
import { computed, reactive } from 'vue'
import { Trash2 } from 'lucide-vue-next'
import ConfirmDeleteDialog from '@/components/admin/shared/ConfirmDeleteDialog.vue'
import FormField from '@/components/admin/shared/FormField.vue'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { CheckboxCard } from '@/components/ui/checkbox-card'
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { useDialogForm } from '@/composables/useDialogForm'
import { useFormErrors } from '@/composables/useFormErrors'
import { optionalText } from '@/lib/forms'
import type { AdminVatDto, CreateAdminVatRequest } from '@/stores/admin/vat'

interface Props {
  vat: AdminVatDto | null
  saving?: boolean
  deleting?: boolean
  nameError?: string | null
  generalError?: string | null
}

const props = withDefaults(defineProps<Props>(), {
  saving: false,
  deleting: false,
  nameError: null,
  generalError: null,
})

const open = defineModel<boolean>('open', { required: true })

const emit = defineEmits<{
  (event: 'save', payload: CreateAdminVatRequest): void
  (event: 'delete'): void
  (event: 'clearErrors'): void
}>()

const MAX_NAME_LENGTH = 255
const MAX_VAT_PERCENT = 100

interface FormState {
  name: string
  percent: number | string
  description: string
  isDefault: boolean
}

const form = reactive<FormState>({
  name: '',
  percent: '',
  description: '',
  isDefault: false,
})
const { fieldErrors, clearFieldErrors } = useFormErrors<'name' | 'percent'>()

const isEditMode = computed(() => props.vat !== null)
const title = computed(() => (isEditMode.value ? 'Edit VAT' : 'New VAT'))
const nameErrorMessage = computed(() => fieldErrors.name ?? props.nameError)

const parsedPercent = computed(() => {
  const raw = form.percent
  if (raw === '') {
    return null
  }

  const numericValue = typeof raw === 'number' ? raw : Number(raw)
  return Number.isFinite(numericValue) && Number.isInteger(numericValue) ? numericValue : NaN
})

function resetForm() {
  form.name = props.vat?.name ?? ''
  form.percent = props.vat?.percent ?? ''
  form.description = props.vat?.description ?? ''
  form.isDefault = props.vat?.isDefault ?? false
  clearFieldErrors()
}

const { isDeleteDialogOpen } = useDialogForm({
  open,
  resetKeys: () => [props.vat?.id],
  resetForm,
})

function updateName(value: string | number) {
  form.name = String(value)
  fieldErrors.name = undefined
  emit('clearErrors')
}

function updatePercent(value: string | number) {
  form.percent = value
  fieldErrors.percent = undefined
}

function validate() {
  clearFieldErrors()
  let ok = true

  if (form.name.trim() === '') {
    fieldErrors.name = 'Name is required.'
    ok = false
  } else if (form.name.trim().length > MAX_NAME_LENGTH) {
    fieldErrors.name = `Name must be at most ${MAX_NAME_LENGTH} characters.`
    ok = false
  }

  if (parsedPercent.value === null) {
    fieldErrors.percent = 'Percent is required.'
    ok = false
  } else if (Number.isNaN(parsedPercent.value)) {
    fieldErrors.percent = 'Percent must be a whole number.'
    ok = false
  } else if (parsedPercent.value < 0) {
    fieldErrors.percent = 'Percent must be zero or positive.'
    ok = false
  } else if (parsedPercent.value > MAX_VAT_PERCENT) {
    fieldErrors.percent = `Percent must be at most ${MAX_VAT_PERCENT}.`
    ok = false
  }

  return ok
}

function saveVat() {
  if (props.saving || props.deleting || !validate()) {
    return
  }

  emit('save', {
    name: form.name.trim(),
    percent: parsedPercent.value as number,
    description: optionalText(form.description),
    isDefault: form.isDefault,
  })
}

function deleteVat() {
  if (props.saving || props.deleting) {
    return
  }

  emit('delete')
}
</script>

<template>
  <Dialog v-model:open="open">
    <DialogContent class="w-[calc(100%-2rem)] max-w-lg rounded-xl">
      <DialogHeader>
        <DialogTitle>{{ title }}</DialogTitle>
      </DialogHeader>

      <form class="space-y-5" @submit.prevent="saveVat">
        <Alert v-if="generalError" variant="destructive">
          {{ generalError }}
        </Alert>

        <FormField label="Name" for="vat-name" :error="nameErrorMessage">
          <Input
            id="vat-name"
            :model-value="form.name"
            type="text"
            placeholder="e.g. Standard"
            :maxlength="MAX_NAME_LENGTH"
            :aria-invalid="nameErrorMessage ? true : undefined"
            @update:model-value="updateName"
          />
        </FormField>

        <FormField
          label="Percent"
          for="vat-percent"
          :error="fieldErrors.percent"
          :hint="`Enter a whole number from 0 to ${MAX_VAT_PERCENT}.`"
        >
          <Input
            id="vat-percent"
            :model-value="form.percent"
            type="number"
            inputmode="numeric"
            min="0"
            :max="MAX_VAT_PERCENT"
            step="1"
            placeholder="e.g. 19"
            :aria-invalid="fieldErrors.percent ? true : undefined"
            @update:model-value="updatePercent"
          />
        </FormField>

        <FormField label="Description" for="vat-description">
          <Textarea
            id="vat-description"
            v-model="form.description"
            rows="3"
            placeholder="Optional description"
          />
        </FormField>

        <CheckboxCard
          id="vat-is-default"
          v-model="form.isDefault"
          class="bg-muted/20"
          content-class="block space-y-1.5"
        >
          <span class="block font-medium text-foreground">Default</span>
          <span class="block text-sm leading-6 text-muted-foreground">
            Mark this VAT as the system default. Any other entry currently marked as default will be
            unmarked.
          </span>
        </CheckboxCard>

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
              Delete VAT
            </Button>
            <ConfirmDeleteDialog
              v-model:open="isDeleteDialogOpen"
              title="Delete VAT entry?"
              :description="`This permanently deletes ${form.name || 'this VAT entry'}. This action cannot be undone.`"
              confirm-label="Delete VAT"
              :deleting="deleting"
              confirm-test-id="confirm-delete-vat"
              @confirm="deleteVat"
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
            {{ saving ? 'Saving...' : 'Save VAT' }}
          </Button>
        </DialogFooter>
      </form>
    </DialogContent>
  </Dialog>
</template>
