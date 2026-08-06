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
  WITHOUT_SUBCATEGORY,
  type AdminPromptListFilterCriteria,
  type PromptStatusFilter,
  type PromptSubcategoryFilter,
} from '@/composables/useAdminPromptListFilters'
import type {
  AdminPromptCategoryDto,
  AdminPromptSubcategoryDto,
} from '@/stores/admin/promptCategories'

interface Props {
  criteria: Readonly<AdminPromptListFilterCriteria>
  categories: readonly Readonly<AdminPromptCategoryDto>[]
  subcategories: readonly Readonly<AdminPromptSubcategoryDto>[]
  hasActiveFilters: boolean
}

const props = defineProps<Props>()

const emit = defineEmits<{
  categoryIdChange: [value: number | null]
  subcategoryIdChange: [value: PromptSubcategoryFilter]
  statusChange: [value: PromptStatusFilter]
  titleChange: [value: string]
  reset: []
}>()

const { t } = useI18n()
const ALL_VALUE = 'all'

const categorySelectValue = computed({
  get: () => props.criteria.categoryId?.toString() ?? ALL_VALUE,
  set: (value: string) => emit('categoryIdChange', value === ALL_VALUE ? null : Number(value)),
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
  set: (value: string) => emit('statusChange', value as PromptStatusFilter),
})
</script>

<template>
  <div class="flex flex-wrap items-center gap-2">
    <Select v-model="categorySelectValue">
      <SelectTrigger
        class="h-8 w-40"
        :aria-label="t('admin.prompts.filters.categoryLabel')"
        data-testid="prompt-filter-category"
      >
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        <SelectItem :value="ALL_VALUE">{{ t('admin.prompts.filters.allCategories') }}</SelectItem>
        <SelectItem
          v-for="category in props.categories"
          :key="category.id"
          :value="category.id.toString()"
        >
          {{ category.name }}{{ category.active ? '' : ` (${t('admin.prompts.editor.inactive')})` }}
        </SelectItem>
      </SelectContent>
    </Select>

    <Select v-model="subcategorySelectValue" :disabled="props.criteria.categoryId === null">
      <SelectTrigger
        class="h-8 w-44"
        :aria-label="t('admin.prompts.filters.subcategoryLabel')"
        data-testid="prompt-filter-subcategory"
      >
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        <SelectItem :value="ALL_VALUE">
          {{ t('admin.prompts.filters.allSubcategories') }}
        </SelectItem>
        <SelectItem :value="WITHOUT_SUBCATEGORY">
          {{ t('admin.prompts.filters.withoutSubcategory') }}
        </SelectItem>
        <SelectItem
          v-for="subcategory in props.subcategories"
          :key="subcategory.id"
          :value="subcategory.id.toString()"
        >
          {{ subcategory.name
          }}{{ subcategory.active ? '' : ` (${t('admin.prompts.editor.inactive')})` }}
        </SelectItem>
      </SelectContent>
    </Select>

    <Select v-model="statusSelectValue">
      <SelectTrigger
        class="h-8 w-36"
        :aria-label="t('admin.prompts.filters.statusLabel')"
        data-testid="prompt-filter-status"
      >
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        <SelectItem value="all">{{ t('admin.prompts.filters.statusAll') }}</SelectItem>
        <SelectItem value="active">{{ t('admin.prompts.table.active') }}</SelectItem>
        <SelectItem value="inactive">{{ t('admin.prompts.table.inactive') }}</SelectItem>
        <SelectItem value="archived">{{ t('admin.prompts.table.archived') }}</SelectItem>
      </SelectContent>
    </Select>

    <Input
      :model-value="props.criteria.title"
      type="search"
      class="h-8 w-44"
      :placeholder="t('admin.prompts.filters.titlePlaceholder')"
      :aria-label="t('admin.prompts.filters.titleLabel')"
      data-testid="prompt-filter-title"
      @update:model-value="emit('titleChange', String($event))"
    />

    <Button
      v-if="props.hasActiveFilters"
      variant="ghost"
      size="sm"
      data-testid="prompt-filter-reset"
      @click="emit('reset')"
    >
      <FilterX class="size-4" />
      {{ t('admin.prompts.filters.reset') }}
    </Button>
  </div>
</template>
