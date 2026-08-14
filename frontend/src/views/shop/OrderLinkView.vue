<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, shallowRef } from 'vue'
import { useI18n } from 'vue-i18n'
import { Loader2 } from 'lucide-vue-next'
import { RouterLink, useRoute } from 'vue-router'
import OrderDetails from '@/components/shop/orders/OrderDetails.vue'
import OrderStatusBadge from '@/components/shop/orders/OrderStatusBadge.vue'
import { orderDisplayStatus } from '@/components/shop/orders/orderDisplayStatus'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { formatPrice } from '@/lib/formatPrice'
import { ApiError } from '@/lib/api'
import { useOrdersStore, type Order } from '@/stores/shop/orders'

const { t, locale } = useI18n()
const route = useRoute()
const ordersStore = useOrdersStore()

/**
 * What the one read produced. `invalid` is the backend's uniform `404`: unknown, malformed, and
 * foreign tokens are the same answer, so the page can only say "this link is not valid". `failed`
 * is everything else — a server or network fault, which says nothing about the link.
 */
type LinkState = 'loading' | 'loaded' | 'invalid' | 'failed'

const state = shallowRef<LinkState>('loading')
const order = shallowRef<Order | null>(null)

const token = computed(() => {
  const value = route.params.token
  return typeof value === 'string' ? value : ''
})

/**
 * The token sits in the path, so every request this page triggers and every link a visitor follows
 * would otherwise carry the whole URL along as its referrer. The page therefore declares
 * `no-referrer` for as long as it is on screen and takes the declaration back when it leaves — the
 * app has no head-management util, and a tag in `index.html` would change every other page too
 * (issue #110, Joe decision 1).
 */
let referrerMeta: HTMLMetaElement | null = null

function applyNoReferrerPolicy() {
  const meta = document.createElement('meta')
  meta.name = 'referrer'
  meta.content = 'no-referrer'
  document.head.appendChild(meta)
  referrerMeta = meta
}

function removeNoReferrerPolicy() {
  referrerMeta?.remove()
  referrerMeta = null
}

/**
 * Exactly one read, on mount. There is nothing to wait for here: the page is a permanent record of a
 * placed order, not the payment return page, so it neither polls nor retries — `useOrderStatusRefresh`
 * belongs to the confirmation page and is deliberately not used.
 */
async function loadOrder() {
  if (!token.value) {
    state.value = 'invalid'
    return
  }

  try {
    order.value = await ordersStore.fetchOrderByToken(token.value)
    state.value = 'loaded'
  } catch (error) {
    state.value = error instanceof ApiError && error.status === 404 ? 'invalid' : 'failed'
  }
}

onMounted(() => {
  applyNoReferrerPolicy()
  loadOrder()
})

onBeforeUnmount(removeNoReferrerPolicy)

function formatDate(value: string) {
  return new Date(value).toLocaleDateString(locale.value === 'en' ? 'en-US' : 'de-DE', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  })
}
</script>

<template>
  <div class="space-y-6 pb-12">
    <div v-if="state === 'loading'" class="flex justify-center py-20">
      <Loader2 class="size-8 animate-spin text-muted-foreground" />
    </div>

    <template v-else-if="state === 'loaded' && order">
      <div>
        <h1 class="font-heading text-2xl font-bold tracking-tight sm:text-3xl">
          {{ t('orders.link.title', { orderId: order.orderId }) }}
        </h1>
        <p class="mt-2 max-w-2xl text-sm text-muted-foreground">
          {{ t('orders.link.subtitle') }}
        </p>
      </div>

      <Card as="article" class="bg-card p-5 shadow-sm" data-testid="order-link-card">
        <div class="flex flex-wrap items-start justify-between gap-4">
          <div>
            <p class="m-0 text-xs uppercase tracking-[0.18em] text-muted-foreground">
              {{ t('orders.orderNumber') }}
            </p>
            <p class="mt-1 text-lg font-semibold">#{{ order.orderId }}</p>
          </div>

          <div class="text-right">
            <p class="m-0 text-xs uppercase tracking-[0.18em] text-muted-foreground">
              {{ t('orders.total') }}
            </p>
            <p class="mt-1 font-semibold tabular-nums">{{ formatPrice(order.total) }}</p>
          </div>
        </div>

        <div class="mt-4 flex flex-wrap gap-2">
          <OrderStatusBadge :status="order.status" :payment-status="order.paymentStatus" />
        </div>

        <dl class="mt-4 grid grid-cols-2 gap-3 text-sm">
          <div>
            <dt class="text-muted-foreground">{{ t('orders.orderDate') }}</dt>
            <dd class="mt-1 font-medium">{{ formatDate(order.createdAt) }}</dd>
          </div>
          <div>
            <dt class="text-muted-foreground">{{ t('orders.statusLabel') }}</dt>
            <dd class="mt-1 font-medium">
              {{
                t(`orders.displayStatus.${orderDisplayStatus(order.status, order.paymentStatus)}`)
              }}
            </dd>
          </div>
        </dl>

        <div class="mt-6 border-t border-border pt-4">
          <p class="m-0 mb-3 text-sm font-medium">{{ t('orders.details') }}</p>
          <OrderDetails :order="order" readonly />
        </div>
      </Card>
    </template>

    <Card
      v-else-if="state === 'invalid'"
      class="border-dashed bg-muted/30 px-6 py-16 text-center"
      data-testid="order-link-invalid"
    >
      <h1 class="font-heading text-xl font-semibold">{{ t('orders.link.invalidTitle') }}</h1>
      <p class="mx-auto mt-2 max-w-md text-sm text-muted-foreground">
        {{ t('orders.link.invalidDescription') }}
      </p>

      <Button as-child class="mt-6" variant="outline">
        <RouterLink to="/">{{ t('orders.link.homeAction') }}</RouterLink>
      </Button>
    </Card>

    <Alert v-else variant="destructive" data-testid="order-link-failed">
      <p class="m-0 text-sm">{{ t('orders.link.loadError') }}</p>
    </Alert>
  </div>
</template>
