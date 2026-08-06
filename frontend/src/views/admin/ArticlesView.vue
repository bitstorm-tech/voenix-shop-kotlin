<script setup lang="ts">
import { onMounted } from 'vue'
import { Plus, RefreshCw } from 'lucide-vue-next'
import { useI18n } from 'vue-i18n'
import { RouterLink, useRoute } from 'vue-router'
import AdminArticlesFilterBar from '@/components/admin/article/AdminArticlesFilterBar.vue'
import AdminArticlesTable from '@/components/admin/article/AdminArticlesTable.vue'
import AdminPageHeader from '@/components/admin/shared/AdminPageHeader.vue'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { useAdminArticleListFilters } from '@/composables/useAdminArticleListFilters'
import { useToast } from '@/composables/useToast'
import { useAdminArticleCategoriesStore } from '@/stores/admin/articleCategories'
import { useAdminArticleSubcategoriesStore } from '@/stores/admin/articleSubcategories'
import { ArticleOrderConflictError, useAdminArticlesStore } from '@/stores/admin/articles'

const articlesStore = useAdminArticlesStore()
const categoriesStore = useAdminArticleCategoriesStore()
const subcategoriesStore = useAdminArticleSubcategoriesStore()
const route = useRoute()
const { t } = useI18n()
const { toast } = useToast()

const {
  criteria,
  subcategoryOptions,
  filteredArticles,
  hasActiveFilters,
  setCategoryId,
  setSubcategoryId,
  setStatus,
  setName,
  resetFilters,
} = useAdminArticleListFilters({
  articles: () => articlesStore.articles,
  categories: () => categoriesStore.categories,
  subcategories: () => subcategoriesStore.subcategories,
})

async function reorderArticles(sourceId: number, targetId: number) {
  try {
    await articlesStore.reorderArticles(sourceId, targetId)
  } catch (error) {
    if (error instanceof ArticleOrderConflictError) {
      toast({
        title: t('admin.articles.errors.orderChangedTitle'),
        description: t('admin.articles.errors.orderChangedDescription'),
        variant: 'destructive',
      })
    } else {
      toast({
        title: t('admin.articles.errors.reorderFailedTitle'),
        description: t('admin.articles.errors.reorderFailedDescription'),
        variant: 'destructive',
      })
    }

    await articlesStore.fetchArticles()
  }
}

onMounted(async () => {
  await Promise.all([
    articlesStore.fetchArticles(),
    categoriesStore.fetchCategories(),
    subcategoriesStore.fetchSubcategories(),
  ])
})
</script>

<template>
  <section class="space-y-4">
    <AdminPageHeader :title="t('admin.articles.title')" breakpoint="lg">
      <template #actions>
        <div class="flex flex-wrap items-center gap-2">
          <AdminArticlesFilterBar
            :criteria="criteria"
            :categories="categoriesStore.categories"
            :subcategories="subcategoryOptions"
            :has-active-filters="hasActiveFilters"
            @category-id-change="setCategoryId"
            @subcategory-id-change="setSubcategoryId"
            @status-change="setStatus"
            @name-change="setName"
            @reset="resetFilters"
          />
          <Button
            variant="outline"
            size="sm"
            :disabled="articlesStore.isLoading || articlesStore.isReordering"
            @click="articlesStore.fetchArticles"
          >
            <RefreshCw :class="['size-4', articlesStore.isLoading && 'animate-spin']" />
            {{ t('admin.articles.reload') }}
          </Button>
          <Button as-child size="sm">
            <RouterLink :to="{ name: 'admin-article-new', query: route.query }">
              <Plus class="size-4" />
              {{ t('admin.articles.add') }}
            </RouterLink>
          </Button>
        </div>
      </template>
    </AdminPageHeader>

    <Alert v-if="articlesStore.error" variant="destructive">
      {{ t('admin.articles.loadFailed') }}
    </Alert>

    <Card
      v-else-if="articlesStore.isLoading && articlesStore.articles.length === 0"
      class="px-4 py-12 text-center text-sm text-muted-foreground"
    >
      {{ t('admin.articles.loading') }}
    </Card>

    <Card
      v-else-if="articlesStore.articles.length === 0"
      class="px-4 py-12 text-center text-sm text-muted-foreground"
    >
      {{ t('admin.articles.empty') }}
    </Card>

    <Card
      v-else-if="filteredArticles.length === 0"
      class="space-y-3 px-4 py-12 text-center text-sm text-muted-foreground"
      data-testid="article-filter-empty"
    >
      <p>{{ t('admin.articles.filters.empty') }}</p>
      <Button variant="outline" size="sm" @click="resetFilters">
        {{ t('admin.articles.filters.reset') }}
      </Button>
    </Card>

    <AdminArticlesTable
      v-else
      :articles="filteredArticles"
      :reordering="articlesStore.isReordering"
      :reorder-disabled="articlesStore.isLoading || hasActiveFilters"
      @reorder-articles="reorderArticles"
    />
  </section>
</template>
