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
import { useDialogForm } from '@/composables/useDialogForm'
import { useFormErrors } from '@/composables/useFormErrors'
import type {
  AdminPromptCategoryDto,
  SaveAdminPromptCategoryRequest,
} from '@/stores/admin/promptCategories'

interface Props {
  category: AdminPromptCategoryDto | null
  /** Every known category, so the editor can refuse a duplicate name before it is sent. */
  categories: AdminPromptCategoryDto[]
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
  (event: 'save', payload: SaveAdminPromptCategoryRequest): void
  (event: 'delete'): void
  (event: 'clearErrors'): void
}>()

const MAX_NAME_LENGTH = 200

interface FormState {
  name: string
  active: boolean
}

const form = reactive<FormState>({
  name: '',
  active: true,
})
const { fieldErrors, clearFieldErrors } = useFormErrors<'name'>()

const isEditMode = computed(() => props.category !== null)
const title = computed(() => (isEditMode.value ? 'Edit Prompt Category' : 'New Prompt Category'))
const nameErrorMessage = computed(() => fieldErrors.name ?? props.nameError)

/** Category names are unique case-insensitively; the category being edited keeps its own name. */
const takenNames = computed(() =>
  props.categories
    .filter((category) => category.id !== props.category?.id)
    .map((category) => category.name.trim().toLowerCase()),
)

function resetForm() {
  form.name = props.category?.name ?? ''
  form.active = props.category?.active ?? true
  clearFieldErrors()
}

const { isDeleteDialogOpen } = useDialogForm({
  open,
  resetKeys: () => [props.category?.id],
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
    fieldErrors.name = 'A prompt category with this name already exists.'
    return false
  }

  return true
}

function saveCategory() {
  if (props.saving || props.deleting || !validate()) {
    return
  }

  emit('save', {
    name: form.name.trim(),
    active: form.active,
  })
}

function deleteCategory() {
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

      <form class="space-y-5" @submit.prevent="saveCategory">
        <Alert v-if="generalError" variant="destructive">
          {{ generalError }}
        </Alert>

        <FormField label="Name" for="prompt-category-name" :error="nameErrorMessage">
          <Input
            id="prompt-category-name"
            :model-value="form.name"
            type="text"
            placeholder="e.g. Portrait Styles"
            :maxlength="MAX_NAME_LENGTH"
            :aria-invalid="nameErrorMessage ? true : undefined"
            @update:model-value="updateName"
          />
        </FormField>

        <CheckboxCard id="prompt-category-active" v-model="form.active" class="bg-muted/20">
          <span class="block font-medium text-foreground">Active</span>
          <span class="mt-1 block text-sm leading-6 text-muted-foreground">
            Active categories and their eligible prompts are available in the storefront.
          </span>
        </CheckboxCard>

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
              Delete Category
            </Button>
            <ConfirmDeleteDialog
              v-model:open="isDeleteDialogOpen"
              title="Delete prompt category?"
              :description="`This permanently deletes ${form.name || 'this prompt category'}. This action cannot be undone.`"
              confirm-label="Delete Category"
              :deleting="deleting"
              confirm-test-id="confirm-delete-prompt-category"
              @confirm="deleteCategory"
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
            {{ saving ? 'Saving...' : 'Save Category' }}
          </Button>
        </DialogFooter>
      </form>
    </DialogContent>
  </Dialog>
</template>
