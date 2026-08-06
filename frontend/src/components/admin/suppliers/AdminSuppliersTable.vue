<script setup lang="ts">
import { computed } from 'vue'
import { Pencil } from 'lucide-vue-next'
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
import { type AdminSupplierDto, formatContactPerson } from '@/stores/admin/suppliers'

interface Props {
  suppliers: AdminSupplierDto[]
}

const props = defineProps<Props>()

const emit = defineEmits<{
  (event: 'edit', supplier: AdminSupplierDto): void
}>()

const rows = computed(() =>
  props.suppliers.map((supplier) => ({
    supplier,
    contactPerson: formatContactPerson(supplier),
  })),
)
</script>

<template>
  <Card class="overflow-hidden">
    <div class="overflow-x-auto">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Name</TableHead>
            <TableHead>Contact person</TableHead>
            <TableHead>City</TableHead>
            <TableHead>Country</TableHead>
            <TableHead>Email</TableHead>
            <TableHead class="text-right">Actions</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          <TableRow
            v-for="{ supplier, contactPerson } in rows"
            :key="supplier.id"
            class="cursor-pointer"
            tabindex="0"
            @click="emit('edit', supplier)"
            @keydown.enter="emit('edit', supplier)"
            @keydown.space.prevent="emit('edit', supplier)"
          >
            <TableCell class="min-w-40 text-foreground">{{ supplier.name }}</TableCell>
            <TableCell class="whitespace-nowrap text-muted-foreground">
              {{ contactPerson || '—' }}
            </TableCell>
            <TableCell class="whitespace-nowrap text-muted-foreground">
              {{ supplier.city || '—' }}
            </TableCell>
            <TableCell class="whitespace-nowrap text-muted-foreground">
              {{ supplier.country?.name ?? '—' }}
            </TableCell>
            <TableCell class="whitespace-nowrap text-muted-foreground">
              {{ supplier.email || '—' }}
            </TableCell>
            <TableCell class="whitespace-nowrap text-right">
              <Button
                variant="outline"
                size="icon-sm"
                :aria-label="`Edit supplier ${supplier.name}`"
                :title="`Edit supplier ${supplier.name}`"
                @click.stop="emit('edit', supplier)"
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
