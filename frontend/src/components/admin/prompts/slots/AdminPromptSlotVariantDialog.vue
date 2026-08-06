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
import { Textarea } from '@/components/ui/textarea'
import { useDialogForm } from '@/composables/useDialogForm'
import { useFormErrors } from '@/composables/useFormErrors'
import { optionalText } from '@/lib/forms'
import type {
  AdminPromptSlotTypeDto,
  AdminPromptSlotVariantDetailDto,
  CreateAdminPromptSlotVariantRequest,
  UpdateAdminPromptSlotVariantRequest,
} from '@/stores/admin/promptSlots'

interface Props {
  slotVariant: AdminPromptSlotVariantDetailDto | null
  slotType: AdminPromptSlotTypeDto | null
  saving?: boolean
  deleting?: boolean
  canDelete?: boolean
  deleteDisabledReason?: string | null
  slotTypeError?: string | null
  nameError?: string | null
  promptError?: string | null
  descriptionError?: string | null
  llmError?: string | null
  generalError?: string | null
}

const props = withDefaults(defineProps<Props>(), {
  saving: false,
  deleting: false,
  canDelete: true,
  deleteDisabledReason: null,
  slotTypeError: null,
  nameError: null,
  promptError: null,
  descriptionError: null,
  llmError: null,
  generalError: null,
})

const open = defineModel<boolean>('open', { required: true })

const emit = defineEmits<{
  (
    event: 'save',
    payload: CreateAdminPromptSlotVariantRequest | UpdateAdminPromptSlotVariantRequest,
  ): void
  (event: 'delete'): void
  (event: 'clearErrors'): void
}>()

const MAX_NAME_LENGTH = 255
const MAX_PROMPT_LENGTH = 10000
const MAX_DESCRIPTION_LENGTH = 1000
const MAX_LLM_LENGTH = 255

interface FormState {
  name: string
  prompt: string
  description: string
  llm: string
}

const form = reactive<FormState>({
  name: '',
  prompt: '',
  description: '',
  llm: '',
})
const { fieldErrors, clearFieldErrors } = useFormErrors<
  'slotType' | 'name' | 'prompt' | 'description' | 'llm'
>()

const isEditMode = computed(() => props.slotVariant !== null)
const effectiveSlotType = computed(() => {
  if (props.slotVariant) {
    return {
      id: props.slotVariant.slotType.id,
      name: props.slotVariant.slotType.name,
      position: props.slotVariant.slotType.position,
    }
  }

  return props.slotType
})
const title = computed(() =>
  isEditMode.value ? 'Edit Prompt Slot Variant' : 'New Prompt Slot Variant',
)
const slotTypeErrorMessage = computed(() => fieldErrors.slotType ?? props.slotTypeError)
const nameErrorMessage = computed(() => fieldErrors.name ?? props.nameError)
const promptErrorMessage = computed(() => fieldErrors.prompt ?? props.promptError)
const descriptionErrorMessage = computed(() => fieldErrors.description ?? props.descriptionError)
const llmErrorMessage = computed(() => fieldErrors.llm ?? props.llmError)

function resetForm() {
  form.name = props.slotVariant?.name ?? ''
  form.prompt = props.slotVariant?.prompt ?? ''
  form.description = props.slotVariant?.description ?? ''
  form.llm = props.slotVariant?.llm ?? ''
  clearFieldErrors()
}

const { isDeleteDialogOpen } = useDialogForm({
  open,
  resetKeys: () => [props.slotVariant?.id, props.slotType?.id],
  resetForm,
})

function updateName(value: string | number) {
  form.name = String(value)
  fieldErrors.name = undefined
  emit('clearErrors')
}

function updatePrompt(value: string | number) {
  form.prompt = String(value)
  fieldErrors.prompt = undefined
  emit('clearErrors')
}

function updateDescription(value: string | number) {
  form.description = String(value)
  fieldErrors.description = undefined
  emit('clearErrors')
}

function updateLlm(value: string | number) {
  form.llm = String(value)
  fieldErrors.llm = undefined
  emit('clearErrors')
}

function validate() {
  clearFieldErrors()

  let ok = true
  const trimmedName = form.name.trim()
  const trimmedPrompt = form.prompt.trim()
  const trimmedDescription = form.description.trim()
  const trimmedLlm = form.llm.trim()

  if (!effectiveSlotType.value) {
    fieldErrors.slotType = 'Slot type is required.'
    ok = false
  }

  if (trimmedName === '') {
    fieldErrors.name = 'Name is required.'
    ok = false
  } else if (trimmedName.length > MAX_NAME_LENGTH) {
    fieldErrors.name = `Name must be at most ${MAX_NAME_LENGTH} characters.`
    ok = false
  }

  if (trimmedPrompt === '') {
    fieldErrors.prompt = 'Prompt is required.'
    ok = false
  } else if (trimmedPrompt.length > MAX_PROMPT_LENGTH) {
    fieldErrors.prompt = `Prompt must be at most ${MAX_PROMPT_LENGTH} characters.`
    ok = false
  }

  if (trimmedDescription.length > MAX_DESCRIPTION_LENGTH) {
    fieldErrors.description = `Description must be at most ${MAX_DESCRIPTION_LENGTH} characters.`
    ok = false
  }

  if (trimmedLlm.length > MAX_LLM_LENGTH) {
    fieldErrors.llm = `LLM must be at most ${MAX_LLM_LENGTH} characters.`
    ok = false
  }

  return ok
}

function saveSlotVariant() {
  if (props.saving || props.deleting || !validate() || !effectiveSlotType.value) {
    return
  }

  const basePayload = {
    name: form.name.trim(),
    prompt: form.prompt.trim(),
    description: optionalText(form.description),
    llm: optionalText(form.llm),
  }

  emit(
    'save',
    isEditMode.value
      ? basePayload
      : {
          slotTypeId: effectiveSlotType.value.id,
          ...basePayload,
        },
  )
}

function deleteSlotVariant() {
  if (props.saving || props.deleting || !props.canDelete) {
    return
  }

  emit('delete')
}
</script>

<template>
  <Dialog v-model:open="open">
    <DialogContent class="w-[calc(100%-2rem)] max-w-3xl rounded-xl">
      <DialogHeader>
        <DialogTitle>{{ title }}</DialogTitle>
      </DialogHeader>

      <form class="space-y-5" @submit.prevent="saveSlotVariant">
        <Alert v-if="generalError" variant="destructive">
          {{ generalError }}
        </Alert>

        <div
          class="rounded-lg border border-border bg-muted/20 px-4 py-3 text-sm text-muted-foreground"
          :class="slotTypeErrorMessage && 'border-destructive/30 bg-destructive/8 text-destructive'"
        >
          <span class="font-medium text-foreground">Prompt slot type</span>
          <div v-if="effectiveSlotType" class="mt-1">
            {{ effectiveSlotType.name }} (#{{ effectiveSlotType.id }}, position
            {{ effectiveSlotType.position }})
          </div>
          <div v-else class="mt-1">No slot type selected.</div>
          <p v-if="slotTypeErrorMessage" class="mt-2 text-sm text-destructive">
            {{ slotTypeErrorMessage }}
          </p>
        </div>

        <div class="grid gap-5 md:grid-cols-2">
          <FormField label="Name" for="prompt-slot-variant-name" :error="nameErrorMessage">
            <Input
              id="prompt-slot-variant-name"
              :model-value="form.name"
              type="text"
              placeholder="e.g. Portrait"
              :maxlength="MAX_NAME_LENGTH"
              :aria-invalid="nameErrorMessage ? true : undefined"
              @update:model-value="updateName"
            />
          </FormField>

          <FormField label="LLM (optional)" for="prompt-slot-variant-llm" :error="llmErrorMessage">
            <Input
              id="prompt-slot-variant-llm"
              :model-value="form.llm"
              type="text"
              placeholder="Optional model hint"
              :maxlength="MAX_LLM_LENGTH"
              :aria-invalid="llmErrorMessage ? true : undefined"
              @update:model-value="updateLlm"
            />
          </FormField>

          <FormField
            label="Prompt"
            for="prompt-slot-variant-prompt"
            class="md:col-span-2"
            :error="promptErrorMessage"
          >
            <Textarea
              id="prompt-slot-variant-prompt"
              :model-value="form.prompt"
              rows="8"
              placeholder="Prompt text for this variant"
              :maxlength="MAX_PROMPT_LENGTH"
              :aria-invalid="promptErrorMessage ? true : undefined"
              @update:model-value="updatePrompt"
            />
          </FormField>

          <FormField
            label="Description (optional)"
            for="prompt-slot-variant-description"
            class="md:col-span-2"
            :error="descriptionErrorMessage"
          >
            <Textarea
              id="prompt-slot-variant-description"
              :model-value="form.description"
              rows="4"
              placeholder="Optional admin context"
              :maxlength="MAX_DESCRIPTION_LENGTH"
              :aria-invalid="descriptionErrorMessage ? true : undefined"
              @update:model-value="updateDescription"
            />
          </FormField>
        </div>

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
              Delete Variant
            </Button>
            <ConfirmDeleteDialog
              v-model:open="isDeleteDialogOpen"
              title="Delete prompt slot variant?"
              :description="`This permanently deletes ${form.name || 'this prompt slot variant'}. This action cannot be undone.`"
              confirm-label="Delete Variant"
              :deleting="deleting"
              confirm-test-id="confirm-delete-prompt-slot-variant"
              @confirm="deleteSlotVariant"
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
            {{ saving ? 'Saving...' : 'Save Variant' }}
          </Button>
        </DialogFooter>
      </form>
    </DialogContent>
  </Dialog>
</template>
