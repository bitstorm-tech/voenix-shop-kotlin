<script setup lang="ts">
import { computed, onMounted, shallowRef } from 'vue'
import { Plus, RefreshCw } from 'lucide-vue-next'
import AdminPromptCategoryDialog from '@/components/admin/prompts/categories/AdminPromptCategoryDialog.vue'
import AdminPromptSubcategoryDialog from '@/components/admin/prompts/categories/AdminPromptSubcategoryDialog.vue'
import AdminPageHeader from '@/components/admin/shared/AdminPageHeader.vue'
import AdminCategoryGroups from '@/components/admin/shared/category-groups/AdminCategoryGroups.vue'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { useDialogCrud } from '@/composables/useDialogCrud'
import { useFormErrors } from '@/composables/useFormErrors'
import { useToast } from '@/composables/useToast'
import {
  type AdminPromptCategoryDto,
  type AdminPromptSubcategoryDetailDto,
  type CreateAdminPromptCategoryRequest,
  type CreateAdminPromptSubcategoryRequest,
  PromptCategoryInUseError,
  PromptCategoryNameConflictError,
  PromptCategoryNotFoundError,
  PromptCategoryOrderConflictError,
  PromptSubcategoryCategoryNotFoundError,
  PromptSubcategoryInUseError,
  PromptSubcategoryNameConflictError,
  PromptSubcategoryNotFoundError,
  PromptSubcategoryOrderConflictError,
  type UpdateAdminPromptCategoryRequest,
  type UpdateAdminPromptSubcategoryRequest,
  useAdminPromptCategoriesStore,
} from '@/stores/admin/promptCategories'
import type {
  AdminCategoryItem,
  AdminSubcategoryItem,
} from '@/components/admin/shared/category-groups/types'

const CATEGORY_IN_USE_FALLBACK = 'Prompt category is in use by existing prompts or subcategories.'
const SUBCATEGORY_IN_USE_FALLBACK = 'Prompt subcategory is in use by existing prompts.'

const promptCategoriesStore = useAdminPromptCategoriesStore()
const { toast } = useToast()

const initialSubcategoryCategoryId = shallowRef<number | null>(null)
const isReorderingCategories = shallowRef(false)
const isReorderingSubcategoryCategoryId = shallowRef<number | null>(null)

const {
  fieldErrors: categoryFieldErrors,
  generalError: categoryGeneralError,
  clearErrors: clearCategoryErrors,
} = useFormErrors<'name'>()

const {
  fieldErrors: subcategoryFieldErrors,
  generalError: subcategoryGeneralError,
  clearErrors: clearSubcategoryErrors,
} = useFormErrors<'category' | 'name' | 'description'>()

async function reloadPromptCategories() {
  await Promise.all([
    promptCategoriesStore.fetchCategories(),
    promptCategoriesStore.fetchSubcategories(),
  ])
}

const {
  isOpen: isCategoryDialogOpen,
  selected: selectedCategory,
  isSaving: isSavingCategory,
  isDeleting: isDeletingCategory,
  openCreate: openNewCategoryDialog,
  openEdit: openCategoryDialog,
  save: saveCategory,
  deleteSelected: deleteSelectedCategory,
} = useDialogCrud<
  AdminPromptCategoryDto,
  CreateAdminPromptCategoryRequest | UpdateAdminPromptCategoryRequest
>({
  errors: { generalError: categoryGeneralError, clearErrors: clearCategoryErrors },
  notFoundError: PromptCategoryNotFoundError,
  messages: {
    notFound: {
      title: 'Prompt category not found',
      fallbackDescription: 'The requested prompt category does not exist.',
    },
    saveFailed: {
      title: 'Failed to save prompt category',
      fallbackDescription: 'Failed to save prompt category.',
    },
    deleteFailed: {
      title: 'Failed to delete prompt category',
      fallbackDescription: 'Failed to delete prompt category.',
    },
  },
  createEntity: (payload) => promptCategoriesStore.createCategory(payload),
  updateEntity: (id, payload) => promptCategoriesStore.updateCategory(id, payload),
  deleteEntity: (id) => promptCategoriesStore.deleteCategory(id),
  getId: (category) => category.id,
  savedToast: (category, isEdit) => ({
    title: isEdit ? 'Prompt category saved' : 'Prompt category created',
    description: `${category.name} was saved.`,
  }),
  deletedToast: (category) => ({
    title: 'Prompt category deleted',
    description: `${category.name} was deleted.`,
  }),
  onNotFound: () => reloadPromptCategories(),
  handleSaveError: (error) => {
    if (error instanceof PromptCategoryNameConflictError) {
      categoryFieldErrors.name = error.message || 'A prompt category with this name already exists.'
      return true
    }

    return false
  },
  handleDeleteError: (error) => {
    if (error instanceof PromptCategoryInUseError) {
      categoryGeneralError.value = error.message || CATEGORY_IN_USE_FALLBACK
      toast({
        title: 'Prompt category cannot be deleted',
        description: categoryGeneralError.value,
        variant: 'destructive',
      })
      return true
    }

    return false
  },
})

const {
  isOpen: isSubcategoryDialogOpen,
  selected: selectedSubcategory,
  isSaving: isSavingSubcategory,
  isDeleting: isDeletingSubcategory,
  openCreate: openCreateSubcategoryDialog,
  openEdit: openEditSubcategoryDialog,
  save: saveSubcategory,
  deleteSelected: deleteSelectedSubcategory,
} = useDialogCrud<
  AdminPromptSubcategoryDetailDto,
  CreateAdminPromptSubcategoryRequest | UpdateAdminPromptSubcategoryRequest
>({
  errors: { generalError: subcategoryGeneralError, clearErrors: clearSubcategoryErrors },
  notFoundError: PromptSubcategoryNotFoundError,
  messages: {
    notFound: {
      title: 'Prompt subcategory not found',
      fallbackDescription: 'The requested prompt subcategory does not exist.',
    },
    saveFailed: {
      title: 'Failed to save prompt subcategory',
      fallbackDescription: 'Failed to save prompt subcategory.',
    },
    deleteFailed: {
      title: 'Failed to delete prompt subcategory',
      fallbackDescription: 'Failed to delete prompt subcategory.',
    },
  },
  createEntity: (payload) => promptCategoriesStore.createSubcategory(payload),
  updateEntity: (id, payload) => promptCategoriesStore.updateSubcategory(id, payload),
  deleteEntity: (id) => promptCategoriesStore.deleteSubcategory(id),
  getId: (subcategory) => subcategory.id,
  savedToast: (subcategory, isEdit) => ({
    title: isEdit ? 'Prompt subcategory saved' : 'Prompt subcategory created',
    description: `${subcategory.name} was saved.`,
  }),
  deletedToast: (subcategory) => ({
    title: 'Prompt subcategory deleted',
    description: `${subcategory.name} was deleted.`,
  }),
  onNotFound: () => reloadPromptCategories(),
  handleSaveError: (error) => {
    if (error instanceof PromptSubcategoryNameConflictError) {
      subcategoryFieldErrors.name =
        error.message || 'A prompt subcategory with this name already exists in this category.'
      return true
    }

    if (error instanceof PromptSubcategoryCategoryNotFoundError) {
      subcategoryFieldErrors.category =
        error.message || 'The selected prompt category does not exist.'
      return true
    }

    if (error instanceof PromptSubcategoryInUseError) {
      subcategoryGeneralError.value = error.message || SUBCATEGORY_IN_USE_FALLBACK
      toast({
        title: 'Prompt subcategory cannot be saved',
        description: subcategoryGeneralError.value,
        variant: 'destructive',
      })
      return true
    }

    return false
  },
  handleDeleteError: (error) => {
    if (error instanceof PromptSubcategoryInUseError) {
      subcategoryGeneralError.value = error.message || SUBCATEGORY_IN_USE_FALLBACK
      toast({
        title: 'Prompt subcategory cannot be deleted',
        description: subcategoryGeneralError.value,
        variant: 'destructive',
      })
      return true
    }

    return false
  },
})

const selectedCategorySubcategories = computed(() => {
  if (!selectedCategory.value) {
    return []
  }

  return promptCategoriesStore.subcategoriesByCategoryId[selectedCategory.value.id] ?? []
})

const canDeleteSelectedCategory = computed(
  () => !selectedCategory.value || selectedCategorySubcategories.value.length === 0,
)

const selectedCategoryDeleteReason = computed(() =>
  canDeleteSelectedCategory.value ? null : 'Remove subcategories first.',
)

function openNewSubcategoryDialog(category: AdminPromptCategoryDto) {
  initialSubcategoryCategoryId.value = category.id
  openCreateSubcategoryDialog()
}

function openSubcategoryDialog(subcategory: AdminPromptSubcategoryDetailDto) {
  initialSubcategoryCategoryId.value = subcategory.promptCategory.id
  openEditSubcategoryDialog(subcategory)
}

function handleOpenCategoryDialog(category: AdminCategoryItem) {
  openCategoryDialog(category as AdminPromptCategoryDto)
}

function handleOpenNewSubcategoryDialog(category: AdminCategoryItem) {
  openNewSubcategoryDialog(category as AdminPromptCategoryDto)
}

function handleOpenSubcategoryDialog(subcategory: AdminSubcategoryItem) {
  openSubcategoryDialog(subcategory as AdminPromptSubcategoryDetailDto)
}

async function reorderCategories(sourceCategoryId: number, targetCategoryId: number) {
  if (isReorderingCategories.value) {
    return
  }

  isReorderingCategories.value = true
  try {
    await promptCategoriesStore.reorderCategories(sourceCategoryId, targetCategoryId)
  } catch (error) {
    if (error instanceof PromptCategoryOrderConflictError) {
      toast({
        title: 'Prompt category order changed',
        description: error.message || 'Reloaded prompt categories. Try reordering again.',
        variant: 'destructive',
      })
      await reloadPromptCategories()
      return
    }

    toast({
      title: 'Failed to reorder prompt categories',
      description: error instanceof Error ? error.message : 'Failed to save prompt category order.',
      variant: 'destructive',
    })
  } finally {
    isReorderingCategories.value = false
  }
}

async function reorderSubcategories(
  categoryId: number,
  sourceSubcategoryId: number,
  targetSubcategoryId: number,
) {
  if (isReorderingSubcategoryCategoryId.value !== null) {
    return
  }

  isReorderingSubcategoryCategoryId.value = categoryId
  try {
    await promptCategoriesStore.reorderSubcategories(sourceSubcategoryId, targetSubcategoryId)
  } catch (error) {
    if (error instanceof PromptSubcategoryOrderConflictError) {
      toast({
        title: 'Prompt subcategory order changed',
        description: error.message || 'Reloaded prompt subcategories. Try reordering again.',
        variant: 'destructive',
      })
      await reloadPromptCategories()
      return
    }

    toast({
      title: 'Failed to reorder prompt subcategories',
      description:
        error instanceof Error ? error.message : 'Failed to save prompt subcategory order.',
      variant: 'destructive',
    })
  } finally {
    isReorderingSubcategoryCategoryId.value = null
  }
}

onMounted(async () => {
  await reloadPromptCategories()
})
</script>

<template>
  <section class="space-y-4">
    <AdminPageHeader title="Prompt Categories" breakpoint="lg">
      <template #actions>
        <div class="flex flex-wrap items-center gap-2">
          <Button
            type="button"
            variant="outline"
            size="sm"
            :disabled="promptCategoriesStore.isLoading"
            @click="reloadPromptCategories"
          >
            <RefreshCw :class="['size-4', promptCategoriesStore.isLoading && 'animate-spin']" />
            Reload
          </Button>
          <Button type="button" size="sm" @click="openNewCategoryDialog">
            <Plus class="size-4" />
            New Category
          </Button>
        </div>
      </template>
    </AdminPageHeader>

    <Alert v-if="promptCategoriesStore.error" variant="destructive">
      Failed to load prompt categories. {{ promptCategoriesStore.error }}
    </Alert>

    <Card
      v-else-if="promptCategoriesStore.isLoading && promptCategoriesStore.categories.length === 0"
      class="px-4 py-12 text-center text-sm text-muted-foreground"
    >
      Loading prompt categories...
    </Card>

    <Card
      v-else-if="promptCategoriesStore.categories.length === 0"
      class="px-4 py-12 text-center text-sm text-muted-foreground"
    >
      No prompt categories found.
    </Card>

    <AdminCategoryGroups
      v-else
      :categories="promptCategoriesStore.categories"
      :subcategories-by-category-id="promptCategoriesStore.subcategoriesByCategoryId"
      :reordering="isReorderingCategories"
      :reordering-subcategory-category-id="isReorderingSubcategoryCategoryId"
      @edit-category="handleOpenCategoryDialog"
      @delete-category="handleOpenCategoryDialog"
      @add-subcategory="handleOpenNewSubcategoryDialog"
      @edit-subcategory="handleOpenSubcategoryDialog"
      @delete-subcategory="handleOpenSubcategoryDialog"
      @reorder-categories="reorderCategories"
      @reorder-subcategories="reorderSubcategories"
    />

    <AdminPromptCategoryDialog
      v-model:open="isCategoryDialogOpen"
      :category="selectedCategory"
      :saving="isSavingCategory"
      :deleting="isDeletingCategory"
      :can-delete="canDeleteSelectedCategory"
      :delete-disabled-reason="selectedCategoryDeleteReason"
      :name-error="categoryFieldErrors.name ?? null"
      :general-error="categoryGeneralError"
      @save="saveCategory"
      @delete="deleteSelectedCategory"
      @clear-errors="clearCategoryErrors"
    />

    <AdminPromptSubcategoryDialog
      v-model:open="isSubcategoryDialogOpen"
      :subcategory="selectedSubcategory"
      :categories="promptCategoriesStore.categories"
      :initial-category-id="initialSubcategoryCategoryId"
      :saving="isSavingSubcategory"
      :deleting="isDeletingSubcategory"
      :category-error="subcategoryFieldErrors.category ?? null"
      :name-error="subcategoryFieldErrors.name ?? null"
      :description-error="subcategoryFieldErrors.description ?? null"
      :general-error="subcategoryGeneralError"
      @save="saveSubcategory"
      @delete="deleteSelectedSubcategory"
      @clear-errors="clearSubcategoryErrors"
    />
  </section>
</template>
