<script setup lang="ts">
import { FilterX } from 'lucide-vue-next'
import { computed } from 'vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {
  WITHOUT_CATEGORY,
  WITHOUT_SUBCATEGORY,
  type AdminArticleListFilterCriteria,
  type ArticleCategoryFilter,
  type ArticleStatusFilter,
  type ArticleSubcategoryFilter,
} from '@/composables/useAdminArticleListFilters'
import type { AdminArticleCategoryDto } from '@/stores/admin/articleCategories'
import type { AdminArticleSubcategoryDto } from '@/stores/admin/articleSubcategories'

interface Props {
  criteria: Readonly<AdminArticleListFilterCriteria>
  categories: readonly Readonly<AdminArticleCategoryDto>[]
  subcategories: readonly Readonly<AdminArticleSubcategoryDto>[]
  hasActiveFilters: boolean
}

const props = defineProps<Props>()

const emit = defineEmits<{
  categoryIdChange: [value: ArticleCategoryFilter]
  subcategoryIdChange: [value: ArticleSubcategoryFilter]
  statusChange: [value: ArticleStatusFilter]
  nameChange: [value: string]
  reset: []
}>()

const ALL_VALUE = 'all'

const categorySelectValue = computed({
  get: () =>
    props.criteria.categoryId === null ? ALL_VALUE : props.criteria.categoryId.toString(),
  set: (value: string) =>
    emit(
      'categoryIdChange',
      value === ALL_VALUE ? null : value === WITHOUT_CATEGORY ? WITHOUT_CATEGORY : Number(value),
    ),
})

const subcategorySelectValue = computed({
  get: () =>
    props.criteria.subcategoryId === null ? ALL_VALUE : props.criteria.subcategoryId.toString(),
  set: (value: string) =>
    emit(
      'subcategoryIdChange',
      value === ALL_VALUE
        ? null
        : value === WITHOUT_SUBCATEGORY
          ? WITHOUT_SUBCATEGORY
          : Number(value),
    ),
})

const statusSelectValue = computed({
  get: () => props.criteria.status,
  set: (value: string) => emit('statusChange', value as ArticleStatusFilter),
})

const subcategoryDisabled = computed(() => typeof props.criteria.categoryId !== 'number')
</script>

<template>
  <div class="flex flex-wrap items-center gap-2">
    <Select v-model="categorySelectValue">
      <SelectTrigger
        class="h-8 w-40"
        aria-label="Filter by category"
        data-testid="article-filter-category"
      >
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        <SelectItem :value="ALL_VALUE">All categories</SelectItem>
        <SelectItem :value="WITHOUT_CATEGORY"> Without category </SelectItem>
        <SelectItem
          v-for="category in props.categories"
          :key="category.id"
          :value="category.id.toString()"
        >
          {{ category.name }}{{ category.active ? '' : ' (Inactive)' }}
        </SelectItem>
      </SelectContent>
    </Select>

    <Select v-model="subcategorySelectValue" :disabled="subcategoryDisabled">
      <SelectTrigger
        class="h-8 w-44"
        aria-label="Filter by subcategory"
        data-testid="article-filter-subcategory"
      >
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        <SelectItem :value="ALL_VALUE"> All subcategories </SelectItem>
        <SelectItem :value="WITHOUT_SUBCATEGORY"> Without subcategory </SelectItem>
        <SelectItem
          v-for="subcategory in props.subcategories"
          :key="subcategory.id"
          :value="subcategory.id.toString()"
        >
          {{ subcategory.name }}{{ subcategory.active ? '' : ' (Inactive)' }}
        </SelectItem>
      </SelectContent>
    </Select>

    <Select v-model="statusSelectValue">
      <SelectTrigger
        class="h-8 w-36"
        aria-label="Filter by status"
        data-testid="article-filter-status"
      >
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        <SelectItem value="all">All statuses</SelectItem>
        <SelectItem value="active">Active</SelectItem>
        <SelectItem value="inactive">Inactive</SelectItem>
      </SelectContent>
    </Select>

    <Input
      :model-value="props.criteria.name"
      type="search"
      class="h-8 w-44"
      placeholder="Filter by name..."
      aria-label="Filter by name"
      data-testid="article-filter-name"
      @update:model-value="emit('nameChange', String($event))"
    />

    <Button
      v-if="props.hasActiveFilters"
      variant="ghost"
      size="sm"
      data-testid="article-filter-reset"
      @click="emit('reset')"
    >
      <FilterX class="size-4" />
      Reset filters
    </Button>
  </div>
</template>
