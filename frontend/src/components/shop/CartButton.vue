<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { ShoppingCart } from 'lucide-vue-next'
import { useCartStore } from '@/stores/shop/cart'

const cartStore = useCartStore()
</script>

<template>
  <RouterLink
    to="/cart"
    class="relative inline-flex items-center justify-center p-2 text-foreground hover:text-muted-foreground transition-colors"
  >
    <ShoppingCart class="size-5" />
    <span
      v-if="cartStore.totalItems > 0"
      class="absolute -right-1 -top-1 flex size-5 items-center justify-center rounded-full bg-primary text-xs font-medium text-primary-foreground"
    >
      {{ cartStore.totalItems > 99 ? '99+' : cartStore.totalItems }}
    </span>
  </RouterLink>
</template>
