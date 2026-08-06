<script setup lang="ts">
import { computed, shallowRef } from 'vue'
import AdminCategoryDropSkeleton from './AdminCategoryDropSkeleton.vue'
import AdminCategoryGroup from './AdminCategoryGroup.vue'
import { useExpandableItems } from '@/composables/useExpandableItems'
import type { AdminCategoryItem, AdminSubcategoryItem } from './types'

interface Props {
  categories: AdminCategoryItem[]
  subcategoriesByCategoryId: Record<number, AdminSubcategoryItem[]>
  reordering?: boolean
  reorderingSubcategoryCategoryId?: number | null
  categoryLabel?: string
  subcategoryLabel?: string
  subcategoryCountLabel?: string
  subcategoryPluralLabel?: string
  addSubcategoryLabel?: string
  emptySubcategoriesLabel?: string
  testIdPrefix?: string
}

const props = withDefaults(defineProps<Props>(), {
  reordering: false,
  reorderingSubcategoryCategoryId: null,
  categoryLabel: 'prompt category',
  subcategoryLabel: 'prompt subcategory',
  subcategoryCountLabel: 'subcategory',
  subcategoryPluralLabel: 'subcategories',
  addSubcategoryLabel: 'Add Subcategory',
  emptySubcategoriesLabel: 'No subcategories in this prompt category yet.',
  testIdPrefix: 'prompt',
})

const emit = defineEmits<{
  (event: 'editCategory', category: AdminCategoryItem): void
  (event: 'deleteCategory', category: AdminCategoryItem): void
  (event: 'addSubcategory', category: AdminCategoryItem): void
  (event: 'editSubcategory', subcategory: AdminSubcategoryItem): void
  (event: 'deleteSubcategory', subcategory: AdminSubcategoryItem): void
  (event: 'reorderCategories', sourceCategoryId: number, targetCategoryId: number): void
  (
    event: 'reorderSubcategories',
    categoryId: number,
    sourceSubcategoryId: number,
    targetSubcategoryId: number,
  ): void
}>()

const draggedCategoryId = shallowRef<number | null>(null)
const dragOverCategoryId = shallowRef<number | null>(null)
const { isExpanded: isCategoryExpanded, setExpanded: setCategoryExpanded } =
  useExpandableItems<number>()

type DropIndicatorPlacement = 'before' | 'after'

const isDragging = computed(() => draggedCategoryId.value !== null)
const categoryDropIndicator = computed<{
  categoryId: number
  placement: DropIndicatorPlacement
} | null>(() => {
  if (draggedCategoryId.value === null || dragOverCategoryId.value === null) {
    return null
  }

  const sourceIndex = props.categories.findIndex(
    (category) => category.id === draggedCategoryId.value,
  )
  const targetIndex = props.categories.findIndex(
    (category) => category.id === dragOverCategoryId.value,
  )
  if (sourceIndex < 0 || targetIndex < 0 || sourceIndex === targetIndex) {
    return null
  }

  return {
    categoryId: dragOverCategoryId.value,
    placement: sourceIndex < targetIndex ? 'after' : 'before',
  }
})

function isCategoryDropIndicator(categoryId: number, placement: DropIndicatorPlacement) {
  return (
    categoryDropIndicator.value?.categoryId === categoryId &&
    categoryDropIndicator.value.placement === placement
  )
}

function clearDragState() {
  draggedCategoryId.value = null
  dragOverCategoryId.value = null
}

function onDragStart(category: AdminCategoryItem, event: DragEvent) {
  if (props.reordering) {
    event.preventDefault()
    return
  }

  draggedCategoryId.value = category.id
  event.dataTransfer?.setData('text/plain', String(category.id))
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move'
  }
}

function onDragOver(category: AdminCategoryItem, event: DragEvent) {
  if (
    props.reordering ||
    draggedCategoryId.value === null ||
    draggedCategoryId.value === category.id
  ) {
    return
  }

  event.preventDefault()
  dragOverCategoryId.value = category.id
  if (event.dataTransfer) {
    event.dataTransfer.dropEffect = 'move'
  }
}

function onDrop(category: AdminCategoryItem, event: DragEvent) {
  event.preventDefault()
  if (
    props.reordering ||
    draggedCategoryId.value === null ||
    draggedCategoryId.value === category.id
  ) {
    clearDragState()
    return
  }

  const sourceCategoryId = draggedCategoryId.value
  const sourceExists = props.categories.some((item) => item.id === sourceCategoryId)
  const targetExists = props.categories.some((item) => item.id === category.id)
  if (!sourceExists || !targetExists) {
    clearDragState()
    return
  }

  clearDragState()
  emit('reorderCategories', sourceCategoryId, category.id)
}
</script>

<template>
  <div class="space-y-3" :aria-busy="reordering">
    <div
      v-if="reordering"
      class="rounded-md border border-border bg-muted/30 px-3 py-2 text-sm text-muted-foreground"
      role="status"
    >
      Saving category order...
    </div>

    <template v-for="category in categories" :key="category.id">
      <AdminCategoryDropSkeleton
        v-if="isCategoryDropIndicator(category.id, 'before')"
        variant="category"
        :test-id-prefix="testIdPrefix"
        @drag-over="onDragOver(category, $event)"
        @drop="onDrop(category, $event)"
      />

      <div
        :data-testid="`${testIdPrefix}-category-drop-${category.id}`"
        @dragover="onDragOver(category, $event)"
        @drop="onDrop(category, $event)"
      >
        <AdminCategoryGroup
          :category="category"
          :subcategories="subcategoriesByCategoryId[category.id] ?? []"
          :expanded="isCategoryExpanded(category.id)"
          :dragging="draggedCategoryId === category.id"
          :reordering="reordering"
          :reordering-subcategories="reorderingSubcategoryCategoryId === category.id"
          :category-label="categoryLabel"
          :subcategory-label="subcategoryLabel"
          :subcategory-count-label="subcategoryCountLabel"
          :subcategory-plural-label="subcategoryPluralLabel"
          :add-subcategory-label="addSubcategoryLabel"
          :empty-subcategories-label="emptySubcategoriesLabel"
          :test-id-prefix="testIdPrefix"
          :class="isDragging && draggedCategoryId !== category.id ? 'transition-transform' : ''"
          @update:expanded="setCategoryExpanded(category.id, $event)"
          @drag-start="onDragStart(category, $event)"
          @drag-end="clearDragState"
          @edit-category="emit('editCategory', $event)"
          @delete-category="emit('deleteCategory', $event)"
          @add-subcategory="emit('addSubcategory', $event)"
          @edit-subcategory="emit('editSubcategory', $event)"
          @delete-subcategory="emit('deleteSubcategory', $event)"
          @reorder-subcategories="
            (sourceSubcategoryId, targetSubcategoryId) =>
              emit('reorderSubcategories', category.id, sourceSubcategoryId, targetSubcategoryId)
          "
        />
      </div>

      <AdminCategoryDropSkeleton
        v-if="isCategoryDropIndicator(category.id, 'after')"
        variant="category"
        :test-id-prefix="testIdPrefix"
        @drag-over="onDragOver(category, $event)"
        @drop="onDrop(category, $event)"
      />
    </template>
  </div>
</template>
