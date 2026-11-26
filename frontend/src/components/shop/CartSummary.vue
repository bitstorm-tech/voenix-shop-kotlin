<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { ArrowRight, ShieldCheck, Truck } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import CartPromotionForm from '@/components/shop/CartPromotionForm.vue'
import { formatPrice } from '@/lib/formatPrice'
import type { AppliedPromotion, PromotionApplicationErrorCode } from '@/stores/shop/cart'

/**
 * The server-calculated merchandise totals of the current `CartView`. Shipping costs depend on the
 * destination country selected at checkout, so no flat shipping fee is assumed here.
 */
withDefaults(
  defineProps<{
    subtotal: number
    discountAmount: number
    total: number
    appliedPromotion: AppliedPromotion | null
    isPromotionLoading: boolean
    promotionErrorCode: PromotionApplicationErrorCode | null
    checkoutDisabled?: boolean
  }>(),
  {
    checkoutDisabled: false,
  },
)

const emit = defineEmits<{
  applyPromotion: [promotionCode: string]
  removePromotion: []
  checkout: []
}>()

const { t } = useI18n()
</script>

<template>
  <Card class="p-6 shadow-lg">
    <h2 class="font-heading text-lg font-bold">{{ t('cart.summaryTitle') }}</h2>

    <div class="mt-5">
      <CartPromotionForm
        :applied-promotion="appliedPromotion"
        :is-loading="isPromotionLoading"
        :error-code="promotionErrorCode"
        @apply="emit('applyPromotion', $event)"
        @remove="emit('removePromotion')"
      />
    </div>

    <dl class="mt-5 space-y-3 border-t border-border pt-5 text-sm">
      <div class="flex justify-between">
        <dt class="text-muted-foreground">{{ t('cart.subtotal') }}</dt>
        <dd class="font-medium tabular-nums">{{ formatPrice(subtotal) }}</dd>
      </div>
      <div v-if="discountAmount > 0" class="flex justify-between text-success">
        <dt>{{ t('cart.discount') }}</dt>
        <dd class="font-medium tabular-nums">-{{ formatPrice(discountAmount) }}</dd>
      </div>
      <div class="flex justify-between gap-4">
        <dt class="text-muted-foreground">{{ t('cart.shipping') }}</dt>
        <dd class="text-right text-muted-foreground" data-testid="cart-shipping-note">
          {{ t('cart.shippingByCountry') }}
        </dd>
      </div>
      <div class="flex justify-between border-t border-border pt-4 text-base font-bold">
        <dt>{{ t('cart.totalExclShipping') }}</dt>
        <dd class="tabular-nums">{{ formatPrice(total) }}</dd>
      </div>
    </dl>

    <p v-if="appliedPromotion" class="mt-2 text-xs text-muted-foreground">
      {{ t('cart.promotion.shippingDiscountHint') }}
    </p>

    <Button
      class="mt-6 w-full"
      variant="shop"
      size="shop"
      :disabled="checkoutDisabled"
      data-testid="cart-checkout"
      @click="emit('checkout')"
    >
      {{ t('cart.checkout') }}
      <ArrowRight class="size-4" />
    </Button>

    <Button as-child variant="ghost" class="mt-2 w-full text-muted-foreground">
      <RouterLink to="/products">{{ t('cart.continueShopping') }}</RouterLink>
    </Button>

    <div class="mt-6 space-y-2 border-t border-border pt-5 text-xs text-muted-foreground">
      <p class="flex items-start gap-2">
        <Truck class="mt-0.5 size-3.5 shrink-0" />
        <span>
          <i18n-t keypath="cart.shippingCountryHint" tag="span">
            <template #link>
              <RouterLink
                :to="{ name: 'shipping' }"
                class="underline hover:text-foreground"
                data-testid="cart-shipping-link"
              >
                {{ t('cart.shippingInfoLink') }}
              </RouterLink>
            </template>
          </i18n-t>
        </span>
      </p>
      <p class="flex items-center gap-2">
        <ShieldCheck class="size-3.5 shrink-0" />
        {{ t('cart.securePayment') }}
      </p>
    </div>
  </Card>
</template>
