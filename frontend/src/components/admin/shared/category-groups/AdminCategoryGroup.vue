<script setup lang="ts">
import { computed, shallowRef } from 'vue'
import { ChevronDown, GripVertical, Pencil, Plus, Trash2 } from 'lucide-vue-next'
import AdminCategoryDropSkeleton from './AdminCategoryDropSkeleton.vue'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Card } from '@/components/ui/card'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { cn } from '@/lib/utils'
import type { AdminCategoryItem, AdminSubcategoryItem } from './types'

interface Props {
  category: AdminCategoryItem
  subcategories: AdminSubcategoryItem[]
  expanded?: boolean
  dragging?: boolean
  reordering?: boolean
  reorderingSubcategories?: boolean
  categoryLabel?: string
  subcategoryLabel?: string
  subcategoryCountLabel?: string
  subcategoryPluralLabel?: string
  addSubcategoryLabel?: string
  emptySubcategoriesLabel?: string
  testIdPrefix?: string
  class?: string
}

const props = withDefaults(defineProps<Props>(), {
  expanded: false,
  dragging: false,
  reordering: false,
  reorderingSubcategories: false,
  categoryLabel: 'prompt category',
  subcategoryLabel: 'prompt subcategory',
  subcategoryCountLabel: 'subcategory',
  subcategoryPluralLabel: 'subcategories',
  addSubcategoryLabel: 'Add Subcategory',
  emptySubcategoriesLabel: 'No subcategories in this prompt category yet.',
  testIdPrefix: 'prompt',
  class: '',
})

const emit = defineEmits<{
  (event: 'update:expanded', expanded: boolean): void
  (event: 'dragStart', dragEvent: DragEvent): void
  (event: 'dragEnd'): void
  (event: 'editCategory', category: AdminCategoryItem): void
  (event: 'deleteCategory', category: AdminCategoryItem): void
  (event: 'addSubcategory', category: AdminCategoryItem): void
  (event: 'editSubcategory', subcategory: AdminSubcategoryItem): void
  (event: 'deleteSubcategory', subcategory: AdminSubcategoryItem): void
  (event: 'reorderSubcategories', sourceSubcategoryId: number, targetSubcategoryId: number): void
}>()

const draggedSubcategoryId = shallowRef<number | null>(null)
const dragOverSubcategoryId = shallowRef<number | null>(null)

type DropIndicatorPlacement = 'before' | 'after'

const subcategoryCountLabel = computed(() => {
  const count = props.subcategories.length
  return `${count} ${count === 1 ? props.subcategoryCountLabel : props.subcategoryPluralLabel}`
})

const subcategoryDropIndicator = computed<{
  subcategoryId: number
  placement: DropIndicatorPlacement
} | null>(() => {
  if (draggedSubcategoryId.value === null || dragOverSubcategoryId.value === null) {
    return null
  }

  const sourceIndex = props.subcategories.findIndex(
    (subcategory) => subcategory.id === draggedSubcategoryId.value,
  )
  const targetIndex = props.subcategories.findIndex(
    (subcategory) => subcategory.id === dragOverSubcategoryId.value,
  )
  if (sourceIndex < 0 || targetIndex < 0 || sourceIndex === targetIndex) {
    return null
  }

  return {
    subcategoryId: dragOverSubcategoryId.value,
    placement: sourceIndex < targetIndex ? 'after' : 'before',
  }
})

function isSubcategoryDropIndicator(subcategoryId: number, placement: DropIndicatorPlacement) {
  return (
    subcategoryDropIndicator.value?.subcategoryId === subcategoryId &&
    subcategoryDropIndicator.value.placement === placement
  )
}

function clearSubcategoryDragState() {
  draggedSubcategoryId.value = null
  dragOverSubcategoryId.value = null
}

function onSubcategoryDragStart(subcategory: AdminSubcategoryItem, event: DragEvent) {
  if (props.reordering || props.reorderingSubcategories) {
    event.preventDefault()
    return
  }

  draggedSubcategoryId.value = subcategory.id
  event.dataTransfer?.setData('text/plain', String(subcategory.id))
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move'
  }
}

function onSubcategoryDragOver(subcategory: AdminSubcategoryItem, event: DragEvent) {
  if (
    props.reordering ||
    props.reorderingSubcategories ||
    draggedSubcategoryId.value === null ||
    draggedSubcategoryId.value === subcategory.id
  ) {
    return
  }

  event.preventDefault()
  dragOverSubcategoryId.value = subcategory.id
  if (event.dataTransfer) {
    event.dataTransfer.dropEffect = 'move'
  }
}

function onSubcategoryDrop(subcategory: AdminSubcategoryItem, event: DragEvent) {
  event.preventDefault()
  if (
    props.reordering ||
    props.reorderingSubcategories ||
    draggedSubcategoryId.value === null ||
    draggedSubcategoryId.value === subcategory.id
  ) {
    clearSubcategoryDragState()
    return
  }

  const sourceSubcategoryId = draggedSubcategoryId.value
  const sourceExists = props.subcategories.some((item) => item.id === sourceSubcategoryId)
  const targetExists = props.subcategories.some((item) => item.id === subcategory.id)
  if (!sourceExists || !targetExists) {
    clearSubcategoryDragState()
    return
  }

  clearSubcategoryDragState()
  emit('reorderSubcategories', sourceSubcategoryId, subcategory.id)
}
</script>

<template>
  <Collapsible v-slot="{ open }" :open="expanded" @update:open="emit('update:expanded', $event)">
    <Card
      as="section"
      :class="
        cn(
          'overflow-hidden transition-colors',
          dragging && 'opacity-50',
          reordering && 'pointer-events-none opacity-70',
          props.class,
        )
      "
    >
      <div
        class="flex flex-col gap-3 border-b border-border bg-muted/20 px-4 py-3 lg:flex-row lg:items-center lg:justify-between"
      >
        <div class="flex min-w-0 items-start gap-2">
          <Button
            type="button"
            size="icon-sm"
            variant="ghost"
            class="mt-0.5 cursor-grab text-muted-foreground active:cursor-grabbing"
            :disabled="reordering"
            :draggable="!reordering"
            :aria-label="`Drag ${categoryLabel} ${category.name}`"
            :title="`Drag ${categoryLabel} ${category.name}`"
            @dragstart="emit('dragStart', $event)"
            @dragend="emit('dragEnd')"
          >
            <GripVertical class="size-4" />
            <span class="sr-only">Reorder category</span>
          </Button>

          <CollapsibleTrigger
            type="button"
            class="mt-0.5 inline-flex size-8 shrink-0 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
            :aria-label="`${open ? 'Hide' : 'Show'} subcategories for ${category.name}`"
            :title="`${open ? 'Hide' : 'Show'} subcategories for ${category.name}`"
          >
            <ChevronDown class="size-4 transition-transform" :class="{ 'rotate-180': open }" />
            <span class="sr-only">{{ open ? 'Hide' : 'Show' }} subcategories</span>
          </CollapsibleTrigger>

          <div class="min-w-0">
            <div class="flex flex-wrap items-center gap-2">
              <h2 class="truncate text-base font-semibold text-foreground">{{ category.name }}</h2>
              <Badge :variant="category.active ? 'success' : 'muted'">
                {{ category.active ? 'Active' : 'Inactive' }}
              </Badge>
            </div>
            <p class="mt-1 text-sm text-muted-foreground">{{ subcategoryCountLabel }}</p>
            <p
              v-if="category.description"
              class="mt-1 line-clamp-2 text-sm text-muted-foreground"
              :title="category.description"
            >
              {{ category.description }}
            </p>
          </div>
        </div>

        <div class="flex flex-wrap items-center gap-2">
          <Button
            type="button"
            size="sm"
            variant="outline"
            :aria-label="`Add subcategory to ${category.name}`"
            :title="`Add subcategory to ${category.name}`"
            @click="emit('addSubcategory', category)"
          >
            <Plus class="size-4" />
            {{ addSubcategoryLabel }}
          </Button>
          <Button
            type="button"
            size="icon-sm"
            variant="outline"
            :aria-label="`Edit ${categoryLabel} ${category.name}`"
            :title="`Edit ${categoryLabel} ${category.name}`"
            @click="emit('editCategory', category)"
          >
            <Pencil class="size-4" />
            <span class="sr-only">Edit category</span>
          </Button>
          <Button
            type="button"
            size="icon-sm"
            variant="outline"
            :aria-label="`Delete ${categoryLabel} ${category.name}`"
            :title="`Delete ${categoryLabel} ${category.name}`"
            @click="emit('deleteCategory', category)"
          >
            <Trash2 class="size-4" />
            <span class="sr-only">Delete category</span>
          </Button>
        </div>
      </div>

      <CollapsibleContent>
        <div v-if="subcategories.length === 0" class="px-4 py-5 text-sm text-muted-foreground">
          {{ emptySubcategoriesLabel }}
        </div>

        <div v-else class="overflow-x-auto" :aria-busy="reorderingSubcategories">
          <div
            v-if="reorderingSubcategories"
            class="border-b border-border bg-muted/20 px-4 py-2 text-sm text-muted-foreground"
            role="status"
          >
            Saving subcategory order...
          </div>
          <Table>
            <TableHeader class="bg-muted/10">
              <TableRow>
                <TableHead class="w-14">Order</TableHead>
                <TableHead class="min-w-48">Name</TableHead>
                <TableHead class="w-28">Status</TableHead>
                <TableHead class="min-w-72">Description</TableHead>
                <TableHead class="w-28 text-right">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              <template v-for="subcategory in subcategories" :key="subcategory.id">
                <AdminCategoryDropSkeleton
                  v-if="isSubcategoryDropIndicator(subcategory.id, 'before')"
                  variant="subcategory"
                  :test-id-prefix="testIdPrefix"
                  @drag-over="onSubcategoryDragOver(subcategory, $event)"
                  @drop="onSubcategoryDrop(subcategory, $event)"
                />

                <TableRow
                  :data-testid="`${testIdPrefix}-subcategory-drop-${subcategory.id}`"
                  :class="
                    cn(
                      draggedSubcategoryId === subcategory.id && 'opacity-50',
                      reorderingSubcategories && 'opacity-70',
                    )
                  "
                  @dragover="onSubcategoryDragOver(subcategory, $event)"
                  @drop="onSubcategoryDrop(subcategory, $event)"
                >
                  <TableCell class="whitespace-nowrap">
                    <Button
                      type="button"
                      size="icon-sm"
                      variant="ghost"
                      class="cursor-grab text-muted-foreground active:cursor-grabbing"
                      :disabled="reordering || reorderingSubcategories"
                      :draggable="!reordering && !reorderingSubcategories"
                      :aria-label="`Drag ${subcategoryLabel} ${subcategory.name}`"
                      :title="`Drag ${subcategoryLabel} ${subcategory.name}`"
                      @dragstart="onSubcategoryDragStart(subcategory, $event)"
                      @dragend="clearSubcategoryDragState"
                    >
                      <GripVertical class="size-4" />
                      <span class="sr-only">Reorder subcategory</span>
                    </Button>
                  </TableCell>
                  <TableCell class="text-foreground">{{ subcategory.name }}</TableCell>
                  <TableCell>
                    <Badge :variant="subcategory.active ? 'success' : 'muted'">
                      {{ subcategory.active ? 'Active' : 'Inactive' }}
                    </Badge>
                  </TableCell>
                  <TableCell
                    class="max-w-xl text-muted-foreground"
                    :title="subcategory.description ?? ''"
                  >
                    <span class="line-clamp-2">{{ subcategory.description || '—' }}</span>
                  </TableCell>
                  <TableCell class="whitespace-nowrap text-right">
                    <div class="inline-flex items-center gap-2">
                      <Button
                        type="button"
                        size="icon-sm"
                        variant="outline"
                        :aria-label="`Edit ${subcategoryLabel} ${subcategory.name}`"
                        :title="`Edit ${subcategoryLabel} ${subcategory.name}`"
                        @click="emit('editSubcategory', subcategory)"
                      >
                        <Pencil class="size-4" />
                        <span class="sr-only">Edit subcategory</span>
                      </Button>
                      <Button
                        type="button"
                        size="icon-sm"
                        variant="outline"
                        :aria-label="`Delete ${subcategoryLabel} ${subcategory.name}`"
                        :title="`Delete ${subcategoryLabel} ${subcategory.name}`"
                        @click="emit('deleteSubcategory', subcategory)"
                      >
                        <Trash2 class="size-4" />
                        <span class="sr-only">Delete subcategory</span>
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>

                <AdminCategoryDropSkeleton
                  v-if="isSubcategoryDropIndicator(subcategory.id, 'after')"
                  variant="subcategory"
                  :test-id-prefix="testIdPrefix"
                  @drag-over="onSubcategoryDragOver(subcategory, $event)"
                  @drop="onSubcategoryDrop(subcategory, $event)"
                />
              </template>
            </TableBody>
          </Table>
        </div>
      </CollapsibleContent>
    </Card>
  </Collapsible>
</template>
