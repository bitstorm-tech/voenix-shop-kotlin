<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { ArrowRight } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import CartPromotionForm from '@/components/shop/CartPromotionForm.vue'
import { formatPrice } from '@/lib/formatPrice'
import type { AppliedPromotion, PromotionApplicationErrorCode } from '@/stores/shop/cart'

/** The server-calculated totals of the current `CartView`; nothing here is recomputed locally. */
defineProps<{
  subtotal: number
  shippingCost: number
  discountAmount: number
  total: number
  appliedPromotion: AppliedPromotion | null
  isPromotionLoading: boolean
  promotionErrorCode: PromotionApplicationErrorCode | null
}>()

const emit = defineEmits<{
  applyPromotion: [promotionCode: string]
  removePromotion: []
  checkout: []
}>()

const { t } = useI18n()
const FREE_SHIPPING_THRESHOLD_IN_CENTS = 5000
</script>

<template>
  <Card class="bg-muted/30 p-6">
    <h2 class="font-heading text-lg font-semibold">{{ t('cart.subtotal') }}</h2>

    <div class="mt-5 border-b border-border pb-5">
      <CartPromotionForm
        :applied-promotion="appliedPromotion"
        :is-loading="isPromotionLoading"
        :error-code="promotionErrorCode"
        @apply="emit('applyPromotion', $event)"
        @remove="emit('removePromotion')"
      />
    </div>

    <dl class="mt-4 space-y-3 text-sm">
      <div class="flex justify-between">
        <dt class="text-muted-foreground">{{ t('cart.subtotal') }}</dt>
        <dd class="font-medium tabular-nums">{{ formatPrice(subtotal) }}</dd>
      </div>
      <div class="flex justify-between">
        <dt class="text-muted-foreground">{{ t('cart.shipping') }}</dt>
        <dd class="font-medium tabular-nums">
          <span v-if="shippingCost === 0" class="text-success">
            {{ t('cart.shippingFree') }}
          </span>
          <span v-else>{{ formatPrice(shippingCost) }}</span>
        </dd>
      </div>
      <div v-if="shippingCost > 0" class="text-xs text-muted-foreground">
        {{ t('cart.shippingFreeHint', { amount: formatPrice(FREE_SHIPPING_THRESHOLD_IN_CENTS) }) }}
      </div>
      <div v-if="discountAmount > 0" class="flex justify-between text-success">
        <dt>{{ t('cart.discount') }}</dt>
        <dd class="font-medium tabular-nums">-{{ formatPrice(discountAmount) }}</dd>
      </div>
      <hr class="border-border" />
      <div class="flex justify-between text-base font-semibold">
        <dt>{{ t('cart.total') }}</dt>
        <dd class="tabular-nums">{{ formatPrice(total) }}</dd>
      </div>
    </dl>

    <Button class="mt-6 w-full" size="lg" data-testid="cart-checkout" @click="emit('checkout')">
      {{ t('cart.checkout') }}
      <ArrowRight class="size-4" />
    </Button>

    <Button as-child variant="ghost" class="mt-2 w-full text-muted-foreground">
      <RouterLink to="/mugs">{{ t('cart.continueShopping') }}</RouterLink>
    </Button>
  </Card>
</template>
