<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AdminArticleDropSkeleton from './AdminArticleDropSkeleton.vue'
import AdminArticleRow from './AdminArticleRow.vue'
import { Card } from '@/components/ui/card'
import { Table, TableBody, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useAdminArticleReorder } from '@/composables/useAdminArticleReorder'
import type { AdminArticleRowDto, AdminArticleType } from '@/stores/admin/articles'

interface Props {
  /** The one type every row of this table has. Each list page is per type, so the page names it. */
  articleType: AdminArticleType
  /** The route that edits a row of this type — one editor per type, not one union form. */
  editRouteName: string
  articles: readonly Readonly<AdminArticleRowDto>[]
  /**
   * Shows the sync column of a synced type: when the last run saw the row, and whether the partner
   * still lists it. Only the t-shirt list has a second owner (ADR 0003); the mug list omits it and
   * gets the table it always had.
   */
  syncColumn?: boolean
  reordering?: boolean
  reorderDisabled?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  syncColumn: false,
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

function editArticle(article: Readonly<AdminArticleRowDto>) {
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
          <TableHead v-if="syncColumn">Synced</TableHead>
          <TableHead>Status</TableHead>
          <TableHead class="text-right">Actions</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        <template v-for="article in articles" :key="article.id">
          <AdminArticleDropSkeleton
            v-if="isDropIndicator(article.id, 'before')"
            :article-id="article.id"
            :sync-column="syncColumn"
            @drag-over="onDragOver(article, $event)"
            @drop="onDrop(article, $event)"
          />

          <AdminArticleRow
            :article="article"
            :article-type="articleType"
            :sync-column="syncColumn"
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
            :sync-column="syncColumn"
            @drag-over="onDragOver(article, $event)"
            @drop="onDrop(article, $event)"
          />
        </template>
      </TableBody>
    </Table>
  </Card>
</template>
