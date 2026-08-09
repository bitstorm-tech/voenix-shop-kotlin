<script setup lang="ts">
import { computed, onMounted, shallowRef } from 'vue'
import { Plus, RefreshCw } from 'lucide-vue-next'
import AdminArticleCategoryDialog from '@/components/admin/article/category/AdminArticleCategoryDialog.vue'
import AdminArticleSubcategoryDialog from '@/components/admin/article/subcategory/AdminArticleSubcategoryDialog.vue'
import AdminPageHeader from '@/components/admin/shared/AdminPageHeader.vue'
import AdminCategoryGroups from '@/components/admin/shared/category-groups/AdminCategoryGroups.vue'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { useDialogCrud } from '@/composables/useDialogCrud'
import { useFormErrors } from '@/composables/useFormErrors'
import { useToast } from '@/composables/useToast'
import {
  type AdminArticleCategoryDto,
  ArticleCategoryInUseError,
  ArticleCategoryNameConflictError,
  ArticleCategoryNotFoundError,
  ArticleCategoryOrderConflictError,
  type CreateAdminArticleCategoryRequest,
  useAdminArticleCategoriesStore,
} from '@/stores/admin/articleCategories'
import {
  type AdminArticleSubcategoryDto,
  ArticleSubcategoryInUseError,
  ArticleSubcategoryNameConflictError,
  ArticleSubcategoryNotFoundError,
  ArticleSubcategoryOrderConflictError,
  ArticleSubcategoryValidationError,
  type SaveAdminArticleSubcategoryRequest,
  useAdminArticleSubcategoriesStore,
} from '@/stores/admin/articleSubcategories'
import type {
  AdminCategoryItem,
  AdminSubcategoryItem,
} from '@/components/admin/shared/category-groups/types'
import type { AdminArticleSubcategoryFormPayload } from '@/components/admin/article/subcategory/types'

const CATEGORY_IN_USE_FALLBACK =
  'Category is referenced by articles or subcategories and cannot be deleted.'
const SUBCATEGORY_IN_USE_DELETE_FALLBACK = 'Article subcategory is in use and cannot be deleted.'

const categoriesStore = useAdminArticleCategoriesStore()
const subcategoriesStore = useAdminArticleSubcategoriesStore()
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
} = useFormErrors<'category' | 'name' | 'exampleImage'>()

const isLoading = computed(() => categoriesStore.isLoading || subcategoriesStore.isLoading)
const pageError = computed(() => categoriesStore.error || subcategoriesStore.error)

const subcategoriesByCategoryId = computed<Record<number, AdminArticleSubcategoryDto[]>>(() => {
  const grouped: Record<number, AdminArticleSubcategoryDto[]> = {}

  for (const category of categoriesStore.categories) {
    grouped[category.id] = []
  }

  for (const subcategory of subcategoriesStore.subcategories) {
    const categoryId = subcategory.categoryId
    grouped[categoryId] = grouped[categoryId] ?? []
    grouped[categoryId].push(subcategory)
  }

  return grouped
})

async function loadArticleCategories() {
  await Promise.all([categoriesStore.fetchCategories(), subcategoriesStore.fetchSubcategories()])
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
} = useDialogCrud<AdminArticleCategoryDto, CreateAdminArticleCategoryRequest>({
  errors: { generalError: categoryGeneralError, clearErrors: clearCategoryErrors },
  notFoundError: ArticleCategoryNotFoundError,
  messages: {
    notFound: {
      title: 'Article category not found',
      fallbackDescription: 'The requested article category does not exist.',
    },
    saveFailed: {
      title: 'Failed to save article category',
      fallbackDescription: 'Failed to save article category.',
    },
    deleteFailed: {
      title: 'Failed to delete article category',
      fallbackDescription: 'Failed to delete article category.',
    },
  },
  createEntity: (payload) => categoriesStore.createCategory(payload),
  updateEntity: (id, payload) => categoriesStore.updateCategory(id, payload),
  deleteEntity: (id) => categoriesStore.deleteCategory(id),
  getId: (category) => category.id,
  savedToast: (category, isEdit) => ({
    title: isEdit ? 'Article category saved' : 'Article category created',
    description: `${category.name} was saved.`,
  }),
  deletedToast: (category) => ({
    title: 'Article category deleted',
    description: `${category.name} was deleted.`,
  }),
  onNotFound: () => loadArticleCategories(),
  handleSaveError: (error) => {
    if (error instanceof ArticleCategoryNameConflictError) {
      categoryFieldErrors.name =
        error.message || 'An article category with this name already exists.'
      return true
    }

    return false
  },
  handleDeleteError: (error) => {
    if (error instanceof ArticleCategoryInUseError) {
      categoryGeneralError.value = error.message || CATEGORY_IN_USE_FALLBACK
      toast({
        title: 'Article category cannot be deleted',
        description: categoryGeneralError.value,
        variant: 'destructive',
      })
      return true
    }

    return false
  },
})

/**
 * Where a field error of a rejected subcategory write is shown. `categoryId` carries both the
 * unknown category and the refusal to move a subcategory that articles use; `file` carries every
 * rejection of the example-image pre-upload, and `exampleImageFilename` a name the write itself
 * refused.
 */
const SUBCATEGORY_FIELD_ERROR_TARGETS: Array<[string, 'category' | 'name' | 'exampleImage']> = [
  ['categoryId', 'category'],
  ['name', 'name'],
  ['file', 'exampleImage'],
  ['exampleImageFilename', 'exampleImage'],
]

function applySubcategoryFieldErrors(error: ArticleSubcategoryValidationError) {
  let handled = false

  for (const [field, target] of SUBCATEGORY_FIELD_ERROR_TARGETS) {
    const message = error.fieldError(field)
    if (message !== null && subcategoryFieldErrors[target] === undefined) {
      subcategoryFieldErrors[target] = message
      handled = true
    }
  }

  return handled
}

/**
 * Turns the dialog payload into the JSON body of the write. A newly chosen file is pre-uploaded
 * first, and the name it answers is what the subcategory then refers to.
 */
async function toSubcategoryRequest(
  payload: AdminArticleSubcategoryFormPayload,
): Promise<SaveAdminArticleSubcategoryRequest> {
  const exampleImageFilename = payload.exampleImage
    ? await subcategoriesStore.uploadExampleImage(payload.exampleImage)
    : payload.exampleImageFilename

  return {
    categoryId: payload.categoryId,
    name: payload.name,
    description: payload.description,
    exampleImageFilename,
    active: payload.active,
  }
}

const {
  isOpen: isSubcategoryDialogOpen,
  selected: selectedSubcategory,
  isSaving: isSavingSubcategory,
  isDeleting: isDeletingSubcategory,
  openCreate: openCreateSubcategoryDialog,
  openEdit: openEditSubcategoryDialog,
  save: saveSubcategory,
  deleteSelected: deleteSelectedSubcategory,
} = useDialogCrud<AdminArticleSubcategoryDto, AdminArticleSubcategoryFormPayload>({
  errors: { generalError: subcategoryGeneralError, clearErrors: clearSubcategoryErrors },
  notFoundError: ArticleSubcategoryNotFoundError,
  messages: {
    notFound: {
      title: 'Article subcategory not found',
      fallbackDescription: 'The requested article subcategory does not exist.',
    },
    saveFailed: {
      title: 'Failed to save article subcategory',
      fallbackDescription: 'Failed to save article subcategory.',
    },
    deleteFailed: {
      title: 'Failed to delete article subcategory',
      fallbackDescription: 'Failed to delete article subcategory.',
    },
  },
  createEntity: async (payload) =>
    subcategoriesStore.createSubcategory(await toSubcategoryRequest(payload)),
  updateEntity: async (id, payload) =>
    subcategoriesStore.updateSubcategory(id, await toSubcategoryRequest(payload)),
  deleteEntity: (id) => subcategoriesStore.deleteSubcategory(id),
  getId: (subcategory) => subcategory.id,
  savedToast: (subcategory, isEdit) => ({
    title: isEdit ? 'Article subcategory saved' : 'Article subcategory created',
    description: `${subcategory.name} was saved.`,
  }),
  deletedToast: (subcategory) => ({
    title: 'Article subcategory deleted',
    description: `${subcategory.name} was deleted.`,
  }),
  onNotFound: () => loadArticleCategories(),
  handleSaveError: (error) => {
    if (error instanceof ArticleSubcategoryNameConflictError) {
      subcategoryFieldErrors.name =
        error.message || 'An article subcategory with this name already exists in this category.'
      return true
    }

    if (error instanceof ArticleSubcategoryValidationError) {
      return applySubcategoryFieldErrors(error)
    }

    return false
  },
  handleDeleteError: (error) => {
    if (error instanceof ArticleSubcategoryInUseError) {
      subcategoryGeneralError.value = error.message || SUBCATEGORY_IN_USE_DELETE_FALLBACK
      toast({
        title: 'Article subcategory cannot be deleted',
        description: subcategoryGeneralError.value,
        variant: 'destructive',
      })
      return true
    }

    return false
  },
})

function openNewSubcategoryDialog(category: AdminArticleCategoryDto) {
  initialSubcategoryCategoryId.value = category.id
  openCreateSubcategoryDialog()
}

function openSubcategoryDialog(subcategory: AdminArticleSubcategoryDto) {
  initialSubcategoryCategoryId.value = subcategory.categoryId
  openEditSubcategoryDialog(subcategory)
}

function handleOpenCategoryDialog(category: AdminCategoryItem) {
  openCategoryDialog(category as AdminArticleCategoryDto)
}

function handleOpenNewSubcategoryDialog(category: AdminCategoryItem) {
  openNewSubcategoryDialog(category as AdminArticleCategoryDto)
}

function handleOpenSubcategoryDialog(subcategory: AdminSubcategoryItem) {
  openSubcategoryDialog(subcategory as AdminArticleSubcategoryDto)
}

async function reorderCategories(sourceId: number, targetId: number) {
  if (isReorderingCategories.value) {
    return
  }

  isReorderingCategories.value = true
  try {
    await categoriesStore.reorderCategories(sourceId, targetId)
  } catch (error) {
    // A lost race and a category somebody else deleted both mean the screen is stale, so both are
    // answered by reloading and letting the user drag again.
    if (
      error instanceof ArticleCategoryOrderConflictError ||
      error instanceof ArticleCategoryNotFoundError
    ) {
      toast({
        title: 'Article category order changed',
        description: error.message || 'Reloaded article categories. Try reordering again.',
        variant: 'destructive',
      })
      await loadArticleCategories()
      return
    }

    toast({
      title: 'Failed to reorder article categories',
      description:
        error instanceof Error ? error.message : 'Failed to save article category order.',
      variant: 'destructive',
    })
  } finally {
    isReorderingCategories.value = false
  }
}

async function reorderSubcategories(categoryId: number, sourceId: number, targetId: number) {
  if (isReorderingSubcategoryCategoryId.value !== null) {
    return
  }

  isReorderingSubcategoryCategoryId.value = categoryId
  try {
    await subcategoriesStore.reorderSubcategories(sourceId, targetId)
  } catch (error) {
    if (
      error instanceof ArticleSubcategoryOrderConflictError ||
      error instanceof ArticleSubcategoryNotFoundError
    ) {
      toast({
        title: 'Article subcategory order changed',
        description: error.message || 'Reloaded article subcategories. Try reordering again.',
        variant: 'destructive',
      })
      await loadArticleCategories()
      return
    }

    toast({
      title: 'Failed to reorder article subcategories',
      description:
        error instanceof Error ? error.message : 'Failed to save article subcategory order.',
      variant: 'destructive',
    })
  } finally {
    isReorderingSubcategoryCategoryId.value = null
  }
}

onMounted(async () => {
  await loadArticleCategories()
})
</script>

<template>
  <section class="space-y-4">
    <AdminPageHeader title="Article Categories" breakpoint="lg">
      <template #actions>
        <div class="flex flex-wrap items-center gap-2">
          <Button variant="outline" size="sm" :disabled="isLoading" @click="loadArticleCategories">
            <RefreshCw :class="['size-4', isLoading && 'animate-spin']" />
            Reload
          </Button>
          <Button size="sm" @click="openNewCategoryDialog">
            <Plus class="size-4" />
            New Category
          </Button>
        </div>
      </template>
    </AdminPageHeader>

    <Alert v-if="pageError" variant="destructive">
      Failed to load article categories and subcategories. {{ pageError }}
    </Alert>

    <Card
      v-else-if="isLoading && categoriesStore.categories.length === 0"
      class="px-4 py-12 text-center text-sm text-muted-foreground"
    >
      Loading article categories...
    </Card>

    <Card
      v-else-if="categoriesStore.categories.length === 0"
      class="px-4 py-12 text-center text-sm text-muted-foreground"
    >
      No article categories found.
    </Card>

    <AdminCategoryGroups
      v-else
      :categories="categoriesStore.categories"
      :subcategories-by-category-id="subcategoriesByCategoryId"
      :reordering="isReorderingCategories"
      :reordering-subcategory-category-id="isReorderingSubcategoryCategoryId"
      category-label="article category"
      subcategory-label="article subcategory"
      subcategory-count-label="article subcategory"
      subcategory-plural-label="article subcategories"
      add-subcategory-label="New Subcategory"
      empty-subcategories-label="No subcategories yet."
      test-id-prefix="article"
      @edit-category="handleOpenCategoryDialog"
      @delete-category="handleOpenCategoryDialog"
      @add-subcategory="handleOpenNewSubcategoryDialog"
      @edit-subcategory="handleOpenSubcategoryDialog"
      @delete-subcategory="handleOpenSubcategoryDialog"
      @reorder-categories="reorderCategories"
      @reorder-subcategories="reorderSubcategories"
    />

    <AdminArticleCategoryDialog
      v-model:open="isCategoryDialogOpen"
      :category="selectedCategory"
      :saving="isSavingCategory"
      :deleting="isDeletingCategory"
      :name-error="categoryFieldErrors.name ?? null"
      :general-error="categoryGeneralError"
      @save="saveCategory"
      @delete="deleteSelectedCategory"
      @clear-errors="clearCategoryErrors"
    />

    <AdminArticleSubcategoryDialog
      v-model:open="isSubcategoryDialogOpen"
      :subcategory="selectedSubcategory"
      :categories="categoriesStore.categories"
      :initial-category-id="initialSubcategoryCategoryId"
      :saving="isSavingSubcategory"
      :deleting="isDeletingSubcategory"
      :category-error="subcategoryFieldErrors.category ?? null"
      :name-error="subcategoryFieldErrors.name ?? null"
      :example-image-error="subcategoryFieldErrors.exampleImage ?? null"
      :general-error="subcategoryGeneralError"
      @save="saveSubcategory"
      @delete="deleteSelectedSubcategory"
      @clear-errors="clearSubcategoryErrors"
    />
  </section>
</template>
