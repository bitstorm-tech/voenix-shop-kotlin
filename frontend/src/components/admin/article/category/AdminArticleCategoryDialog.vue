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
import type {
  AdminArticleCategoryDto,
  CreateAdminArticleCategoryRequest,
  UpdateAdminArticleCategoryRequest,
} from '@/stores/admin/articleCategories'

interface Props {
  category: AdminArticleCategoryDto | null
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
  (
    event: 'save',
    payload: CreateAdminArticleCategoryRequest | UpdateAdminArticleCategoryRequest,
  ): void
  (event: 'delete'): void
  (event: 'clearErrors'): void
}>()

const MAX_NAME_LENGTH = 200
const MAX_DESCRIPTION_LENGTH = 1000

interface FormState {
  name: string
  description: string
  active: boolean
}

const form = reactive<FormState>({
  name: '',
  description: '',
  active: true,
})
const { fieldErrors, clearFieldErrors } = useFormErrors<'name' | 'description'>()

const isEditMode = computed(() => props.category !== null)
const title = computed(() => (isEditMode.value ? 'Edit Article Category' : 'New Article Category'))
const nameErrorMessage = computed(() => fieldErrors.name ?? props.nameError)

function resetForm() {
  form.name = props.category?.name ?? ''
  form.description = props.category?.description ?? ''
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

  if (trimmedName === '') {
    fieldErrors.name = 'Name is required.'
    ok = false
  } else if (trimmedName.length > MAX_NAME_LENGTH) {
    fieldErrors.name = `Name must be at most ${MAX_NAME_LENGTH} characters.`
    ok = false
  }

  if (trimmedDescription.length > MAX_DESCRIPTION_LENGTH) {
    fieldErrors.description = `Description must be at most ${MAX_DESCRIPTION_LENGTH} characters.`
    ok = false
  }

  return ok
}

function saveCategory() {
  if (props.saving || props.deleting || !validate()) {
    return
  }

  emit('save', {
    name: form.name.trim(),
    description: optionalText(form.description),
    active: form.active,
  })
}

function deleteCategory() {
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

      <form class="space-y-5" @submit.prevent="saveCategory">
        <Alert v-if="generalError" variant="destructive">
          {{ generalError }}
        </Alert>

        <FormField label="Name" for="article-category-name" :error="nameErrorMessage">
          <Input
            id="article-category-name"
            :model-value="form.name"
            type="text"
            placeholder="e.g. Mugs"
            :maxlength="MAX_NAME_LENGTH"
            :aria-invalid="nameErrorMessage ? true : undefined"
            @update:model-value="updateName"
          />
        </FormField>

        <FormField
          label="Description"
          for="article-category-description"
          :error="fieldErrors.description"
          hint="Optional storefront and admin context for this category."
        >
          <Textarea
            id="article-category-description"
            :model-value="form.description"
            rows="5"
            placeholder="Optional description"
            :maxlength="MAX_DESCRIPTION_LENGTH"
            :aria-invalid="fieldErrors.description ? true : undefined"
            @update:model-value="updateDescription"
          />
        </FormField>

        <CheckboxCard id="article-category-active" v-model="form.active" class="bg-muted/20">
          <span class="block font-medium text-foreground">Active</span>
          <span class="mt-1 block text-sm leading-6 text-muted-foreground">
            Active categories and their eligible articles are available in the storefront.
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
              Delete Category
            </Button>
            <ConfirmDeleteDialog
              v-model:open="isDeleteDialogOpen"
              title="Delete article category?"
              :description="`This permanently deletes ${form.name || 'this article category'}. This action cannot be undone.`"
              confirm-label="Delete Category"
              :deleting="deleting"
              confirm-test-id="confirm-delete-article-category"
              @confirm="deleteCategory"
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
            {{ saving ? 'Saving...' : 'Save Category' }}
          </Button>
        </DialogFooter>
      </form>
    </DialogContent>
  </Dialog>
</template>
