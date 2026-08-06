<script setup lang="ts">
import { computed, shallowRef } from 'vue'
import { useRoute, RouterLink } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { CheckCircle, Clock, CircleX, Loader2, RefreshCw } from 'lucide-vue-next'
import { useCheckoutStore } from '@/stores/shop/checkout'
import { useOrderStatusRefresh } from '@/composables/useOrderStatusRefresh'
import { CheckoutError, checkoutErrorKeys } from '@/lib/checkoutErrors'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'

const { t } = useI18n()
const route = useRoute()
const checkoutStore = useCheckoutStore()

/** Mollie sends the customer back here with the order they paid for as a query parameter. */
const orderId = computed(() => {
  const id = route.query.orderId
  const parsed = id === undefined ? Number.NaN : Number(id)
  return Number.isInteger(parsed) ? parsed : null
})

/**
 * Without an order number the page has nothing to ask about: no request is ever sent, so no status
 * arrives and no refresh can change that. It is its own dead end and says so, instead of showing the
 * "waiting for payment" card for order `#` with two buttons that can never do anything.
 */
const hasNoOrderId = computed(() => orderId.value === null)

const {
  paymentStatus,
  total,
  isLoading,
  isRefreshing,
  hasFailed,
  isPaid,
  isCancelled,
  isWaiting,
  hasStoppedWaiting,
  refreshNow,
} = useOrderStatusRefresh(orderId)

const paymentError = shallowRef<string | null>(null)

const confirmationTitle = computed(() => {
  if (isCancelled.value) {
    return t('checkout.confirmation.titleCancelled')
  }
  if (!isPaid.value) {
    return t('checkout.confirmation.titlePending')
  }

  // A free order is confirmed without ever having had a payment.
  return t(
    paymentStatus.value === 'PAID'
      ? 'checkout.confirmation.titlePaid'
      : 'checkout.confirmation.titleConfirmed',
  )
})

const confirmationDescription = computed(() => {
  if (isCancelled.value) {
    return t('checkout.confirmation.descriptionCancelled')
  }
  if (!isPaid.value) {
    return t('checkout.confirmation.descriptionPending')
  }

  return t(
    paymentStatus.value === 'PAID'
      ? 'checkout.confirmation.descriptionPaid'
      : 'checkout.confirmation.descriptionConfirmed',
  )
})

/** The retry route exists exactly for an order that is placed but not paid and not cancelled. */
const canRetryPayment = computed(
  () => !isLoading.value && !hasFailed.value && !isPaid.value && !isCancelled.value,
)

function formatPrice(priceInCents: number): string {
  return (priceInCents / 100).toLocaleString('de-DE', {
    style: 'currency',
    currency: 'EUR',
  })
}

async function retryPayment() {
  const id = orderId.value
  if (id === null) {
    return
  }

  paymentError.value = null
  try {
    const result = await checkoutStore.startPayment(id)
    if (result.checkoutUrl) {
      window.location.href = result.checkoutUrl
      return
    }

    // No URL means there is nothing to pay for this order; the fresh status says why.
    await refreshNow()
  } catch (error) {
    paymentError.value = retryErrorMessage(error)
    // `ORDER_ALREADY_PAID` means the confirmation this page waited for arrived elsewhere.
    if (error instanceof CheckoutError && error.code === 'ORDER_ALREADY_PAID') {
      await refreshNow()
    }
  }
}

function retryErrorMessage(error: unknown): string {
  if (error instanceof CheckoutError && error.code) {
    return t(checkoutErrorKeys[error.code])
  }

  return t('checkout.confirmation.retryPaymentFailed')
}
</script>

<template>
  <div class="flex min-h-[50vh] items-center justify-center pb-12">
    <div class="w-full max-w-md">
      <!-- No order number in the link: nothing to load, nothing to retry -->
      <Alert
        v-if="hasNoOrderId"
        variant="destructive"
        class="p-8 text-center"
        data-testid="order-confirmation-missing-id"
      >
        <p class="text-sm text-destructive">{{ t('checkout.confirmation.missingOrderId') }}</p>
        <Button as-child variant="outline" class="mt-4 w-full">
          <RouterLink to="/orders">{{ t('checkout.confirmation.goToOrders') }}</RouterLink>
        </Button>
      </Alert>

      <!-- Loading -->
      <div v-else-if="isLoading" class="flex justify-center py-20">
        <Loader2 class="size-8 animate-spin text-muted-foreground" />
      </div>

      <!-- Error -->
      <Alert v-else-if="hasFailed" variant="destructive" class="p-8 text-center">
        <p class="text-sm text-destructive">{{ t('checkout.confirmation.error') }}</p>
        <Button
          variant="outline"
          class="mt-4 w-full"
          :disabled="isRefreshing"
          data-testid="order-status-refresh"
          @click="refreshNow"
        >
          <RefreshCw class="size-4" :class="{ 'animate-spin': isRefreshing }" />
          {{ t('checkout.confirmation.refresh') }}
        </Button>
      </Alert>

      <!-- Status -->
      <Card v-else class="bg-muted/30 p-8 text-center">
        <!-- Icon -->
        <div class="flex justify-center">
          <div
            :class="[
              'flex size-16 items-center justify-center rounded-full',
              isPaid ? 'bg-success-surface' : 'bg-warning-surface',
            ]"
          >
            <CheckCircle v-if="isPaid" class="text-success size-8" />
            <CircleX v-else-if="isCancelled" class="text-warning size-8" />
            <Clock v-else class="text-warning size-8" />
          </div>
        </div>

        <!-- Title -->
        <h1 class="mt-4 font-heading text-xl font-semibold">
          {{ confirmationTitle }}
        </h1>

        <!-- Description -->
        <p class="mt-2 text-sm text-muted-foreground">
          {{ confirmationDescription }}
        </p>

        <!-- Order details -->
        <dl class="mt-6 space-y-2 text-sm">
          <div class="flex justify-between">
            <dt class="text-muted-foreground">{{ t('checkout.confirmation.orderId') }}</dt>
            <dd class="font-medium">#{{ orderId }}</dd>
          </div>
          <div class="flex justify-between">
            <dt class="text-muted-foreground">{{ t('cart.total') }}</dt>
            <dd class="font-medium tabular-nums">{{ formatPrice(total) }}</dd>
          </div>
        </dl>

        <!-- Waiting for the payment confirmation, on a bounded ladder -->
        <div
          v-if="isWaiting"
          class="mt-6 flex items-center justify-center gap-2 text-xs text-muted-foreground"
          data-testid="order-status-waiting"
        >
          <Loader2 class="size-3 animate-spin" />
          {{ t('checkout.confirmation.waiting') }}
        </div>
        <p
          v-else-if="hasStoppedWaiting"
          class="mt-6 text-xs text-muted-foreground"
          data-testid="order-status-waiting-stopped"
        >
          {{ t('checkout.confirmation.waitingStopped') }}
        </p>

        <p
          v-if="paymentError"
          class="mt-4 text-sm text-destructive"
          data-testid="retry-payment-error"
        >
          {{ paymentError }}
        </p>

        <!-- Asking again, and asking for another payment -->
        <div v-if="canRetryPayment" class="mt-6 space-y-2">
          <Button
            variant="outline"
            class="w-full"
            :disabled="isRefreshing"
            data-testid="order-status-refresh"
            @click="refreshNow"
          >
            <RefreshCw class="size-4" :class="{ 'animate-spin': isRefreshing }" />
            {{ t('checkout.confirmation.refresh') }}
          </Button>
          <Button
            class="w-full"
            :disabled="checkoutStore.isSubmitting"
            data-testid="retry-payment"
            @click="retryPayment"
          >
            <Loader2 v-if="checkoutStore.isSubmitting" class="size-4 animate-spin" />
            {{ t('checkout.confirmation.retryPayment') }}
          </Button>
        </div>

        <!-- Back to shop -->
        <Button as-child variant="ghost" class="mt-6 w-full" size="lg">
          <RouterLink to="/mugs">{{ t('checkout.confirmation.continueShopping') }}</RouterLink>
        </Button>
      </Card>
    </div>
  </div>
</template>
