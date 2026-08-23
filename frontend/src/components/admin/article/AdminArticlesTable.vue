<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AdminArticleDropSkeleton from './AdminArticleDropSkeleton.vue'
import AdminArticleRow from './AdminArticleRow.vue'
import { Card } from '@/components/ui/card'
import { Table, TableBody, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useAdminArticleReorder } from '@/composables/useAdminArticleReorder'
import type { AdminArticleListItemDto, AdminArticleType } from '@/stores/admin/articles'

interface Props {
  /** The one type every row of this table has. Each list page is per type, so the page names it. */
  articleType: AdminArticleType
  /** The route that edits a row of this type — one editor per type, not one union form. */
  editRouteName: string
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
  void router.push({
    name: props.editRouteName,
    params: { id: article.id },
    query: route.query,
  })
}
</script>

<template>
  <Card class="overflow-hidden" :aria-busy="isReorderDisabled">
    <div
      v-if="reordering"
      class="border-b border-border bg-muted/20 px-4 py-2 text-sm text-muted-foreground"
      role="status"
    >
      Saving article order...
    </div>

    <Table class="min-w-[48rem]">
      <TableHeader>
        <TableRow>
          <TableHead class="w-14">Order</TableHead>
          <TableHead class="w-14">Image</TableHead>
          <TableHead>Name</TableHead>
          <TableHead>Category</TableHead>
          <TableHead>Supplier</TableHead>
          <TableHead>Variants</TableHead>
          <TableHead>Status</TableHead>
          <TableHead class="text-right">Actions</TableHead>
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
            :article-type="articleType"
            :edit-route-name="editRouteName"
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
