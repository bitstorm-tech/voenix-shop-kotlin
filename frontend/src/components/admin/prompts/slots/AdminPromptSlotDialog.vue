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
import type { AdminPromptSlotDto, SaveAdminPromptSlotRequest } from '@/stores/admin/promptSlots'

interface Props {
  /** The slot being edited, or `null` for a create. `slot` is a reserved template attribute. */
  slotItem: AdminPromptSlotDto | null
  /** Every known slot, so the editor can refuse a duplicate name before it is sent. */
  slots: AdminPromptSlotDto[]
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
  (event: 'save', payload: SaveAdminPromptSlotRequest): void
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

const isEditMode = computed(() => props.slotItem !== null)
const title = computed(() => (isEditMode.value ? 'Edit Prompt Slot' : 'New Prompt Slot'))
const nameErrorMessage = computed(() => fieldErrors.name ?? props.nameError)

/** Slot names are unique case-insensitively; the slot being edited keeps its own name. */
const takenNames = computed(() =>
  props.slots
    .filter((slot) => slot.id !== props.slotItem?.id)
    .map((slot) => slot.name.trim().toLowerCase()),
)

function resetForm() {
  form.name = props.slotItem?.name ?? ''
  clearFieldErrors()
}

const { isDeleteDialogOpen } = useDialogForm({
  open,
  resetKeys: () => [props.slotItem?.id],
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

  if (takenNames.value.includes(trimmedName.toLowerCase())) {
    fieldErrors.name = 'A prompt slot with this name already exists.'
    return false
  }

  return true
}

function saveSlot() {
  if (props.saving || props.deleting || !validate()) {
    return
  }

  emit('save', {
    name: form.name.trim(),
  })
}

function deleteSlot() {
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

      <form class="space-y-5" @submit.prevent="saveSlot">
        <Alert v-if="generalError" variant="destructive">
          {{ generalError }}
        </Alert>

        <FormField label="Name" for="prompt-slot-name" :error="nameErrorMessage">
          <Input
            id="prompt-slot-name"
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
              Delete Slot
            </Button>
            <ConfirmDeleteDialog
              v-model:open="isDeleteDialogOpen"
              title="Delete prompt slot?"
              :description="`This permanently deletes ${form.name || 'this prompt slot'}. This action cannot be undone.`"
              confirm-label="Delete Slot"
              :deleting="deleting"
              confirm-test-id="confirm-delete-prompt-slot"
              @confirm="deleteSlot"
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
            {{ saving ? 'Saving...' : 'Save Slot' }}
          </Button>
        </DialogFooter>
      </form>
    </DialogContent>
  </Dialog>
</template>
