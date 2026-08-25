<script setup lang="ts">
import { GripVertical, Pencil } from 'lucide-vue-next'
import { RouterLink, useRoute } from 'vue-router'
import AdminExampleImageThumbnail from '@/components/admin/shared/AdminExampleImageThumbnail.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { TableCell, TableRow } from '@/components/ui/table'
import { formatAdminStamp } from '@/lib/adminStamp'
import { cn } from '@/lib/utils'
import { variantExampleImageUrl } from '@/lib/variantExampleImage'
import type { AdminArticleRowDto, AdminArticleType } from '@/stores/admin/articles'

interface Props {
  article: Readonly<AdminArticleRowDto>
  /** Each type stores its variant photos in its own folder, so the type names the folder. */
  articleType: AdminArticleType
  /** The route that edits this row. There is one editor per type, not one union form. */
  editRouteName: string
  /** Shows this row's sync cell. Only the t-shirt list passes it — see `AdminArticlesTable`. */
  syncColumn?: boolean
  dragging?: boolean
  reorderDisabled?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  syncColumn: false,
  dragging: false,
  reorderDisabled: false,
})

const emit = defineEmits<{
  edit: [article: Readonly<AdminArticleRowDto>]
  dragStart: [article: Readonly<AdminArticleRowDto>, event: DragEvent]
  dragEnd: []
  dragOver: [article: Readonly<AdminArticleRowDto>, event: DragEvent]
  drop: [article: Readonly<AdminArticleRowDto>, event: DragEvent]
  pointerDown: [article: Readonly<AdminArticleRowDto>, event: PointerEvent]
  pointerMove: [event: PointerEvent]
  pointerUp: [event: PointerEvent]
  pointerCancel: [event: PointerEvent]
  lostPointerCapture: [event: PointerEvent]
}>()

const route = useRoute()

function rowExampleImageUrl(filename: string, size: number) {
  return variantExampleImageUrl(props.articleType, filename, size)
}

function editRoute(article: Readonly<AdminArticleRowDto>) {
  return {
    name: props.editRouteName,
    params: { id: article.id },
    query: route.query,
  }
}

function formatCategory(article: Readonly<AdminArticleRowDto>) {
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
        :image-url="rowExampleImageUrl"
      />
    </TableCell>
    <TableCell class="min-w-40 text-foreground">{{ article.name }}</TableCell>
    <TableCell class="whitespace-nowrap text-muted-foreground">
      {{ formatCategory(article) }}
    </TableCell>
    <TableCell class="whitespace-nowrap text-muted-foreground">
      {{ article.supplierName || '—' }}
    </TableCell>
    <TableCell class="whitespace-nowrap text-muted-foreground">
      {{ article.variantCount }}
    </TableCell>
    <TableCell v-if="syncColumn" class="whitespace-nowrap text-muted-foreground">
      <div data-testid="article-synced-at">{{ formatAdminStamp(article.syncedAt) ?? '—' }}</div>
      <Badge v-if="article.missingAtSpreadconnect" variant="warning" data-testid="article-missing">
        Missing at Spreadconnect
      </Badge>
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
