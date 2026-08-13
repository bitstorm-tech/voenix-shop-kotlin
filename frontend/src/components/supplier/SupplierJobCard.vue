<script setup lang="ts">
import { computed } from 'vue'
import { Download, Truck } from 'lucide-vue-next'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { orderNumber, SHIPPING_CARRIER_LABELS, type ShippingCarrier } from '@/lib/fulfillment'
import type { SupplierJob } from '@/stores/supplier/jobs'

interface Props {
  job: SupplierJob
  downloading?: boolean
  shipping?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  downloading: false,
  shipping: false,
})

const emit = defineEmits<{
  (event: 'download'): void
  (event: 'ship'): void
}>()

const isShipped = computed(() => props.job.shippedAt !== null)
const customerName = computed(() =>
  `${props.job.customerFirstName} ${props.job.customerLastName}`.trim(),
)
const carrierLabel = computed(() => {
  const carrier = props.job.shippingCarrier
  if (carrier === null) {
    return null
  }

  return SHIPPING_CARRIER_LABELS[carrier as ShippingCarrier] ?? carrier
})

/** The Berlin order date the document prints, shown as it arrives (`yyyy-MM-dd`). */
const shippedDate = computed(() => props.job.shippedAt?.slice(0, 10) ?? null)
</script>

<template>
  <Card class="space-y-4 p-4">
    <div class="flex flex-wrap items-start justify-between gap-2">
      <div>
        <p class="text-base font-semibold text-foreground">{{ orderNumber(job.orderId) }}</p>
        <p class="text-sm text-muted-foreground">Ordered on {{ job.orderDate }}</p>
      </div>
      <Badge v-if="isShipped" variant="success">Shipped</Badge>
      <Badge v-else-if="!job.pdfAvailable" variant="warning">PDF in preparation</Badge>
      <Badge v-else variant="muted">Open</Badge>
    </div>

    <div class="text-sm text-foreground">
      <p class="font-medium">{{ customerName }}</p>
      <p class="text-muted-foreground">{{ job.shippingStreet }} {{ job.shippingHouseNumber }}</p>
      <p class="text-muted-foreground">
        {{ job.shippingPostalCode }} {{ job.shippingCity }}, {{ job.shippingCountry }}
      </p>
    </div>

    <ul v-if="job.items.length > 0" class="space-y-1 text-sm">
      <li v-for="(item, index) in job.items" :key="index" class="flex justify-between gap-4">
        <span>
          {{ item.articleName }} — {{ item.variantName }}
          <span v-if="item.supplierArticleNumber" class="text-muted-foreground">
            ({{ item.supplierArticleNumber }})
          </span>
        </span>
        <span class="shrink-0 tabular-nums text-muted-foreground">×{{ item.quantity }}</span>
      </li>
    </ul>
    <p v-else class="text-sm text-muted-foreground">
      The item list appears as soon as the production document has been generated.
    </p>

    <p v-if="isShipped" class="text-sm text-muted-foreground">
      Shipped on {{ shippedDate }}<template v-if="carrierLabel"> via {{ carrierLabel }}</template
      ><template v-if="job.trackingNumber">, tracking {{ job.trackingNumber }}</template
      >.
    </p>

    <div class="flex flex-wrap items-center gap-2">
      <Button
        variant="outline"
        size="sm"
        :disabled="!job.pdfAvailable || downloading"
        @click="emit('download')"
      >
        <Download class="size-4" />
        {{ downloading ? 'Downloading...' : 'Download PDF' }}
      </Button>

      <Button
        v-if="!isShipped"
        size="sm"
        :disabled="!job.pdfAvailable || shipping"
        @click="emit('ship')"
      >
        <Truck class="size-4" />
        Mark as shipped
      </Button>

      <p v-if="!job.pdfAvailable" class="text-sm text-muted-foreground">
        The production document has not been generated yet. This job can be printed and shipped once
        it is available.
      </p>
    </div>
  </Card>
</template>
