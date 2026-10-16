<script setup lang="ts">
import { computed } from 'vue'
import AdminProductionPdfList from '@/components/admin/orders/AdminProductionPdfList.vue'
import { Alert } from '@/components/ui/alert'
import { Card } from '@/components/ui/card'
import { useToast } from '@/composables/useToast'
import { saveBlobAs } from '@/lib/download'
import {
  OrderNotFoundError,
  ProductionPdfDataError,
  ProductionPdfRenderError,
  type ProductionPdfDataErrorCode,
  useAdminOrdersStore,
} from '@/stores/admin/orders'

/**
 * The production documents the store last looked up, with their failure states and the download.
 * The orders page shows it under its order-id lookup, the order timeline for its own order.
 */
const ordersStore = useAdminOrdersStore()
const { toast } = useToast()

/**
 * The three repairable `409` codes get their own sentence; everything else is worded as a fault of
 * the server or the request, so an admin can tell "fix the order" from "not your data".
 */
const productionPdfDataErrorMessages: Record<ProductionPdfDataErrorCode, string> = {
  PRODUCTION_PDF_MISSING_IMAGE: 'An ordered item has no usable production image.',
  PRODUCTION_PDF_UNREADABLE_IMAGE: "An ordered item's production image cannot be read.",
  PRODUCTION_PDF_INVALID_SOURCE:
    'The order carries production data no document can be laid out from.',
}

function errorMessage(error: Error): string {
  if (error instanceof OrderNotFoundError) {
    return 'No order exists for this ID.'
  }

  if (error instanceof ProductionPdfDataError) {
    return productionPdfDataErrorMessages[error.code]
  }

  if (error instanceof ProductionPdfRenderError) {
    return 'The production document could not be rendered. This is a server fault; the details are in the server log.'
  }

  return error.message || 'The production documents could not be loaded.'
}

/** A repairable `409` is shown as an order-data hint, not as a failure of the lookup. */
const lookupDataError = computed(() =>
  ordersStore.error instanceof ProductionPdfDataError ? errorMessage(ordersStore.error) : null,
)

const lookupError = computed(() =>
  ordersStore.error !== null && lookupDataError.value === null
    ? errorMessage(ordersStore.error)
    : null,
)

const loadedOrderId = computed(() => ordersStore.loadedOrderId)

async function downloadDocument(supplierId: number) {
  const orderId = ordersStore.loadedOrderId
  if (orderId === null) {
    return
  }

  try {
    const { blob, fileName } = await ordersStore.downloadProductionPdf(orderId, supplierId)
    saveBlobAs(blob, fileName)
  } catch (error) {
    toast({
      title: 'Failed to download the production document',
      description: error instanceof Error ? errorMessage(error) : undefined,
      variant: 'destructive',
    })
  }
}
</script>

<template>
  <Alert v-if="lookupError" variant="destructive">{{ lookupError }}</Alert>

  <Alert v-else-if="lookupDataError" variant="info">
    {{ lookupDataError }} Repair the order data and run the lookup again.
  </Alert>

  <Card
    v-else-if="ordersStore.isLoading"
    class="px-4 py-12 text-center text-sm text-muted-foreground"
  >
    Loading production documents...
  </Card>

  <Card
    v-else-if="loadedOrderId !== null && ordersStore.documents.length === 0"
    class="px-4 py-12 text-center text-sm text-muted-foreground"
  >
    This order has no production documents.
  </Card>

  <AdminProductionPdfList
    v-else-if="loadedOrderId !== null"
    :order-id="loadedOrderId"
    :documents="ordersStore.documents"
    :downloading-supplier-id="ordersStore.downloadingSupplierId"
    @download="downloadDocument"
  />
</template>
