<script setup lang="ts">
import { computed } from 'vue'
import { Download } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { productionPdfDownloadName, type ProductionPdfInfo } from '@/stores/admin/orders'

interface Props {
  orderId: number
  documents: ProductionPdfInfo[]
  /** The supplier whose download is running, so only its own button shows the busy state. */
  downloadingSupplierId: number | null
}

const props = defineProps<Props>()
const emit = defineEmits<{
  download: [supplierId: number]
}>()

/** The vue-i18n plural this replaced had one sentence per branch; both are kept verbatim. */
const summary = computed(() => {
  const count = props.documents.length

  return count === 1
    ? `${count} production document, one per supplier.`
    : `${count} production documents, one per supplier.`
})
</script>

<template>
  <div class="space-y-3">
    <p class="text-sm text-muted-foreground">
      {{ summary }}
    </p>

    <Card
      v-for="document in props.documents"
      :key="document.supplierId"
      class="flex flex-col gap-3 p-4 sm:flex-row sm:items-center sm:justify-between"
    >
      <div class="space-y-1">
        <p class="text-sm font-semibold text-foreground">Supplier {{ document.supplierId }}</p>
        <p class="text-sm text-muted-foreground">Producer file name: {{ document.fileName }}</p>
        <p class="text-xs text-muted-foreground">
          Saved as {{ productionPdfDownloadName(props.orderId, document.supplierId) }}, because the
          producer file name repeats across suppliers.
        </p>
      </div>

      <Button
        variant="outline"
        size="sm"
        :disabled="props.downloadingSupplierId !== null"
        @click="emit('download', document.supplierId)"
      >
        <Download class="size-4" />
        {{ props.downloadingSupplierId === document.supplierId ? 'Downloading...' : 'Download' }}
      </Button>
    </Card>
  </div>
</template>
