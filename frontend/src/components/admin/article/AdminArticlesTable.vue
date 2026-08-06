<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import AdminArticleDropSkeleton from './AdminArticleDropSkeleton.vue'
import AdminArticleRow from './AdminArticleRow.vue'
import { Card } from '@/components/ui/card'
import { Table, TableBody, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useAdminArticleReorder } from '@/composables/useAdminArticleReorder'
import type { AdminArticleListItemDto } from '@/stores/admin/articles'

interface Props {
  articles: readonly Readonly<AdminArticleListItemDto>[]
  reordering?: boolean
  reorderDisabled?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  reordering: false,
  reorderDisabled: false,
})

const emit = defineEmits<{
  reorderArticles: [sourceArticleId: number, targetArticleId: number]
}>()

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const isReorderDisabled = computed(() => props.reordering || props.reorderDisabled)
const {
  draggedArticleId,
  clearVisualDragState,
  isDropIndicator,
  onDragOver,
  onDragStart,
  onDrop,
  onLostPointerCapture,
  onPointerCancel,
  onPointerDown,
  onPointerMove,
  onPointerUp,
} = useAdminArticleReorder({
  articles: () => props.articles,
  reorderDisabled: isReorderDisabled,
  onReorder: (sourceArticleId, targetArticleId) => {
    emit('reorderArticles', sourceArticleId, targetArticleId)
  },
})

function editArticle(article: Readonly<AdminArticleListItemDto>) {
  void router.push({ name: 'admin-article-edit', params: { id: article.id }, query: route.query })
}
</script>

<template>
  <Card class="overflow-hidden" :aria-busy="isReorderDisabled">
    <div
      v-if="reordering"
      class="border-b border-border bg-muted/20 px-4 py-2 text-sm text-muted-foreground"
      role="status"
    >
      {{ t('admin.articles.savingOrder') }}
    </div>

    <Table class="min-w-[52rem]">
      <TableHeader>
        <TableRow>
          <TableHead class="w-14">{{ t('admin.articles.table.order') }}</TableHead>
          <TableHead class="w-14">{{ t('admin.articles.table.image') }}</TableHead>
          <TableHead>{{ t('admin.articles.table.name') }}</TableHead>
          <TableHead>{{ t('admin.articles.table.category') }}</TableHead>
          <TableHead>{{ t('admin.articles.table.supplier') }}</TableHead>
          <TableHead>{{ t('admin.articles.table.variants') }}</TableHead>
          <TableHead>{{ t('admin.articles.table.status') }}</TableHead>
          <TableHead class="text-right">{{ t('admin.articles.table.actions') }}</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        <template v-for="article in articles" :key="article.id">
          <AdminArticleDropSkeleton
            v-if="isDropIndicator(article.id, 'before')"
            :article-id="article.id"
            @drag-over="onDragOver(article, $event)"
            @drop="onDrop(article, $event)"
          />

          <AdminArticleRow
            :article="article"
            :dragging="draggedArticleId === article.id"
            :reorder-disabled="isReorderDisabled"
            @edit="editArticle"
            @drag-start="onDragStart"
            @drag-end="clearVisualDragState"
            @drag-over="onDragOver"
            @drop="onDrop"
            @pointer-down="onPointerDown"
            @pointer-move="onPointerMove"
            @pointer-up="onPointerUp"
            @pointer-cancel="onPointerCancel"
            @lost-pointer-capture="onLostPointerCapture"
          />

          <AdminArticleDropSkeleton
            v-if="isDropIndicator(article.id, 'after')"
            :article-id="article.id"
            @drag-over="onDragOver(article, $event)"
            @drop="onDrop(article, $event)"
          />
        </template>
      </TableBody>
    </Table>
  </Card>
</template>
