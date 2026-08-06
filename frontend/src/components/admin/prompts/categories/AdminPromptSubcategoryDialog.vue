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
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Textarea } from '@/components/ui/textarea'
import { useDialogForm } from '@/composables/useDialogForm'
import { useFormErrors } from '@/composables/useFormErrors'
import { optionalText } from '@/lib/forms'
import type {
  AdminPromptCategoryDto,
  AdminPromptSubcategoryDto,
  SaveAdminPromptSubcategoryRequest,
} from '@/stores/admin/promptCategories'

interface Props {
  subcategory: AdminPromptSubcategoryDto | null
  categories: AdminPromptCategoryDto[]
  /** Every known subcategory: a name is unique case-insensitively inside its own category. */
  subcategories: AdminPromptSubcategoryDto[]
  initialCategoryId?: number | null
  saving?: boolean
  deleting?: boolean
  categoryError?: string | null
  nameError?: string | null
  descriptionError?: string | null
  generalError?: string | null
}

const props = withDefaults(defineProps<Props>(), {
  initialCategoryId: null,
  saving: false,
  deleting: false,
  categoryError: null,
  nameError: null,
  descriptionError: null,
  generalError: null,
})

const open = defineModel<boolean>('open', { required: true })

const emit = defineEmits<{
  (event: 'save', payload: SaveAdminPromptSubcategoryRequest): void
  (event: 'delete'): void
  (event: 'clearErrors'): void
}>()

const MAX_NAME_LENGTH = 200
const MAX_DESCRIPTION_LENGTH = 1000
const NONE_CATEGORY_VALUE = 'none'

interface FormState {
  categoryId: number | null
  name: string
  description: string
  active: boolean
}

const form = reactive<FormState>({
  categoryId: null,
  name: '',
  description: '',
  active: true,
})
const { fieldErrors, clearFieldErrors } = useFormErrors<'categoryId' | 'name' | 'description'>()

const isEditMode = computed(() => props.subcategory !== null)
const title = computed(() =>
  isEditMode.value ? 'Edit Prompt Subcategory' : 'New Prompt Subcategory',
)
const categoryErrorMessage = computed(() => fieldErrors.categoryId ?? props.categoryError)
const nameErrorMessage = computed(() => fieldErrors.name ?? props.nameError)
const descriptionErrorMessage = computed(() => fieldErrors.description ?? props.descriptionError)

const categorySelectValue = computed({
  get: () => form.categoryId?.toString() ?? NONE_CATEGORY_VALUE,
  set: (value: string) => {
    form.categoryId = value === NONE_CATEGORY_VALUE ? null : Number(value)
    fieldErrors.categoryId = undefined
    emit('clearErrors')
  },
})

/**
 * The names already taken in the category the form currently selects. The selection can change
 * while the dialog is open, so the check follows it rather than the stored category.
 */
const takenNames = computed(() =>
  props.subcategories
    .filter(
      (subcategory) =>
        subcategory.categoryId === form.categoryId && subcategory.id !== props.subcategory?.id,
    )
    .map((subcategory) => subcategory.name.trim().toLowerCase()),
)

function hasCategory(categoryId: number | null | undefined) {
  return (
    typeof categoryId === 'number' &&
    props.categories.some((category) => category.id === categoryId)
  )
}

function resetForm() {
  const initialCategoryId = props.subcategory?.categoryId ?? props.initialCategoryId ?? null
  form.categoryId = hasCategory(initialCategoryId) ? initialCategoryId : null
  form.name = props.subcategory?.name ?? ''
  form.description = props.subcategory?.description ?? ''
  form.active = props.subcategory?.active ?? true
  clearFieldErrors()
}

const { isDeleteDialogOpen } = useDialogForm({
  open,
  resetKeys: () => [props.subcategory?.id, props.initialCategoryId],
  resetForm,
})

function updateName(value: string | number) {
  form.name = String(value)
  fieldErrors.name = undefined
  emit('clearErrors')
}

function updateDescription(value: string | number) {
  form.description = String(value)
  fieldErrors.description = undefined
  emit('clearErrors')
}

function validate() {
  clearFieldErrors()

  let ok = true
  const trimmedName = form.name.trim()
  const trimmedDescription = form.description.trim()

  if (form.categoryId === null) {
    fieldErrors.categoryId = 'Category is required.'
    ok = false
  }

  if (trimmedName === '') {
    fieldErrors.name = 'Name is required.'
    ok = false
  } else if (trimmedName.length > MAX_NAME_LENGTH) {
    fieldErrors.name = `Name must be at most ${MAX_NAME_LENGTH} characters.`
    ok = false
  } else if (takenNames.value.includes(trimmedName.toLowerCase())) {
    fieldErrors.name = 'A prompt subcategory with this name already exists in this category.'
    ok = false
  }

  if (trimmedDescription.length > MAX_DESCRIPTION_LENGTH) {
    fieldErrors.description = `Description must be at most ${MAX_DESCRIPTION_LENGTH} characters.`
    ok = false
  }

  return ok
}

function saveSubcategory() {
  if (props.saving || props.deleting || !validate() || form.categoryId === null) {
    return
  }

  emit('save', {
    categoryId: form.categoryId,
    name: form.name.trim(),
    description: optionalText(form.description),
    active: form.active,
  })
}

function deleteSubcategory() {
  if (props.saving || props.deleting) {
    return
  }

  emit('delete')
}
</script>

<template>
  <Dialog v-model:open="open">
    <DialogContent class="w-[calc(100%-2rem)] max-w-xl rounded-xl">
      <DialogHeader>
        <DialogTitle>{{ title }}</DialogTitle>
      </DialogHeader>

      <form class="space-y-5" @submit.prevent="saveSubcategory">
        <Alert v-if="generalError" variant="destructive">
          {{ generalError }}
        </Alert>

        <div class="grid gap-5">
          <FormField
            label="Category"
            for="prompt-subcategory-category"
            :error="categoryErrorMessage"
          >
            <Select v-model="categorySelectValue" :disabled="saving || deleting">
              <SelectTrigger
                id="prompt-subcategory-category"
                :aria-invalid="categoryErrorMessage ? true : undefined"
              >
                <SelectValue placeholder="Select category" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem :value="NONE_CATEGORY_VALUE">Select category</SelectItem>
                <SelectItem
                  v-for="category in categories"
                  :key="category.id"
                  :value="category.id.toString()"
                >
                  {{ category.name }}{{ category.active ? '' : ' (Inactive)' }}
                </SelectItem>
              </SelectContent>
            </Select>
          </FormField>

          <FormField label="Name" for="prompt-subcategory-name" :error="nameErrorMessage">
            <Input
              id="prompt-subcategory-name"
              :model-value="form.name"
              type="text"
              placeholder="e.g. Minimalist portraits"
              :maxlength="MAX_NAME_LENGTH"
              :aria-invalid="nameErrorMessage ? true : undefined"
              @update:model-value="updateName"
            />
          </FormField>

          <FormField
            label="Description"
            for="prompt-subcategory-description"
            :error="descriptionErrorMessage"
            hint="Optional admin context for this subcategory."
          >
            <Textarea
              id="prompt-subcategory-description"
              :model-value="form.description"
              rows="5"
              placeholder="Optional description"
              :maxlength="MAX_DESCRIPTION_LENGTH"
              :aria-invalid="descriptionErrorMessage ? true : undefined"
              @update:model-value="updateDescription"
            />
          </FormField>
        </div>

        <CheckboxCard id="prompt-subcategory-active" v-model="form.active" class="bg-muted/20">
          <span class="block font-medium text-foreground">Active</span>
          <span class="mt-1 block text-sm leading-6 text-muted-foreground">
            Active subcategories and their eligible prompts are available in the storefront.
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
              Delete Subcategory
            </Button>
            <ConfirmDeleteDialog
              v-model:open="isDeleteDialogOpen"
              title="Delete prompt subcategory?"
              :description="`This permanently deletes ${form.name || 'this prompt subcategory'}. This action cannot be undone.`"
              confirm-label="Delete Subcategory"
              :deleting="deleting"
              confirm-test-id="confirm-delete-prompt-subcategory"
              @confirm="deleteSubcategory"
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
            {{ saving ? 'Saving...' : 'Save Subcategory' }}
          </Button>
        </DialogFooter>
      </form>
    </DialogContent>
  </Dialog>
</template>
