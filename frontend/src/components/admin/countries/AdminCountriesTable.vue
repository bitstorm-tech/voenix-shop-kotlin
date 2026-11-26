<script setup lang="ts">
import { Pencil } from 'lucide-vue-next'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { formatPrice } from '@/lib/formatPrice'
import type { AdminCountryDto } from '@/stores/admin/countries'

interface Props {
  countries: AdminCountryDto[]
}

defineProps<Props>()

const emit = defineEmits<{
  (event: 'edit', country: AdminCountryDto): void
}>()
</script>

<template>
  <Card class="overflow-hidden">
    <div class="overflow-x-auto">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Name</TableHead>
            <TableHead>Code</TableHead>
            <TableHead>Shipping cost</TableHead>
            <TableHead>Free shipping from</TableHead>
            <TableHead class="text-right">Actions</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          <TableRow v-for="country in countries" :key="country.id">
            <TableCell class="min-w-40 text-foreground">{{ country.name }}</TableCell>
            <TableCell class="whitespace-nowrap font-mono text-foreground">
              {{ country.countryCode }}
            </TableCell>
            <TableCell class="whitespace-nowrap tabular-nums text-foreground">
              <Badge v-if="country.shippingCostCents === 0" variant="success">Free</Badge>
              <span v-else>{{ formatPrice(country.shippingCostCents) }}</span>
            </TableCell>
            <TableCell class="whitespace-nowrap tabular-nums text-foreground">
              <span v-if="country.freeShippingThresholdCents !== null">
                {{ formatPrice(country.freeShippingThresholdCents) }}
              </span>
              <span v-else class="text-muted-foreground">—</span>
            </TableCell>
            <TableCell class="whitespace-nowrap text-right">
              <Button
                variant="outline"
                size="icon-sm"
                :aria-label="`Edit country ${country.name}`"
                :title="`Edit country ${country.name}`"
                @click="emit('edit', country)"
              >
                <Pencil class="size-4" />
                <span class="sr-only">Edit</span>
              </Button>
            </TableCell>
          </TableRow>
        </TableBody>
      </Table>
    </div>
  </Card>
</template>
