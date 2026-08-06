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
import type { AdminVatDto } from '@/stores/admin/vat'

interface Props {
  vats: AdminVatDto[]
}

defineProps<Props>()

const emit = defineEmits<{
  (event: 'edit', vat: AdminVatDto): void
}>()
</script>

<template>
  <Card class="overflow-hidden">
    <div class="overflow-x-auto">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Name</TableHead>
            <TableHead>Percent</TableHead>
            <TableHead>Description</TableHead>
            <TableHead>Default</TableHead>
            <TableHead class="text-right">Actions</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          <TableRow v-for="vat in vats" :key="vat.id">
            <TableCell class="min-w-40 text-foreground">{{ vat.name }}</TableCell>
            <TableCell class="whitespace-nowrap text-foreground">{{ vat.percent }}%</TableCell>
            <TableCell
              class="max-w-xs truncate text-muted-foreground"
              :title="vat.description ?? ''"
            >
              {{ vat.description || '—' }}
            </TableCell>
            <TableCell class="whitespace-nowrap">
              <Badge v-if="vat.isDefault" variant="success">Default</Badge>
              <span v-else class="text-muted-foreground">—</span>
            </TableCell>
            <TableCell class="whitespace-nowrap text-right">
              <Button
                variant="outline"
                size="icon-sm"
                :aria-label="`Edit VAT ${vat.name}`"
                :title="`Edit VAT ${vat.name}`"
                @click="emit('edit', vat)"
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
