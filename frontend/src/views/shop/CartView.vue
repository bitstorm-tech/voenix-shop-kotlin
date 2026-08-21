<script setup lang="ts">
import { onMounted } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { Trash2, ShoppingBag, ArrowRight, Loader2 } from 'lucide-vue-next'
import { useCartStore } from '@/stores/shop/cart'
import { useCatalogStore } from '@/stores/shop/catalog'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import CartLineItem from '@/components/shop/CartLineItem.vue'
import CartSummary from '@/components/shop/CartSummary.vue'

const { t } = useI18n()
const router = useRouter()
const cartStore = useCartStore()
const catalogStore = useCatalogStore()

onMounted(() => {
  cartStore.fetchCart()
  // The line items pair each print motif with its variant's catalog photo, which the mugs store answers.
  catalogStore.fetchArticles()
})

function goToCheckout() {
  router.push({ name: 'checkout' })
}

/** A refused mutation leaves the shown cart possibly stale, so the recovery is a fresh read. */
function reloadCart() {
  cartStore.clearMutationError()
  cartStore.fetchCart()
}
</script>

<template>
  <div class="space-y-6 pb-12">
    <!-- Loading state -->
    <div v-if="cartStore.isLoading && cartStore.isEmpty" class="flex justify-center py-20">
      <Loader2 class="size-8 animate-spin text-muted-foreground" />
    </div>

    <!-- Cart with items -->
    <template v-else-if="!cartStore.isEmpty">
      <!-- Header -->
      <div class="flex items-end justify-between gap-4">
        <div>
          <h1 class="font-heading text-2xl font-bold tracking-tight sm:text-3xl">
            {{ t('cart.title') }}
          </h1>
          <p class="mt-1 text-sm text-muted-foreground">
            {{ t('cart.itemCount', cartStore.totalItems) }}
          </p>
        </div>
        <Button variant="destructive" size="sm" @click="cartStore.clearCart()">
          <Trash2 class="size-4" />
          <span class="hidden sm:inline">{{ t('cart.clearCart') }}</span>
        </Button>
      </div>

      <!-- A refused quantity change or removal must not stay invisible -->
      <Alert v-if="cartStore.mutationError" variant="destructive" data-testid="cart-mutation-error">
        <p class="m-0 text-sm">{{ t('cart.mutationFailed') }}</p>
        <p class="m-0 mt-1 text-xs opacity-80">{{ cartStore.mutationError }}</p>
        <Button class="mt-3" variant="outline" size="sm" @click="reloadCart">
          {{ t('cart.reload') }}
        </Button>
      </Alert>

      <Alert
        v-if="cartStore.hasUnavailableItem"
        variant="destructive"
        data-testid="cart-unavailable-hint"
      >
        <p class="m-0 text-sm">{{ t('cart.unavailableHint') }}</p>
      </Alert>

      <!-- Main layout: item cards + summary -->
      <div class="grid grid-cols-1 gap-8 lg:grid-cols-3">
        <div class="lg:col-span-2">
          <ul class="space-y-4">
            <CartLineItem
              v-for="item in cartStore.items"
              :key="item.id"
              :item="item"
              @update-quantity="cartStore.updateQuantity(item.id, $event)"
              @remove="cartStore.removeItem(item.id)"
            />
          </ul>
        </div>

        <div class="lg:col-span-1">
          <CartSummary
            class="lg:sticky lg:top-24"
            :subtotal="cartStore.subtotal"
            :shipping-cost="cartStore.shippingCost"
            :discount-amount="cartStore.discountAmount"
            :total="cartStore.totalPrice"
            :applied-promotion="cartStore.appliedPromotion"
            :is-promotion-loading="cartStore.isPromotionLoading"
            :promotion-error-code="cartStore.promotionErrorCode"
            @apply-promotion="cartStore.applyPromotion"
            @remove-promotion="cartStore.removePromotion"
            @checkout="goToCheckout"
          />
        </div>
      </div>
    </template>

    <!-- Empty cart state -->
    <Card v-else class="flex flex-col items-center justify-center border-dashed py-20 text-center">
      <div class="flex size-16 items-center justify-center rounded-full bg-muted">
        <ShoppingBag class="size-7 text-muted-foreground" />
      </div>
      <h2 class="mt-4 font-heading text-xl font-semibold">{{ t('cart.empty') }}</h2>
      <p class="mt-1.5 max-w-sm text-sm text-muted-foreground">
        {{ t('cart.emptyDescription') }}
      </p>
      <Button as-child class="mt-6" size="lg">
        <RouterLink to="/products">
          {{ t('cart.emptyAction') }}
          <ArrowRight class="size-4" />
        </RouterLink>
      </Button>
    </Card>
  </div>
</template>
