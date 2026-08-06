<script setup lang="ts">
import { computed } from 'vue'
import { ChevronDown, Pencil, Plus, Trash2 } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible'
import {
  Table,
  TableBody,
  TableCell,
  TableEmpty,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import type { AdminPromptSlotTypeDto, AdminPromptSlotVariantDto } from '@/stores/admin/promptSlots'

interface Props {
  slotType: AdminPromptSlotTypeDto
  variants: AdminPromptSlotVariantDto[]
  expanded?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  expanded: false,
})

const emit = defineEmits<{
  (event: 'update:expanded', expanded: boolean): void
  (event: 'editSlotType', slotType: AdminPromptSlotTypeDto): void
  (event: 'deleteSlotType', slotType: AdminPromptSlotTypeDto): void
  (event: 'addVariant', slotType: AdminPromptSlotTypeDto): void
  (event: 'editVariant', variant: AdminPromptSlotVariantDto): void
  (event: 'deleteVariant', variant: AdminPromptSlotVariantDto): void
}>()

const variantCountLabel = computed(() => {
  const count = props.variants.length
  return `${count} ${count === 1 ? 'variant' : 'variants'}`
})

function promptSummary(prompt: string) {
  return prompt.trim() || '-'
}
</script>

<template>
  <Collapsible v-slot="{ open }" :open="expanded" @update:open="emit('update:expanded', $event)">
    <Card as="section" class="overflow-hidden">
      <div
        class="flex flex-col gap-3 border-b border-border bg-muted/20 px-4 py-3 lg:flex-row lg:items-center lg:justify-between"
      >
        <div class="flex min-w-0 items-start gap-2">
          <CollapsibleTrigger
            type="button"
            class="mt-0.5 inline-flex size-8 shrink-0 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
            :aria-label="`${open ? 'Hide' : 'Show'} variants for ${slotType.name}`"
            :title="`${open ? 'Hide' : 'Show'} variants for ${slotType.name}`"
          >
            <ChevronDown class="size-4 transition-transform" :class="{ 'rotate-180': open }" />
            <span class="sr-only">{{ open ? 'Hide' : 'Show' }} variants</span>
          </CollapsibleTrigger>

          <div class="min-w-0">
            <div class="flex flex-wrap items-center gap-2">
              <h2 class="truncate text-base font-semibold text-foreground">{{ slotType.name }}</h2>
            </div>
            <p class="mt-1 text-sm text-muted-foreground">{{ variantCountLabel }}</p>
          </div>
        </div>

        <div class="flex flex-wrap items-center gap-2">
          <Button
            type="button"
            size="sm"
            variant="outline"
            :aria-label="`Add variant to ${slotType.name}`"
            :title="`Add variant to ${slotType.name}`"
            @click="emit('addVariant', slotType)"
          >
            <Plus class="size-4" />
            Add Variant
          </Button>
          <Button
            type="button"
            size="icon-sm"
            variant="outline"
            :aria-label="`Edit prompt slot type ${slotType.name}`"
            :title="`Edit prompt slot type ${slotType.name}`"
            @click="emit('editSlotType', slotType)"
          >
            <Pencil class="size-4" />
            <span class="sr-only">Edit slot type</span>
          </Button>
          <Button
            type="button"
            size="icon-sm"
            variant="outline"
            :aria-label="`Delete prompt slot type ${slotType.name}`"
            :title="`Delete prompt slot type ${slotType.name}`"
            @click="emit('deleteSlotType', slotType)"
          >
            <Trash2 class="size-4" />
            <span class="sr-only">Delete slot type</span>
          </Button>
        </div>
      </div>

      <CollapsibleContent>
        <div class="overflow-x-auto">
          <Table>
            <TableHeader class="bg-muted/10">
              <TableRow>
                <TableHead class="min-w-44">Name</TableHead>
                <TableHead class="min-w-80">Prompt</TableHead>
                <TableHead class="min-w-56">Description</TableHead>
                <TableHead class="w-36">LLM</TableHead>
                <TableHead class="w-32 text-right">Assigned</TableHead>
                <TableHead class="w-28 text-right">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              <TableEmpty v-if="variants.length === 0" :colspan="6">No variants yet.</TableEmpty>
              <template v-else>
                <TableRow v-for="variant in variants" :key="variant.id">
                  <TableCell class="text-foreground">{{ variant.name }}</TableCell>
                  <TableCell class="max-w-2xl text-muted-foreground" :title="variant.prompt">
                    <span class="line-clamp-2">{{ promptSummary(variant.prompt) }}</span>
                  </TableCell>
                  <TableCell
                    class="max-w-md text-muted-foreground"
                    :title="variant.description ?? ''"
                  >
                    <span class="line-clamp-2">{{ variant.description || '-' }}</span>
                  </TableCell>
                  <TableCell class="whitespace-nowrap text-muted-foreground">
                    {{ variant.llm || '-' }}
                  </TableCell>
                  <TableCell class="whitespace-nowrap text-right text-muted-foreground">
                    {{ variant.assignedPromptCount }}
                  </TableCell>
                  <TableCell class="whitespace-nowrap text-right">
                    <div class="inline-flex items-center gap-2">
                      <Button
                        type="button"
                        size="icon-sm"
                        variant="outline"
                        :aria-label="`Edit prompt slot variant ${variant.name}`"
                        :title="`Edit prompt slot variant ${variant.name}`"
                        @click="emit('editVariant', variant)"
                      >
                        <Pencil class="size-4" />
                        <span class="sr-only">Edit variant</span>
                      </Button>
                      <Button
                        type="button"
                        size="icon-sm"
                        variant="outline"
                        :aria-label="`Delete prompt slot variant ${variant.name}`"
                        :title="`Delete prompt slot variant ${variant.name}`"
                        @click="emit('deleteVariant', variant)"
                      >
                        <Trash2 class="size-4" />
                        <span class="sr-only">Delete variant</span>
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>
              </template>
            </TableBody>
          </Table>
        </div>
      </CollapsibleContent>
    </Card>
  </Collapsible>
</template>
