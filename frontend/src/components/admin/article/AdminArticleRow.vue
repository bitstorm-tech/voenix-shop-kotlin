<script setup lang="ts">
import { GripVertical, Pencil } from 'lucide-vue-next'
import { RouterLink, useRoute } from 'vue-router'
import AdminExampleImageThumbnail from '@/components/admin/shared/AdminExampleImageThumbnail.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { TableCell, TableRow } from '@/components/ui/table'
import { cn } from '@/lib/utils'
import { variantExampleImageUrl } from '@/lib/variantExampleImage'
import {
  ARTICLE_TYPE_LABELS,
  type AdminArticleListItem,
  type AdminArticleType,
} from '@/stores/admin/articles'

interface Props {
  article: Readonly<AdminArticleListItem>
  dragging?: boolean
  reorderDisabled?: boolean
}

withDefaults(defineProps<Props>(), {
  dragging: false,
  reorderDisabled: false,
})

const emit = defineEmits<{
  edit: [article: Readonly<AdminArticleListItem>]
  dragStart: [article: Readonly<AdminArticleListItem>, event: DragEvent]
  dragEnd: []
  dragOver: [article: Readonly<AdminArticleListItem>, event: DragEvent]
  drop: [article: Readonly<AdminArticleListItem>, event: DragEvent]
  pointerDown: [article: Readonly<AdminArticleListItem>, event: PointerEvent]
  pointerMove: [event: PointerEvent]
  pointerUp: [event: PointerEvent]
  pointerCancel: [event: PointerEvent]
  lostPointerCapture: [event: PointerEvent]
}>()

const route = useRoute()

/** Each type stores its variant photos in its own folder, so the row's type names the folder. */
function rowExampleImageUrl(articleType: AdminArticleType) {
  return (filename: string, size: number) => variantExampleImageUrl(articleType, filename, size)
}

/** The route that edits this row. There is one editor per type, not one union form. */
function editRoute(article: Readonly<AdminArticleListItem>) {
  return {
    name: article.articleType === 'MUG' ? 'admin-mug-article-edit' : 'admin-tshirt-article-edit',
    params: { id: article.id },
    query: route.query,
  }
}

function formatCategory(article: Readonly<AdminArticleListItem>) {
  if (!article.categoryName) {
    return '—'
  }

  return article.subcategoryName
    ? `${article.categoryName} / ${article.subcategoryName}`
    : article.categoryName
}
</script>

<template>
  <TableRow
    :data-testid="`article-drop-${article.id}`"
    :data-article-drop-id="article.id"
    :class="cn('cursor-pointer', dragging && 'opacity-50')"
    tabindex="0"
    @click="emit('edit', article)"
    @keydown.enter.self="emit('edit', article)"
    @keydown.space.self.prevent="emit('edit', article)"
    @dragover="emit('dragOver', article, $event)"
    @drop="emit('drop', article, $event)"
  >
    <TableCell class="whitespace-nowrap" @click.stop @keydown.stop>
      <Button
        type="button"
        size="icon-lg"
        variant="ghost"
        class="touch-none cursor-grab text-muted-foreground active:cursor-grabbing"
        :disabled="reorderDisabled"
        :draggable="!reorderDisabled"
        :aria-label="`Drag article ${article.name}`"
        :title="`Drag article ${article.name}`"
        @dragstart="emit('dragStart', article, $event)"
        @dragend="emit('dragEnd')"
        @pointerdown="emit('pointerDown', article, $event)"
        @pointermove="emit('pointerMove', $event)"
        @pointerup="emit('pointerUp', $event)"
        @pointercancel="emit('pointerCancel', $event)"
        @lostpointercapture="emit('lostPointerCapture', $event)"
      >
        <GripVertical class="size-4" />
        <span class="sr-only">Reorder article</span>
      </Button>
    </TableCell>
    <TableCell class="whitespace-nowrap">
      <AdminExampleImageThumbnail
        :filename="article.exampleImageFilename"
        :title="article.name"
        :image-url="rowExampleImageUrl(article.articleType)"
      />
    </TableCell>
    <TableCell class="min-w-40 text-foreground">{{ article.name }}</TableCell>
    <TableCell class="whitespace-nowrap">
      <Badge variant="muted" data-testid="article-type-badge">
        {{ ARTICLE_TYPE_LABELS[article.articleType] }}
      </Badge>
    </TableCell>
    <TableCell class="whitespace-nowrap text-muted-foreground">
      {{ formatCategory(article) }}
    </TableCell>
    <TableCell class="whitespace-nowrap text-muted-foreground">
      {{ article.supplierName || '—' }}
    </TableCell>
    <TableCell class="whitespace-nowrap text-muted-foreground">
      {{ article.variantCount }}
    </TableCell>
    <TableCell class="whitespace-nowrap">
      <Badge :variant="article.active ? 'success' : 'muted'">
        {{ article.active ? 'Active' : 'Inactive' }}
      </Badge>
    </TableCell>
    <TableCell class="whitespace-nowrap text-right">
      <Button as-child variant="outline" size="icon-sm" @click.stop>
        <RouterLink
          :to="editRoute(article)"
          :aria-label="`Edit article ${article.name}`"
          :title="`Edit article ${article.name}`"
          draggable="false"
          @click.stop
        >
          <Pencil class="size-4" />
          <span class="sr-only">Edit</span>
        </RouterLink>
      </Button>
    </TableCell>
  </TableRow>
</template>
