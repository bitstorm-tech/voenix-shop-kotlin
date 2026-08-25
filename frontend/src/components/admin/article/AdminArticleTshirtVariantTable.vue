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
import type { AdminArticleTshirtVariantDto } from '@/stores/admin/tshirtArticles'

/**
 * The synced variants of one t-shirt, read-only (ADR 0003).
 *
 * The editor renders it twice — once for the active variants and once for the inactive ones behind
 * their toggle — and both lists show the same columns on purpose: an operator comparing this screen
 * with the Spreadconnect backoffice needs the partner's own names for a row whether the shop offers
 * it or not.
 */
defineProps<{
  variants: readonly AdminArticleTshirtVariantDto[]
}>()

function variantImageUrl(filename: string, size: number) {
  return variantExampleImageUrl('TSHIRT', filename, size)
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
            :filename="variant.exampleImageFilename"
            :title="variant.name"
            :image-url="variantImageUrl"
          />
        </TableCell>
        <TableCell class="whitespace-nowrap">
          <span class="flex items-center gap-2">
            <span
              class="size-4 shrink-0 rounded-full border border-border"
              :style="{ backgroundColor: variant.colorHex }"
              :title="variant.colorHex"
            />
            {{ variant.colorName }}
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
      </TableRow>
    </TableBody>
  </Table>
</template>
