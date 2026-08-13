<script setup lang="ts">
import { computed, onMounted, shallowRef } from 'vue'
import { useI18n } from 'vue-i18n'
import { ChevronDown, Loader2, Package } from 'lucide-vue-next'
import { RouterLink, useRouter } from 'vue-router'
import OrderDetails from '@/components/shop/orders/OrderDetails.vue'
import OrderPaymentStatusBadge from '@/components/shop/orders/OrderPaymentStatusBadge.vue'
import OrderStatusBadge from '@/components/shop/orders/OrderStatusBadge.vue'
import { Alert } from '@/components/ui/alert'
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
import { useOrdersStore, type Order, type OrderItem } from '@/stores/shop/orders'
import { isOrderImageUnavailable, useCartStore } from '@/stores/shop/cart'
import { PrintImageGoneError, usePrintImagesStore } from '@/stores/shop/printImages'
import { useEditorStore } from '@/stores/shop/editor'
import { useToast } from '@/composables/useToast'

const router = useRouter()
const { t, locale } = useI18n()
const ordersStore = useOrdersStore()
const cartStore = useCartStore()
const printImagesStore = usePrintImagesStore()
const editorStore = useEditorStore()
const { toast } = useToast()

const hasOrders = computed(() => ordersStore.orders.length > 0)
const addingItemId = shallowRef<number | null>(null)
/** The ordered line whose print image is gone, while the fresh-upload offer for it is shown. */
const freshUploadItem = shallowRef<OrderItem | null>(null)
const expandedOrderIds = shallowRef<Set<number>>(new Set())

onMounted(() => {
  ordersStore.fetchOrders()
})

function formatDate(value: string) {
  return new Date(value).toLocaleDateString(locale.value === 'en' ? 'en-US' : 'de-DE', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  })
}

function shouldShowPaymentBadge(order: Order) {
  if (!order.paymentStatus) {
    return false
  }

  if (
    order.paymentStatus === 'OPEN' ||
    order.paymentStatus === 'PENDING' ||
    order.paymentStatus === 'FAILED' ||
    order.paymentStatus === 'EXPIRED' ||
    order.paymentStatus === 'CANCELED'
  ) {
    return true
  }

  if (order.paymentStatus === 'AUTHORIZED') {
    return order.status !== 'PAID'
  }

  return false
}

function formatShippingCost(shippingCost: number) {
  return shippingCost === 0 ? t('orders.shippingFree') : formatPrice(shippingCost)
}

function getItemSummary(order: Order) {
  return order.items
    .map((item) => `${item.articleName} (${item.variantName}) x${item.quantity}`)
    .join(', ')
}

function isOrderExpanded(orderId: number) {
  return expandedOrderIds.value.has(orderId)
}

function getOrderToggleLabel(orderId: number) {
  return isOrderExpanded(orderId) ? t('orders.hideDetails') : t('orders.showDetails')
}

function toggleOrderDetails(orderId: number) {
  const nextExpandedOrderIds = new Set(expandedOrderIds.value)

  if (nextExpandedOrderIds.has(orderId)) {
    nextExpandedOrderIds.delete(orderId)
  } else {
    nextExpandedOrderIds.add(orderId)
  }

  expandedOrderIds.value = nextExpandedOrderIds
}

function getReorderErrorMessage(error: unknown) {
  if (isOrderImageUnavailable(error)) {
    return t('orders.reorderImageUnavailable')
  }

  return t('orders.reorderError')
}

/**
 * The print image of that ordered line cannot be printed any more, so a retry would fail the same
 * way. The offer is a fresh upload: an empty draft for the same article and variant, which the
 * editor opens on its upload step (`docs/migration/order-post-migration.md`).
 */
function startFreshUpload(item: OrderItem) {
  const draft = editorStore.createDraftFromProduct({
    articleId: item.articleId,
    variantId: item.variantId,
  })
  freshUploadItem.value = null

  return router.push({ name: 'editor', params: { draftId: draft.id } })
}

function dismissFreshUploadOffer() {
  freshUploadItem.value = null
}

/** The print image is gone, which no retry can repair. The store owns what the status meant. */
function getRedesignErrorMessage(error: unknown) {
  if (error instanceof PrintImageGoneError) {
    return t('orders.redesignImageUnavailable')
  }

  return t('orders.redesignError')
}

async function reorderItem(item: OrderItem) {
  if (!item.imageId) {
    return
  }

  addingItemId.value = item.orderItemId
  freshUploadItem.value = null

  try {
    await cartStore.reorderOrderItem(item.orderItemId)
    toast({ title: t('orders.reorderSuccess'), variant: 'success' })
  } catch (error) {
    if (isOrderImageUnavailable(error)) {
      freshUploadItem.value = item
    }

    toast({
      title: getReorderErrorMessage(error),
      variant: 'destructive',
    })
  } finally {
    addingItemId.value = null
  }
}

async function redesignItem(item: OrderItem) {
  if (!item.imageId) {
    return
  }

  addingItemId.value = item.orderItemId

  try {
    const imageBlob = await printImagesStore.fetchPrintImageBlob(item.imageId, 1600)
    const draft = editorStore.createDraftFromOrderRedesign({
      articleId: item.articleId,
      variantId: item.variantId,
      imageBlob,
    })

    await router.push({ name: 'editor', params: { draftId: draft.id } })
  } catch (error) {
    toast({
      title: getRedesignErrorMessage(error),
      variant: 'destructive',
    })
  } finally {
    addingItemId.value = null
  }
}
</script>

<template>
  <div class="space-y-6 pb-12">
    <div class="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
      <div>
        <h1 class="font-heading text-2xl font-bold tracking-tight sm:text-3xl">
          {{ t('orders.title') }}
        </h1>
        <p class="mt-2 max-w-2xl text-sm text-muted-foreground">
          {{ t('orders.subtitle') }}
        </p>
      </div>

      <Button as-child variant="outline">
        <RouterLink to="/profile">{{ t('orders.profileAction') }}</RouterLink>
      </Button>
    </div>

    <Alert v-if="freshUploadItem" variant="destructive" data-testid="order-fresh-upload-offer">
      <p class="m-0 text-sm">{{ t('orders.reorderImageUnavailable') }}</p>
      <div class="mt-3 flex flex-wrap gap-2">
        <Button size="sm" @click="startFreshUpload(freshUploadItem)">
          {{ t('orders.reorderFreshUpload') }}
        </Button>
        <Button size="sm" variant="outline" @click="dismissFreshUploadOffer">
          {{ t('orders.reorderDismiss') }}
        </Button>
      </div>
    </Alert>

    <div v-if="ordersStore.isLoading" class="flex justify-center py-20">
      <Loader2 class="size-8 animate-spin text-muted-foreground" />
    </div>

    <div v-else-if="ordersStore.error" class="space-y-4">
      <Alert variant="destructive">
        <p class="m-0 text-sm">{{ ordersStore.error }}</p>
      </Alert>
      <Button class="mt-4" variant="outline" @click="ordersStore.fetchOrders()">
        {{ t('orders.retry') }}
      </Button>
    </div>

    <Card v-else-if="!hasOrders" class="border-dashed bg-muted/30 px-6 py-16 text-center">
      <div
        class="mx-auto flex size-14 items-center justify-center rounded-full bg-background shadow-sm"
      >
        <Package class="size-6 text-muted-foreground" />
      </div>
      <h2 class="mt-5 text-xl font-semibold">{{ t('orders.emptyTitle') }}</h2>
      <p class="mx-auto mt-2 max-w-md text-sm text-muted-foreground">
        {{ t('orders.emptyDescription') }}
      </p>

      <Button as-child class="mt-6">
        <RouterLink to="/mugs">{{ t('orders.emptyAction') }}</RouterLink>
      </Button>
    </Card>

    <template v-else>
      <div class="space-y-4 lg:hidden">
        <Card
          as="article"
          v-for="order in ordersStore.orders"
          :key="order.orderId"
          class="bg-card p-5 shadow-sm"
        >
          <div
            role="button"
            tabindex="0"
            class="cursor-pointer rounded-lg outline-none transition-colors focus-visible:ring-2 focus-visible:ring-ring"
            :aria-expanded="isOrderExpanded(order.orderId)"
            :aria-label="getOrderToggleLabel(order.orderId)"
            @click="toggleOrderDetails(order.orderId)"
            @keydown.enter.prevent="toggleOrderDetails(order.orderId)"
            @keydown.space.prevent="toggleOrderDetails(order.orderId)"
          >
            <div class="flex items-start justify-between gap-4">
              <div>
                <p class="m-0 text-xs uppercase tracking-[0.18em] text-muted-foreground">
                  {{ t('orders.orderNumber') }}
                </p>
                <p class="mt-1 flex items-center gap-2 text-lg font-semibold">
                  <ChevronDown
                    class="size-4 shrink-0 transition-transform"
                    :class="isOrderExpanded(order.orderId) ? 'rotate-180' : '-rotate-90'"
                  />
                  <span>#{{ order.orderId }}</span>
                </p>
              </div>

              <div class="text-right">
                <p class="m-0 text-xs uppercase tracking-[0.18em] text-muted-foreground">
                  {{ t('orders.total') }}
                </p>
                <p class="mt-1 font-semibold tabular-nums">
                  {{ formatPrice(order.total) }}
                </p>
              </div>
            </div>

            <div class="mt-4 flex flex-wrap gap-2">
              <OrderStatusBadge :status="order.status" />
              <OrderPaymentStatusBadge
                v-if="shouldShowPaymentBadge(order)"
                :status="order.paymentStatus"
              />
            </div>

            <dl class="mt-4 grid grid-cols-2 gap-3 text-sm">
              <div>
                <dt class="text-muted-foreground">{{ t('orders.orderDate') }}</dt>
                <dd class="mt-1 font-medium">{{ formatDate(order.createdAt) }}</dd>
              </div>
              <div>
                <dt class="text-muted-foreground">{{ t('orders.shipping') }}</dt>
                <dd class="mt-1 font-medium">
                  {{ formatShippingCost(order.shippingCost) }}
                </dd>
              </div>
            </dl>
          </div>

          <div v-if="isOrderExpanded(order.orderId)" class="mt-4 border-t border-border pt-4">
            <p class="m-0 mb-3 text-sm font-medium">{{ t('orders.details') }}</p>
            <OrderDetails
              :order="order"
              :adding-item-id="addingItemId"
              @reorder-item="reorderItem"
              @redesign-item="redesignItem"
            />
          </div>
        </Card>
      </div>

      <Card class="hidden overflow-hidden bg-card shadow-sm lg:block">
        <Table>
          <TableHeader>
            <TableRow class="hover:bg-transparent">
              <TableHead class="px-4">{{ t('orders.orderNumber') }}</TableHead>
              <TableHead>{{ t('orders.orderDate') }}</TableHead>
              <TableHead>{{ t('orders.items') }}</TableHead>
              <TableHead>{{ t('orders.statusLabel') }}</TableHead>
              <TableHead>{{ t('orders.paymentLabel') }}</TableHead>
              <TableHead class="px-4 text-right">{{ t('orders.total') }}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            <template v-for="order in ordersStore.orders" :key="order.orderId">
              <TableRow
                role="button"
                tabindex="0"
                class="cursor-pointer outline-none focus-visible:bg-muted/40 focus-visible:ring-2 focus-visible:ring-ring"
                :aria-expanded="isOrderExpanded(order.orderId)"
                :aria-label="getOrderToggleLabel(order.orderId)"
                @click="toggleOrderDetails(order.orderId)"
                @keydown.enter.prevent="toggleOrderDetails(order.orderId)"
                @keydown.space.prevent="toggleOrderDetails(order.orderId)"
              >
                <TableCell class="px-4 align-top font-medium">
                  <span class="inline-flex items-center gap-2">
                    <ChevronDown
                      class="size-4 shrink-0 text-muted-foreground transition-transform"
                      :class="isOrderExpanded(order.orderId) ? 'rotate-180' : '-rotate-90'"
                    />
                    <span>#{{ order.orderId }}</span>
                  </span>
                </TableCell>
                <TableCell class="align-top text-muted-foreground">
                  {{ formatDate(order.createdAt) }}
                </TableCell>
                <TableCell class="max-w-[28rem] align-top">
                  <p class="m-0 whitespace-normal break-words">{{ getItemSummary(order) }}</p>
                  <p class="mt-1 text-xs text-muted-foreground">
                    {{
                      t('orders.shippingSummary', {
                        shipping: formatShippingCost(order.shippingCost),
                      })
                    }}
                  </p>
                </TableCell>
                <TableCell class="align-top">
                  <OrderStatusBadge :status="order.status" />
                </TableCell>
                <TableCell class="align-top">
                  <OrderPaymentStatusBadge
                    v-if="shouldShowPaymentBadge(order)"
                    :status="order.paymentStatus"
                  />
                </TableCell>
                <TableCell class="px-4 align-top text-right font-semibold tabular-nums">
                  {{ formatPrice(order.total) }}
                </TableCell>
              </TableRow>
              <TableRow v-if="isOrderExpanded(order.orderId)" class="hover:bg-transparent">
                <TableCell colspan="6" class="px-4 pb-6 pt-4">
                  <OrderDetails
                    :order="order"
                    :adding-item-id="addingItemId"
                    @reorder-item="reorderItem"
                    @redesign-item="redesignItem"
                  />
                </TableCell>
              </TableRow>
            </template>
          </TableBody>
        </Table>
      </Card>
    </template>
  </div>
</template>
