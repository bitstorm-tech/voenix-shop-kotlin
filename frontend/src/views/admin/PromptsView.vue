<script setup lang="ts">
import { onMounted } from 'vue'
import { Plus, RefreshCw } from 'lucide-vue-next'
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
  PromptNotFoundError,
  PromptOrderConflictError,
  useAdminPromptsStore,
} from '@/stores/admin/prompts'

const promptsStore = useAdminPromptsStore()
const categoriesStore = useAdminPromptCategoriesStore()
const route = useRoute()
const router = useRouter()
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

/**
 * Moves `sourceId` to the place of `targetId`. Both failures a user can produce are recoverable and
 * end in the same place: the authoritative order is loaded again, and the move may be repeated.
 */
async function reorderPrompts(sourceId: number, targetId: number) {
  try {
    await promptsStore.reorderPrompts(sourceId, targetId)
  } catch (error) {
    if (error instanceof PromptOrderConflictError) {
      toast({
        title: 'Prompt order changed',
        description: 'The current order was reloaded. Try reordering again.',
        variant: 'destructive',
      })
    } else if (error instanceof PromptNotFoundError) {
      toast({
        title: 'Prompt no longer exists',
        description: 'The current order was reloaded. One of the moved Prompts is gone.',
        variant: 'destructive',
      })
    } else {
      toast({
        title: 'Failed to reorder prompts',
        description: 'The current order was reloaded. Try reordering again.',
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
    <AdminPageHeader title="All Prompts" breakpoint="lg">
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
            Reload
          </Button>
          <Button as-child size="sm">
            <RouterLink :to="{ name: 'admin-prompt-new', query: route.query }">
              <Plus class="size-4" />
              New Prompt
            </RouterLink>
          </Button>
        </div>
      </template>
    </AdminPageHeader>

    <Alert v-if="promptsStore.error" variant="destructive">
      Failed to load prompts. {{ promptsStore.error }}
    </Alert>

    <Card
      v-else-if="promptsStore.isLoading && promptsStore.prompts.length === 0"
      class="px-4 py-12 text-center text-sm text-muted-foreground"
    >
      Loading prompts...
    </Card>

    <Card
      v-else-if="promptsStore.prompts.length === 0"
      class="px-4 py-12 text-center text-sm text-muted-foreground"
    >
      No prompts found.
    </Card>

    <Card
      v-else-if="filteredPrompts.length === 0"
      class="space-y-3 px-4 py-12 text-center text-sm text-muted-foreground"
      data-testid="prompt-filter-empty"
    >
      <p>No Prompts match the active filters.</p>
      <Button variant="outline" size="sm" @click="resetFilters">Reset filters</Button>
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
