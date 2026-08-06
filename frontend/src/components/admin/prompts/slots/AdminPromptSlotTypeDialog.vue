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
import type {
  AdminPromptSlotTypeDto,
  CreateAdminPromptSlotTypeRequest,
  UpdateAdminPromptSlotTypeRequest,
} from '@/stores/admin/promptSlots'

interface Props {
  slotType: AdminPromptSlotTypeDto | null
  saving?: boolean
  deleting?: boolean
  canDelete?: boolean
  deleteDisabledReason?: string | null
  nameError?: string | null
  generalError?: string | null
}

const props = withDefaults(defineProps<Props>(), {
  saving: false,
  deleting: false,
  canDelete: true,
  deleteDisabledReason: null,
  nameError: null,
  generalError: null,
})

const open = defineModel<boolean>('open', { required: true })

const emit = defineEmits<{
  (
    event: 'save',
    payload: CreateAdminPromptSlotTypeRequest | UpdateAdminPromptSlotTypeRequest,
  ): void
  (event: 'delete'): void
  (event: 'clearErrors'): void
}>()

const MAX_NAME_LENGTH = 255

interface FormState {
  name: string
}

const form = reactive<FormState>({
  name: '',
})
const { fieldErrors, clearFieldErrors } = useFormErrors<'name'>()

const isEditMode = computed(() => props.slotType !== null)
const title = computed(() => (isEditMode.value ? 'Edit Prompt Slot Type' : 'New Prompt Slot Type'))
const nameErrorMessage = computed(() => fieldErrors.name ?? props.nameError)

function resetForm() {
  form.name = props.slotType?.name ?? ''
  clearFieldErrors()
}

const { isDeleteDialogOpen } = useDialogForm({
  open,
  resetKeys: () => [props.slotType?.id],
  resetForm,
})

function updateName(value: string | number) {
  form.name = String(value)
  fieldErrors.name = undefined
  emit('clearErrors')
}

function validate() {
  clearFieldErrors()
  const trimmedName = form.name.trim()

  if (trimmedName === '') {
    fieldErrors.name = 'Name is required.'
    return false
  }

  if (trimmedName.length > MAX_NAME_LENGTH) {
    fieldErrors.name = `Name must be at most ${MAX_NAME_LENGTH} characters.`
    return false
  }

  return true
}

function saveSlotType() {
  if (props.saving || props.deleting || !validate()) {
    return
  }

  emit('save', {
    name: form.name.trim(),
  })
}

function deleteSlotType() {
  if (props.saving || props.deleting || !props.canDelete) {
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

      <form class="space-y-5" @submit.prevent="saveSlotType">
        <Alert v-if="generalError" variant="destructive">
          {{ generalError }}
        </Alert>

        <FormField label="Name" for="prompt-slot-type-name" :error="nameErrorMessage">
          <Input
            id="prompt-slot-type-name"
            :model-value="form.name"
            type="text"
            placeholder="e.g. Subject"
            :maxlength="MAX_NAME_LENGTH"
            :aria-invalid="nameErrorMessage ? true : undefined"
            @update:model-value="updateName"
          />
        </FormField>

        <DialogFooter class="gap-2 border-t border-border pt-5">
          <template v-if="isEditMode">
            <Button
              type="button"
              variant="destructive"
              class="sm:mr-auto"
              :disabled="saving || deleting || !canDelete"
              @click="isDeleteDialogOpen = true"
            >
              <Trash2 class="size-4" />
              Delete Slot Type
            </Button>
            <ConfirmDeleteDialog
              v-model:open="isDeleteDialogOpen"
              title="Delete prompt slot type?"
              :description="`This permanently deletes ${form.name || 'this prompt slot type'}. This action cannot be undone.`"
              confirm-label="Delete Slot Type"
              :deleting="deleting"
              confirm-test-id="confirm-delete-prompt-slot-type"
              @confirm="deleteSlotType"
            />
          </template>

          <p
            v-if="isEditMode && !canDelete && deleteDisabledReason"
            class="text-sm text-muted-foreground sm:mr-auto"
          >
            {{ deleteDisabledReason }}
          </p>

          <Button
            type="button"
            variant="outline"
            :disabled="saving || deleting"
            @click="open = false"
          >
            Cancel
          </Button>
          <Button type="submit" :disabled="saving || deleting">
            {{ saving ? 'Saving...' : 'Save Slot Type' }}
          </Button>
        </DialogFooter>
      </form>
    </DialogContent>
  </Dialog>
</template>
