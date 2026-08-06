<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import AdminProductionPdfList from '@/components/admin/orders/AdminProductionPdfList.vue'
import AdminProductionPdfLookupForm from '@/components/admin/orders/AdminProductionPdfLookupForm.vue'
import AdminPageHeader from '@/components/admin/shared/AdminPageHeader.vue'
import { Alert } from '@/components/ui/alert'
import { Card } from '@/components/ui/card'
import { useToast } from '@/composables/useToast'
import { saveBlobAs } from '@/lib/download'
import {
  OrderNotFoundError,
  ProductionPdfDataError,
  ProductionPdfRenderError,
  useAdminOrdersStore,
} from '@/stores/admin/orders'

const ordersStore = useAdminOrdersStore()
const { t } = useI18n()
const { toast } = useToast()

const orderIdInput = ref('')

/**
 * The three repairable `409` codes get their own sentence; everything else is worded as a fault of
 * the server or the request, so an admin can tell "fix the order" from "not your data".
 */
function errorMessage(error: Error): string {
  if (error instanceof OrderNotFoundError) {
    return t('admin.orders.errors.notFound')
  }

  if (error instanceof ProductionPdfDataError) {
    return t(`admin.orders.errors.${error.code}`)
  }

  if (error instanceof ProductionPdfRenderError) {
    return t('admin.orders.errors.renderFailure')
  }

  return error.message || t('admin.orders.errors.unknown')
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

async function lookupOrder(orderId: number) {
  await ordersStore.fetchProductionPdfs(orderId)
}

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
      title: t('admin.orders.errors.downloadFailed'),
      description: error instanceof Error ? errorMessage(error) : undefined,
      variant: 'destructive',
    })
  }
}
</script>

<template>
  <section class="space-y-4">
    <AdminPageHeader :title="t('admin.orders.title')" breakpoint="lg" />

    <p class="max-w-2xl text-sm text-muted-foreground">
      {{ t('admin.orders.description') }}
    </p>

    <AdminProductionPdfLookupForm
      v-model="orderIdInput"
      :is-loading="ordersStore.isLoading"
      @submit="lookupOrder"
    />

    <Alert v-if="lookupError" variant="destructive">{{ lookupError }}</Alert>

    <Alert v-else-if="lookupDataError" variant="info">
      {{ lookupDataError }} {{ t('admin.orders.errors.repairHint') }}
    </Alert>

    <Card
      v-else-if="ordersStore.isLoading"
      class="px-4 py-12 text-center text-sm text-muted-foreground"
    >
      {{ t('admin.orders.loading') }}
    </Card>

    <Card
      v-else-if="loadedOrderId !== null && ordersStore.documents.length === 0"
      class="px-4 py-12 text-center text-sm text-muted-foreground"
    >
      {{ t('admin.orders.empty') }}
    </Card>

    <AdminProductionPdfList
      v-else-if="loadedOrderId !== null"
      :order-id="loadedOrderId"
      :documents="ordersStore.documents"
      :downloading-supplier-id="ordersStore.downloadingSupplierId"
      @download="downloadDocument"
    />
  </section>
</template>
