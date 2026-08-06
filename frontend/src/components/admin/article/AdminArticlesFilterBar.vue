<script setup lang="ts">
import { FilterX } from 'lucide-vue-next'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
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

const { t } = useI18n()
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
        :aria-label="t('admin.articles.filters.categoryLabel')"
        data-testid="article-filter-category"
      >
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        <SelectItem :value="ALL_VALUE">{{ t('admin.articles.filters.allCategories') }}</SelectItem>
        <SelectItem :value="WITHOUT_CATEGORY">
          {{ t('admin.articles.filters.withoutCategory') }}
        </SelectItem>
        <SelectItem
          v-for="category in props.categories"
          :key="category.id"
          :value="category.id.toString()"
        >
          {{ category.name }}{{ category.active ? '' : ` (${t('admin.articles.table.inactive')})` }}
        </SelectItem>
      </SelectContent>
    </Select>

    <Select v-model="subcategorySelectValue" :disabled="subcategoryDisabled">
      <SelectTrigger
        class="h-8 w-44"
        :aria-label="t('admin.articles.filters.subcategoryLabel')"
        data-testid="article-filter-subcategory"
      >
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        <SelectItem :value="ALL_VALUE">
          {{ t('admin.articles.filters.allSubcategories') }}
        </SelectItem>
        <SelectItem :value="WITHOUT_SUBCATEGORY">
          {{ t('admin.articles.filters.withoutSubcategory') }}
        </SelectItem>
        <SelectItem
          v-for="subcategory in props.subcategories"
          :key="subcategory.id"
          :value="subcategory.id.toString()"
        >
          {{ subcategory.name
          }}{{ subcategory.active ? '' : ` (${t('admin.articles.table.inactive')})` }}
        </SelectItem>
      </SelectContent>
    </Select>

    <Select v-model="statusSelectValue">
      <SelectTrigger
        class="h-8 w-36"
        :aria-label="t('admin.articles.filters.statusLabel')"
        data-testid="article-filter-status"
      >
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        <SelectItem value="all">{{ t('admin.articles.filters.statusAll') }}</SelectItem>
        <SelectItem value="active">{{ t('admin.articles.table.active') }}</SelectItem>
        <SelectItem value="inactive">{{ t('admin.articles.table.inactive') }}</SelectItem>
      </SelectContent>
    </Select>

    <Input
      :model-value="props.criteria.name"
      type="search"
      class="h-8 w-44"
      :placeholder="t('admin.articles.filters.namePlaceholder')"
      :aria-label="t('admin.articles.filters.nameLabel')"
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
      {{ t('admin.articles.filters.reset') }}
    </Button>
  </div>
</template>
