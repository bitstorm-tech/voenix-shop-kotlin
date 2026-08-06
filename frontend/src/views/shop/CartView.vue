<script setup lang="ts">
import { onMounted } from 'vue'
import { RouterLink } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { Minus, Plus, Trash2, ShoppingBag, ArrowRight, Loader2 } from 'lucide-vue-next'
import { useCartStore } from '@/stores/shop/cart'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import CartItemPreviewDialog from '@/components/shop/CartItemPreviewDialog.vue'
import CartPromotionForm from '@/components/shop/CartPromotionForm.vue'

const { t } = useI18n()
const cartStore = useCartStore()

onMounted(() => {
  cartStore.fetchCart()
})
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
        <Button variant="destructive" size="sm" @click="cartStore.clearCart">
          <Trash2 class="size-4" />
          <span class="hidden sm:inline">{{ t('cart.clearCart') }}</span>
        </Button>
      </div>

      <!-- Main layout: items + summary -->
      <div class="grid grid-cols-1 gap-8 lg:grid-cols-3">
        <!-- Item list -->
        <div class="lg:col-span-2">
          <ul class="divide-y divide-border">
            <li
              v-for="item in cartStore.items"
              :key="item.id"
              class="flex gap-4 py-6 first:pt-0 last:pb-0"
            >
              <!-- Image preview or color circle -->
              <div
                class="flex size-20 shrink-0 items-center justify-center overflow-hidden rounded-xl bg-muted/50 sm:size-24"
              >
                <img
                  v-if="item.generatedEditedImageId"
                  :src="'/api/images/guest/200/' + item.generatedEditedImageId"
                  :alt="item.articleName"
                  class="size-full object-cover"
                />
                <div
                  v-else
                  class="size-12 rounded-full shadow-inner sm:size-16"
                  :style="{
                    backgroundColor: item.outsideColorCode,
                    boxShadow: `inset 0 -16px 24px -8px ${item.insideColorCode}`,
                  }"
                />
              </div>

              <!-- Item details -->
              <div class="flex min-w-0 flex-1 flex-col justify-between">
                <div>
                  <h3 class="font-medium leading-tight">{{ item.articleName }}</h3>
                  <p class="mt-0.5 text-sm text-muted-foreground">
                    {{ t('cart.variant') }}: {{ item.variantName }}
                  </p>
                  <div v-if="item.generatedEditedImageId" class="mt-3">
                    <CartItemPreviewDialog :item="item" />
                  </div>
                </div>

                <div class="mt-3 flex items-center justify-between gap-4">
                  <!-- Quantity controls -->
                  <div class="flex items-center gap-1">
                    <Button
                      variant="outline"
                      size="icon-sm"
                      :disabled="item.quantity <= 1"
                      @click="cartStore.updateQuantity(item.id, item.quantity - 1)"
                    >
                      <Minus class="size-3.5" />
                    </Button>
                    <span class="w-9 text-center text-sm font-medium tabular-nums">
                      {{ item.quantity }}
                    </span>
                    <Button
                      variant="outline"
                      size="icon-sm"
                      :disabled="item.quantity >= 99"
                      @click="cartStore.updateQuantity(item.id, item.quantity + 1)"
                    >
                      <Plus class="size-3.5" />
                    </Button>
                  </div>

                  <!-- Price + Remove -->
                  <div class="flex items-center gap-3">
                    <span class="text-sm font-semibold tabular-nums">
                      {{ cartStore.formatPrice((item.price + item.promptPrice) * item.quantity) }}
                    </span>
                    <Button
                      variant="destructive"
                      size="icon-sm"
                      :aria-label="t('cart.removeItem', { item: item.articleName })"
                      @click="cartStore.removeItem(item.id)"
                    >
                      <Trash2 class="size-4" />
                    </Button>
                  </div>
                </div>
              </div>
            </li>
          </ul>
        </div>

        <!-- Order summary -->
        <div class="lg:col-span-1">
          <Card class="bg-muted/30 p-6">
            <h2 class="font-heading text-lg font-semibold">{{ t('cart.subtotal') }}</h2>

            <div class="mt-5 border-b border-border pb-5">
              <CartPromotionForm
                :applied-promotion="cartStore.appliedPromotion"
                :is-loading="cartStore.isPromotionLoading"
                :error-code="cartStore.promotionErrorCode"
                @apply="cartStore.applyPromotion"
                @remove="cartStore.removePromotion"
              />
            </div>

            <dl class="mt-4 space-y-3 text-sm">
              <div class="flex justify-between">
                <dt class="text-muted-foreground">{{ t('cart.subtotal') }}</dt>
                <dd class="font-medium tabular-nums">
                  {{ cartStore.formatPrice(cartStore.subtotal) }}
                </dd>
              </div>
              <div class="flex justify-between">
                <dt class="text-muted-foreground">{{ t('cart.shipping') }}</dt>
                <dd class="font-medium tabular-nums">
                  <template v-if="cartStore.shippingCost === 0">
                    <span class="text-success">
                      {{ t('cart.shippingFree') }}
                    </span>
                  </template>
                  <template v-else>
                    {{ cartStore.formatPrice(cartStore.shippingCost) }}
                  </template>
                </dd>
              </div>
              <div v-if="cartStore.shippingCost > 0" class="text-xs text-muted-foreground">
                {{ t('cart.shippingFreeHint', { amount: cartStore.formatPrice(5000) }) }}
              </div>
              <div v-if="cartStore.discountAmount > 0" class="flex justify-between text-success">
                <dt>{{ t('cart.discount') }}</dt>
                <dd class="font-medium tabular-nums">
                  -{{ cartStore.formatPrice(cartStore.discountAmount) }}
                </dd>
              </div>
              <hr class="border-border" />
              <div class="flex justify-between text-base font-semibold">
                <dt>{{ t('cart.total') }}</dt>
                <dd class="tabular-nums">{{ cartStore.formatPrice(cartStore.totalPrice) }}</dd>
              </div>
            </dl>

            <Button class="mt-6 w-full" size="lg" @click="$router.push({ name: 'checkout' })">
              {{ t('cart.checkout') }}
              <ArrowRight class="size-4" />
            </Button>

            <Button as-child variant="ghost" class="mt-2 w-full text-muted-foreground">
              <RouterLink to="/mugs">{{ t('cart.continueShopping') }}</RouterLink>
            </Button>
          </Card>
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
        <RouterLink to="/mugs">
          {{ t('cart.emptyAction') }}
          <ArrowRight class="size-4" />
        </RouterLink>
      </Button>
    </Card>
  </div>
</template>
