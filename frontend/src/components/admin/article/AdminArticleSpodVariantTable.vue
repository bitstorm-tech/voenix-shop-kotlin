<script setup lang="ts">
import AdminExampleImageThumbnail from '@/components/admin/shared/AdminExampleImageThumbnail.vue'
import { Badge } from '@/components/ui/badge'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { variantExampleImageUrl } from '@/lib/variantExampleImage'
import type { AdminSpodAppearanceDto, AdminSpodVariantDto } from '@/stores/admin/spodArticles'

/**
 * The synced variants of one SPOD article, read-only. The colour of a row and its thumbnail — the
 * first listing image of that colour — come from the appearance the variant points to.
 *
 * The editor renders it twice — once for the active variants and once for the inactive ones behind
 * their toggle — and both lists show the same columns on purpose: an operator comparing this screen
 * with the Spreadconnect backoffice needs the partner's own names for a row whether the shop offers
 * it or not.
 */
const props = defineProps<{
  variants: readonly AdminSpodVariantDto[]
  appearances: readonly AdminSpodAppearanceDto[]
}>()

function appearanceOf(variant: AdminSpodVariantDto) {
  return props.appearances.find((appearance) => appearance.id === variant.appearanceId)
}

function variantImageUrl(filename: string, size: number) {
  return variantExampleImageUrl('SPOD', filename, size)
}
</script>

<template>
  <Table class="min-w-[48rem]">
    <TableHeader>
      <TableRow>
        <TableHead class="w-14">Image</TableHead>
        <TableHead>Colour</TableHead>
        <TableHead>Size</TableHead>
        <TableHead>Variant ID</TableHead>
        <TableHead>SKU</TableHead>
        <TableHead>Product / appearance / size ID</TableHead>
        <TableHead>Status</TableHead>
        <TableHead>Default</TableHead>
      </TableRow>
    </TableHeader>
    <TableBody>
      <TableRow
        v-for="variant in variants"
        :key="variant.id"
        :data-testid="`spod-variant-${variant.id}`"
      >
        <TableCell>
          <AdminExampleImageThumbnail
            :filename="appearanceOf(variant)?.listingImageFilenames[0] ?? null"
            :title="variant.name"
            :image-url="variantImageUrl"
          />
        </TableCell>
        <TableCell class="whitespace-nowrap">
          <span class="flex items-center gap-2">
            <span
              class="size-4 shrink-0 rounded-full border border-border"
              :style="{ backgroundColor: appearanceOf(variant)?.colorHex }"
              :title="appearanceOf(variant)?.colorHex"
            />
            {{ appearanceOf(variant)?.colorName }}
          </span>
        </TableCell>
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
        <TableCell class="whitespace-nowrap">
          <Badge v-if="variant.isDefault">Default</Badge>
        </TableCell>
      </TableRow>
    </TableBody>
  </Table>
</template>
