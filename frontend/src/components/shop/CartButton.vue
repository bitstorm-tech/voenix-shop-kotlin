<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { ShoppingCart } from 'lucide-vue-next'
import { useCartStore } from '@/stores/shop/cart'

const { t } = useI18n()
const cartStore = useCartStore()
</script>

<template>
  <RouterLink
    to="/cart"
    :aria-label="t('cart.title')"
    class="relative inline-flex items-center justify-center p-2 text-foreground hover:text-muted-foreground transition-colors"
  >
    <ShoppingCart class="size-5" aria-hidden="true" />
    <span
      v-if="cartStore.totalItems > 0"
      class="absolute -right-1 -top-1 flex size-5 items-center justify-center rounded-full bg-primary text-xs font-medium text-primary-foreground"
    >
      {{ cartStore.totalItems > 99 ? '99+' : cartStore.totalItems }}
    </span>
  </RouterLink>
</template>
