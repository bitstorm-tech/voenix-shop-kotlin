<script setup lang="ts">
import VoenixLogo from '@/assets/images/voenix-logo-text.svg'
import { MAGIC_COINS_ROUTE } from '@/lib/magicCoins'
import { useAuthStore } from '@/stores/shared/auth'
import { useArticleCategoriesStore } from '@/stores/shop/articleCategories'
import { useMagicCoinsStore } from '@/stores/shop/magicCoins'
import { LogIn, Shield } from 'lucide-vue-next'
import {
  NavigationMenu,
  NavigationMenuContent,
  NavigationMenuIndicator,
  NavigationMenuItem,
  NavigationMenuList,
  NavigationMenuTrigger,
  NavigationMenuViewport,
} from '@/components/ui/navigation-menu'
import { computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, useRoute } from 'vue-router'
import CartButton from './CartButton.vue'
import HeaderCategoryMenuPanel from './HeaderCategoryMenuPanel.vue'
import LanguageDropdown from './LanguageDropdown.vue'
import MagicCoinsBadge from './MagicCoinsBadge.vue'
import MobileMenu from './MobileMenu.vue'
import ThemeToggle from './ThemeToggle.vue'
import UserMenu from './UserMenu.vue'

const { t } = useI18n()
const authStore = useAuthStore()
const magicCoinsStore = useMagicCoinsStore()
// MobileMenu reads from this store but does not fetch — Header owns the fetch.
const categoriesStore = useArticleCategoriesStore()
const route = useRoute()
const MEGA_MENU_DELAY_MS = 10

onMounted(() => {
  categoriesStore.fetchCategories()
})

const menuCategories = computed(() => categoriesStore.categories)

function isCategoryActive(categoryId: number) {
  const category = Array.isArray(route.query.category)
    ? route.query.category[0]
    : route.query.category

  return category === categoryId.toString()
}
</script>

<template>
  <header class="border-b border-border bg-background-soft">
    <NavigationMenu
      :delay-duration="MEGA_MENU_DELAY_MS"
      class="relative z-40 block max-w-none flex-none items-stretch justify-start"
    >
      <div class="flex h-14 items-stretch px-4 md:h-16 md:px-8">
        <RouterLink class="inline-flex h-full shrink-0 items-center" to="/">
          <img alt="Voenix.Shop" class="block h-9 w-auto md:h-10" :src="VoenixLogo" />
        </RouterLink>

        <div class="hidden h-full min-w-0 flex-1 justify-center overflow-visible md:flex">
          <NavigationMenuList v-if="menuCategories.length > 0" class="flex h-full list-none">
            <NavigationMenuItem
              v-for="category in menuCategories"
              :key="category.id"
              class="flex h-full"
            >
              <NavigationMenuTrigger
                variant="plain"
                class="h-full gap-2 px-4 select-none"
                :class="{ 'text-primary': isCategoryActive(category.id) }"
              >
                {{ category.name }}
              </NavigationMenuTrigger>
              <NavigationMenuContent
                class="data-[state=closed]:hidden data-[state=open]:animate-in data-[state=open]:fade-in-0"
              >
                <HeaderCategoryMenuPanel :category="category" />
              </NavigationMenuContent>
            </NavigationMenuItem>
          </NavigationMenuList>

          <RouterLink
            v-else
            to="/products"
            class="inline-flex h-full items-center px-4 text-sm font-medium text-foreground transition-colors hover:text-muted-foreground"
          >
            {{ t('header.products') }}
          </RouterLink>
        </div>

        <div class="flex-1 md:hidden"></div>

        <div class="hidden shrink-0 items-center gap-4 md:flex">
          <template v-if="!authStore.isAuthenticated">
            <RouterLink
              to="/login"
              class="inline-flex items-center gap-2 text-sm font-medium text-foreground hover:text-muted-foreground transition-colors"
            >
              <LogIn class="size-4" />
              {{ t('header.login') }}
            </RouterLink>
          </template>

          <RouterLink
            v-if="authStore.isAdmin"
            to="/admin"
            class="inline-flex items-center gap-2 text-sm font-medium transition-colors"
            :class="
              route.path.startsWith('/admin')
                ? 'text-primary'
                : 'text-foreground hover:text-muted-foreground'
            "
          >
            <Shield class="size-4" />
            Admin
          </RouterLink>

          <UserMenu v-if="authStore.isAuthenticated" />
          <RouterLink
            :to="MAGIC_COINS_ROUTE"
            class="rounded-full transition-transform duration-200 hover:-translate-y-0.5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/25 focus-visible:ring-offset-2"
            :aria-label="t('magicCoins.badgeLabel')"
          >
            <MagicCoinsBadge :coins="magicCoinsStore.balance" />
          </RouterLink>
          <ThemeToggle />
          <LanguageDropdown />
          <CartButton />
        </div>

        <div class="flex shrink-0 items-center gap-2 md:hidden">
          <CartButton />
          <MobileMenu />
        </div>
      </div>

      <NavigationMenuIndicator />

      <NavigationMenuViewport
        class="mt-0 w-screen rounded-none border-x-0 border-t-0 border-b border-border bg-background-soft text-foreground shadow-[0_24px_55px_oklch(0_0_0_/_0.13)] data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 md:w-screen"
      />
    </NavigationMenu>
  </header>
</template>
