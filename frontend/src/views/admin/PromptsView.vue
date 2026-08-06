<script setup lang="ts">
import { onMounted } from 'vue'
import { Plus, RefreshCw } from 'lucide-vue-next'
import { useI18n } from 'vue-i18n'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import AdminPromptsFilterBar from '@/components/admin/prompts/AdminPromptsFilterBar.vue'
import AdminPromptsTable from '@/components/admin/prompts/AdminPromptsTable.vue'
import AdminPageHeader from '@/components/admin/shared/AdminPageHeader.vue'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { useAdminPromptListFilters } from '@/composables/useAdminPromptListFilters'
import { useToast } from '@/composables/useToast'
import { useAdminPromptCategoriesStore } from '@/stores/admin/promptCategories'
import {
  type AdminPromptListItemDto,
  PromptOrderConflictError,
  useAdminPromptsStore,
} from '@/stores/admin/prompts'

const promptsStore = useAdminPromptsStore()
const categoriesStore = useAdminPromptCategoriesStore()
const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const { toast } = useToast()

const {
  criteria,
  subcategoryOptions,
  filteredPrompts,
  hasActiveFilters,
  setCategoryId,
  setSubcategoryId,
  setStatus,
  setTitle,
  resetFilters,
} = useAdminPromptListFilters({
  prompts: () => promptsStore.prompts,
  categories: () => categoriesStore.categories,
  subcategories: () => categoriesStore.subcategories,
})

function editPrompt(prompt: AdminPromptListItemDto) {
  void router.push({ name: 'admin-prompt-edit', params: { id: prompt.id }, query: route.query })
}

async function reorderPrompts(sourcePromptId: number, targetPromptId: number) {
  try {
    await promptsStore.reorderPrompts(sourcePromptId, targetPromptId)
  } catch (error) {
    if (error instanceof PromptOrderConflictError) {
      toast({
        title: t('admin.prompts.errors.orderChangedTitle'),
        description: t('admin.prompts.errors.orderChangedDescription'),
        variant: 'destructive',
      })
    } else {
      toast({
        title: t('admin.prompts.errors.reorderFailedTitle'),
        description: t('admin.prompts.errors.reorderFailedDescription'),
        variant: 'destructive',
      })
    }
    await promptsStore.fetchPrompts()
  }
}

onMounted(async () => {
  await Promise.all([
    promptsStore.fetchPrompts(),
    categoriesStore.fetchCategories(),
    categoriesStore.fetchSubcategories(),
  ])
})
</script>

<template>
  <section class="space-y-4">
    <AdminPageHeader :title="t('admin.prompts.title')" breakpoint="lg">
      <template #actions>
        <div class="flex flex-wrap items-center gap-2">
          <AdminPromptsFilterBar
            :criteria="criteria"
            :categories="categoriesStore.categories"
            :subcategories="subcategoryOptions"
            :has-active-filters="hasActiveFilters"
            @category-id-change="setCategoryId"
            @subcategory-id-change="setSubcategoryId"
            @status-change="setStatus"
            @title-change="setTitle"
            @reset="resetFilters"
          />
          <Button
            variant="outline"
            size="sm"
            :disabled="promptsStore.isLoading || promptsStore.isReordering"
            @click="promptsStore.fetchPrompts"
          >
            <RefreshCw :class="['size-4', promptsStore.isLoading && 'animate-spin']" />
            {{ t('admin.prompts.reload') }}
          </Button>
          <Button as-child size="sm">
            <RouterLink :to="{ name: 'admin-prompt-new', query: route.query }">
              <Plus class="size-4" />
              {{ t('admin.prompts.add') }}
            </RouterLink>
          </Button>
        </div>
      </template>
    </AdminPageHeader>

    <Alert v-if="promptsStore.error" variant="destructive">
      {{ t('admin.prompts.loadFailed') }} {{ promptsStore.error }}
    </Alert>

    <Card
      v-else-if="promptsStore.isLoading && promptsStore.prompts.length === 0"
      class="px-4 py-12 text-center text-sm text-muted-foreground"
    >
      {{ t('admin.prompts.loading') }}
    </Card>

    <Card
      v-else-if="promptsStore.prompts.length === 0"
      class="px-4 py-12 text-center text-sm text-muted-foreground"
    >
      {{ t('admin.prompts.empty') }}
    </Card>

    <Card
      v-else-if="filteredPrompts.length === 0"
      class="space-y-3 px-4 py-12 text-center text-sm text-muted-foreground"
      data-testid="prompt-filter-empty"
    >
      <p>{{ t('admin.prompts.filters.empty') }}</p>
      <Button variant="outline" size="sm" @click="resetFilters">
        {{ t('admin.prompts.filters.reset') }}
      </Button>
    </Card>

    <AdminPromptsTable
      v-else
      :prompts="filteredPrompts"
      :reordering="promptsStore.isReordering"
      :reorder-disabled="promptsStore.isLoading || hasActiveFilters"
      @edit="editPrompt"
      @reorder-prompts="reorderPrompts"
    />
  </section>
</template>
