<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { sortSizes } from '@/lib/tshirtSizes'

/**
 * The size chart of a SPOD article: the partner's picture, which shows what each letter measures,
 * above a table of the letters per size in centimetres. Without measurements there is no table.
 */
const props = defineProps<{
  imageUrl: string | null
  measurements: { size: string; measurements: { name: string; valueMm: number }[] }[]
  selectedSize: string | null
}>()

const { t, locale } = useI18n()

const rows = computed(() => {
  const bySize = new Map(props.measurements.map((entry) => [entry.size, entry.measurements]))
  return sortSizes([...bySize.keys()]).map((size) => ({
    size,
    values: new Map(bySize.get(size)!.map((item) => [item.name, item.valueMm])),
  }))
})

/** One column per measurement name, in the order it first appears down the sorted sizes. */
const columns = computed(() => [...new Set(rows.value.flatMap((row) => [...row.values.keys()]))])

const formatter = computed(() => new Intl.NumberFormat(locale.value, { maximumFractionDigits: 1 }))

function formatCm(valueMm: number | undefined): string {
  return valueMm === undefined ? '–' : formatter.value.format(valueMm / 10)
}
</script>

<template>
  <div class="grid gap-4">
    <img
      v-if="imageUrl"
      :src="imageUrl"
      :alt="t('sizeChart.image')"
      class="mx-auto max-h-[45vh] w-auto object-contain"
      data-testid="size-chart-image"
    />
    <Table v-if="rows.length > 0" data-testid="size-chart-table">
      <TableHeader>
        <TableRow>
          <TableHead scope="col" class="px-2 py-1.5">{{ t('sizeChart.size') }}</TableHead>
          <TableHead
            v-for="column in columns"
            :key="column"
            scope="col"
            class="px-2 py-1.5 text-right"
          >
            {{ column }}
          </TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        <TableRow
          v-for="row in rows"
          :key="row.size"
          :class="{ 'bg-muted font-semibold hover:bg-muted': row.size === selectedSize }"
          :aria-current="row.size === selectedSize ? 'true' : undefined"
        >
          <TableCell class="px-2 py-1.5">{{ row.size }}</TableCell>
          <TableCell
            v-for="column in columns"
            :key="column"
            class="px-2 py-1.5 text-right tabular-nums"
          >
            {{ formatCm(row.values.get(column)) }}
          </TableCell>
        </TableRow>
      </TableBody>
    </Table>
  </div>
</template>
