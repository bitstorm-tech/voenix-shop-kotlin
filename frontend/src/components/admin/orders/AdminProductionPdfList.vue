<script setup lang="ts">
import { Download } from 'lucide-vue-next'
import { useI18n } from 'vue-i18n'
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

const { t } = useI18n()
</script>

<template>
  <div class="space-y-3">
    <p class="text-sm text-muted-foreground">
      {{ t('admin.orders.documents.summary', props.documents.length) }}
    </p>

    <Card
      v-for="document in props.documents"
      :key="document.supplierId"
      class="flex flex-col gap-3 p-4 sm:flex-row sm:items-center sm:justify-between"
    >
      <div class="space-y-1">
        <p class="text-sm font-semibold text-foreground">
          {{ t('admin.orders.documents.supplier', { supplierId: document.supplierId }) }}
        </p>
        <p class="text-sm text-muted-foreground">
          {{ t('admin.orders.documents.serverName', { fileName: document.fileName }) }}
        </p>
        <p class="text-xs text-muted-foreground">
          {{
            t('admin.orders.documents.savedAs', {
              fileName: productionPdfDownloadName(props.orderId, document.supplierId),
            })
          }}
        </p>
      </div>

      <Button
        variant="outline"
        size="sm"
        :disabled="props.downloadingSupplierId !== null"
        @click="emit('download', document.supplierId)"
      >
        <Download class="size-4" />
        {{
          props.downloadingSupplierId === document.supplierId
            ? t('admin.orders.documents.downloading')
            : t('admin.orders.documents.download')
        }}
      </Button>
    </Card>
  </div>
</template>
