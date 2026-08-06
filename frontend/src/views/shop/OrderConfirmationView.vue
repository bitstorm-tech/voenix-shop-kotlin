<script setup lang="ts">
import { shallowRef, onMounted, onUnmounted, computed } from 'vue'
import { useRoute, RouterLink } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { CheckCircle, Clock, Loader2 } from 'lucide-vue-next'
import { useCheckoutStore } from '@/stores/shop/checkout'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'

const { t } = useI18n()
const route = useRoute()
const checkoutStore = useCheckoutStore()

const status = shallowRef<string | null>(null)
const paymentStatus = shallowRef<string | null>(null)
const totalAmount = shallowRef(0)
const isLoading = shallowRef(true)
const error = shallowRef<string | null>(null)

let pollInterval: ReturnType<typeof setInterval> | null = null

const orderId = computed(() => {
  const id = route.query.orderId
  return id ? Number(id) : null
})

const hasSuccessfulPayment = computed(() => paymentStatus.value === 'paid')
const isConfirmed = computed(() => status.value === 'paid' || hasSuccessfulPayment.value)
const confirmationTitle = computed(() => {
  if (!isConfirmed.value) {
    return t('checkout.confirmation.titlePending')
  }

  return t(
    hasSuccessfulPayment.value
      ? 'checkout.confirmation.titlePaid'
      : 'checkout.confirmation.titleConfirmed',
  )
})
const confirmationDescription = computed(() => {
  if (!isConfirmed.value) {
    return t('checkout.confirmation.descriptionPending')
  }

  return t(
    hasSuccessfulPayment.value
      ? 'checkout.confirmation.descriptionPaid'
      : 'checkout.confirmation.descriptionConfirmed',
  )
})

function formatPrice(priceInCents: number): string {
  return (priceInCents / 100).toLocaleString('de-DE', {
    style: 'currency',
    currency: 'EUR',
  })
}

async function fetchStatus() {
  if (!orderId.value) return

  try {
    const data = await checkoutStore.fetchOrderStatus(orderId.value)
    status.value = data.status
    paymentStatus.value = data.paymentStatus
    totalAmount.value = data.totalAmountInCents

    if (isConfirmed.value) {
      stopPolling()
    }
  } catch {
    error.value = t('checkout.confirmation.error')
    stopPolling()
  } finally {
    isLoading.value = false
  }
}

function startPolling() {
  pollInterval = setInterval(fetchStatus, 3000)
}

function stopPolling() {
  if (pollInterval) {
    clearInterval(pollInterval)
    pollInterval = null
  }
}

onMounted(() => {
  fetchStatus()
  startPolling()
})

onUnmounted(() => {
  stopPolling()
})
</script>

<template>
  <div class="flex min-h-[50vh] items-center justify-center pb-12">
    <div class="w-full max-w-md">
      <!-- Loading -->
      <div v-if="isLoading" class="flex justify-center py-20">
        <Loader2 class="size-8 animate-spin text-muted-foreground" />
      </div>

      <!-- Error -->
      <Alert v-else-if="error" variant="destructive" class="p-8 text-center">
        <p class="text-sm text-destructive">{{ error }}</p>
      </Alert>

      <!-- Status -->
      <Card v-else class="bg-muted/30 p-8 text-center">
        <!-- Icon -->
        <div class="flex justify-center">
          <div
            :class="[
              'flex size-16 items-center justify-center rounded-full',
              isConfirmed ? 'bg-success-surface' : 'bg-warning-surface',
            ]"
          >
            <CheckCircle v-if="isConfirmed" class="text-success size-8" />
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
            <dd class="font-medium tabular-nums">{{ formatPrice(totalAmount) }}</dd>
          </div>
        </dl>

        <!-- Polling indicator -->
        <div
          v-if="!isConfirmed"
          class="mt-6 flex items-center justify-center gap-2 text-xs text-muted-foreground"
        >
          <Loader2 class="size-3 animate-spin" />
          {{ t('checkout.confirmation.waiting') }}
        </div>

        <!-- Back to shop -->
        <Button as-child class="mt-6 w-full" size="lg">
          <RouterLink to="/mugs">{{ t('checkout.confirmation.continueShopping') }}</RouterLink>
        </Button>
      </Card>
    </div>
  </div>
</template>
