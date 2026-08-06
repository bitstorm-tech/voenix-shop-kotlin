<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import ShopHeader from '@/components/shop/Header.vue'
import ShopFooter from '@/components/shop/Footer.vue'
import { useCartStore } from '@/stores/shop/cart'
import { useMagicCoinsStore } from '@/stores/shop/magicCoins'

const cartStore = useCartStore()
const magicCoinsStore = useMagicCoinsStore()
const route = useRoute()

const isFullBleed = computed(() => route.name === 'home')
const mainClass = computed(() => {
  if (isFullBleed.value) return 'flex-1 w-full'
  if (route.meta.wideContent) return 'flex-1 w-full'

  return 'flex-1 w-full px-4 pt-4 pb-0 md:max-w-7xl md:mx-auto md:px-8 md:pt-8 md:pb-0'
})
const shouldRenderFooter = computed(() => route.meta.hideFooter !== true)

onMounted(() => {
  cartStore.fetchCart()
  magicCoinsStore.fetchBalance()
})
</script>

<template>
  <div class="min-h-dvh flex flex-col overflow-x-clip">
    <ShopHeader />

    <main :class="mainClass">
      <RouterView />
    </main>

    <ShopFooter v-if="shouldRenderFooter" />
  </div>
</template>
