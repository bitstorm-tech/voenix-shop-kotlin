<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import ShopHeader from '@/components/shop/Header.vue'
import ShopFooter from '@/components/shop/Footer.vue'
import { useCartStore } from '@/stores/shop/cart'

const { t } = useI18n()
const cartStore = useCartStore()
const route = useRoute()

const isFullBleed = computed(() => route.name === 'home')
const mainClass = computed(() => {
  if (isFullBleed.value) return 'flex-1 w-full'
  if (route.meta.wideContent) return 'flex-1 w-full'

  return 'flex-1 w-full px-4 pt-4 pb-0 md:max-w-7xl md:mx-auto md:px-8 md:pt-8 md:pb-0'
})
const shouldRenderFooter = computed(() => route.meta.hideFooter !== true)
const legalBarRoutes = ['imprint', 'privacy', 'terms'] as const

onMounted(() => {
  cartStore.fetchCart()
})
</script>

<template>
  <div class="min-h-dvh flex flex-col overflow-x-clip">
    <ShopHeader />

    <main :class="mainClass">
      <RouterView />
    </main>

    <ShopFooter v-if="shouldRenderFooter" />
    <!-- Legal links must stay reachable on footerless pages; the mobile bottom padding clears their fixed bottom controls. -->
    <nav
      v-else
      data-testid="legal-bar"
      class="flex flex-wrap justify-center gap-x-2 px-4 pt-3 pb-[calc(7rem+env(safe-area-inset-bottom,0px))] text-xs text-muted-foreground md:pb-3"
    >
      <template v-for="(name, index) in legalBarRoutes" :key="name">
        <span v-if="index > 0" aria-hidden="true">·</span>
        <RouterLink :to="{ name }" class="hover:text-foreground hover:underline">
          {{ t(`pageTitles.${name}`) }}
        </RouterLink>
      </template>
    </nav>
  </div>
</template>
