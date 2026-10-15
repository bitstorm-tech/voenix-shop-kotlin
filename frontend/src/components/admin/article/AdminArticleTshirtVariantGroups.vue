<script setup lang="ts">
import { ChevronDown } from 'lucide-vue-next'
import { computed, ref } from 'vue'
import AdminExampleImageThumbnail from '@/components/admin/shared/AdminExampleImageThumbnail.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { cn } from '@/lib/utils'
import { variantExampleImageUrl } from '@/lib/variantExampleImage'
import type { AdminArticleTshirtVariantDto } from '@/stores/admin/tshirtArticles'

/**
 * The synced variants of one t-shirt, read-only (t-shirt sync decision 2), one row per colour.
 *
 * A shirt has a handful of colours and each colour a handful of sizes, so a flat list of variants
 * is thirty near-identical rows. Grouped by colour it is five rows, each showing its sizes as chips:
 * a greyed-out, struck-through chip is a size the partner no longer offers, the highlighted one is
 * the default variant. Every variant stays visible — an operator sees at a glance which sizes of a
 * colour the shop can sell.
 *
 * The partner's identifiers (variant id, SKU, the product/appearance/size triple) are what an
 * operator needs to find the same row again in the Spreadconnect backoffice, and nothing else. They
 * sit behind one toggle and then show for every colour, active or not, in the same columns.
 */
const props = defineProps<{
  variants: readonly AdminArticleTshirtVariantDto[]
}>()

interface ColourGroup {
  key: string
  colorName: string
  colorHex: string
  /** The example image of the group: the default variant's, else the first one that has any. */
  exampleImageFilename: string | null
  variants: AdminArticleTshirtVariantDto[]
  activeCount: number
}

const showTechnicalIds = ref(false)

/**
 * Garment sizes in the order a size chart lists them. The sync stores the partner's labels, and
 * the partner sends them in no useful order, so the chips are sorted here; a label not in this
 * list goes after the known ones, in the order it arrived.
 */
const SIZE_ORDER = ['XXS', 'XS', 'S', 'M', 'L', 'XL', 'XXL', '2XL', '3XL', '4XL', '5XL', '6XL']

function sizeRank(label: string) {
  const rank = SIZE_ORDER.indexOf(label.trim().toUpperCase())
  return rank === -1 ? SIZE_ORDER.length : rank
}

function bySize(a: AdminArticleTshirtVariantDto, b: AdminArticleTshirtVariantDto) {
  return sizeRank(a.sizeLabel) - sizeRank(b.sizeLabel)
}

const groups = computed<ColourGroup[]>(() => {
  const byColour = new Map<string, ColourGroup>()

  for (const variant of props.variants) {
    const key = `${variant.colorHex}|${variant.colorName}`
    let group = byColour.get(key)
    if (!group) {
      group = {
        key,
        colorName: variant.colorName,
        colorHex: variant.colorHex,
        exampleImageFilename: null,
        variants: [],
        activeCount: 0,
      }
      byColour.set(key, group)
    }

    group.variants.push(variant)
    if (variant.active) {
      group.activeCount += 1
    }
    if (
      variant.exampleImageFilename !== null &&
      (group.exampleImageFilename === null || variant.isDefault)
    ) {
      group.exampleImageFilename = variant.exampleImageFilename
    }
  }

  for (const group of byColour.values()) {
    // A stable sort: two unknown labels keep the order they arrived in.
    group.variants.sort(bySize)
  }

  return [...byColour.values()]
})

function variantImageUrl(filename: string, size: number) {
  return variantExampleImageUrl('TSHIRT', filename, size)
}

function sizeChipTitle(variant: AdminArticleTshirtVariantDto) {
  const parts = [variant.name]
  if (variant.isDefault) {
    parts.push('default variant')
  }
  if (!variant.active) {
    parts.push('inactive')
  }
  return parts.join(' — ')
}
</script>

<template>
  <div class="space-y-3">
    <div class="flex flex-wrap items-center justify-between gap-3">
      <p class="text-xs text-muted-foreground">
        A struck-through size is one the partner no longer offers. The highlighted size is the
        default variant.
      </p>
      <Button
        type="button"
        variant="outline"
        size="sm"
        :aria-expanded="showTechnicalIds"
        data-testid="technical-ids"
        @click="showTechnicalIds = !showTechnicalIds"
      >
        <ChevronDown :class="cn('size-4 transition-transform', showTechnicalIds && 'rotate-180')" />
        {{ showTechnicalIds ? 'Hide' : 'Show' }} technical IDs
      </Button>
    </div>

    <ul class="divide-y divide-border rounded-lg border border-border bg-background">
      <li
        v-for="group in groups"
        :key="group.key"
        :class="cn('space-y-3 p-3', group.activeCount === 0 && 'bg-muted/20')"
        :data-testid="`spod-colour-${group.colorName}`"
      >
        <div class="flex flex-wrap items-center gap-3">
          <AdminExampleImageThumbnail
            :filename="group.exampleImageFilename"
            :title="group.colorName"
            :image-url="variantImageUrl"
          />
          <span class="flex w-44 shrink-0 items-center gap-2 font-medium text-foreground">
            <span
              class="size-4 shrink-0 rounded-full border border-border"
              :style="{ backgroundColor: group.colorHex }"
              :title="group.colorHex"
            />
            {{ group.colorName }}
          </span>

          <ul class="flex flex-wrap items-center gap-1.5" aria-label="Sizes">
            <li v-for="variant in group.variants" :key="variant.id">
              <span
                :class="
                  cn(
                    'inline-flex min-w-9 justify-center rounded-md border px-2 py-0.5 text-xs',
                    variant.active
                      ? 'border-border text-foreground'
                      : 'border-dashed border-border text-muted-foreground line-through',
                    variant.isDefault && 'border-primary bg-primary/10 font-semibold text-primary',
                  )
                "
                :title="sizeChipTitle(variant)"
                :data-testid="`spod-size-${variant.id}`"
              >
                {{ variant.sizeLabel }}
              </span>
            </li>
          </ul>

          <span class="ml-auto text-xs text-muted-foreground">
            <Badge v-if="group.activeCount === 0" variant="muted">Inactive</Badge>
            <template v-else
              >{{ group.activeCount }} of {{ group.variants.length }} active</template
            >
          </span>
        </div>

        <Table v-if="showTechnicalIds" class="min-w-[40rem]">
          <TableHeader>
            <TableRow>
              <TableHead class="w-16">Size</TableHead>
              <TableHead>Variant ID</TableHead>
              <TableHead>SKU</TableHead>
              <TableHead>Product / appearance / size ID</TableHead>
              <TableHead>Status</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            <TableRow
              v-for="variant in group.variants"
              :key="variant.id"
              :data-testid="`spod-variant-${variant.id}`"
            >
              <TableCell class="whitespace-nowrap">{{ variant.sizeLabel }}</TableCell>
              <TableCell class="whitespace-nowrap text-muted-foreground">
                {{ variant.spodVariantId }}
              </TableCell>
              <TableCell class="whitespace-nowrap text-muted-foreground">
                {{ variant.sku ?? '—' }}
              </TableCell>
              <TableCell class="whitespace-nowrap text-muted-foreground">
                {{ variant.spodProductTypeId }} / {{ variant.spodAppearanceId }} /
                {{ variant.spodSizeId }}
              </TableCell>
              <TableCell class="whitespace-nowrap">
                <Badge :variant="variant.active ? 'success' : 'muted'">
                  {{ variant.active ? 'Active' : 'Inactive' }}
                </Badge>
              </TableCell>
            </TableRow>
          </TableBody>
        </Table>
      </li>
    </ul>
  </div>
</template>
