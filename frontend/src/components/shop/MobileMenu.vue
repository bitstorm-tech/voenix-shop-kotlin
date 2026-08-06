<script setup lang="ts">
import { computed, shallowRef, watch } from 'vue'
import { useRouter, type RouteLocationRaw } from 'vue-router'
import { useI18n } from 'vue-i18n'
import {
  ArrowUpFromLine,
  ChevronDown,
  Download,
  Globe,
  LogIn,
  LogOut,
  Menu,
  Monitor,
  Moon,
  Package,
  Plus,
  Shield,
  Sun,
  User,
} from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetTitle,
  SheetTrigger,
} from '@/components/ui/sheet'
import { useAuthStore } from '@/stores/shared/auth'
import { usePwaInstallStore } from '@/stores/shared/pwaInstall'
import { useLocaleStore } from '@/stores/shared/locale'
import { useThemeStore, type Theme } from '@/stores/shared/theme'
import { useArticleCategoriesStore } from '@/stores/shop/articleCategories'
import { useMagicCoinsStore } from '@/stores/shop/magicCoins'
import { MAGIC_COINS_ROUTE } from '@/lib/magicCoins'
import MagicCoinsBadge from './MagicCoinsBadge.vue'

const { t, locale } = useI18n()
const authStore = useAuthStore()
const localeStore = useLocaleStore()
const themeStore = useThemeStore()
const pwaStore = usePwaInstallStore()
const categoriesStore = useArticleCategoriesStore()
const magicCoinsStore = useMagicCoinsStore()
const router = useRouter()

const isOpen = shallowRef(false)
const expandedCategoryId = shallowRef<number | null>(null)
const installInstructions = shallowRef<'ios' | 'browser' | null>(null)

const showInstallInstructions = computed(() => installInstructions.value !== null)
const menuCategories = computed(() => categoriesStore.mugCategories)

function categoryRoute(categoryId: number): RouteLocationRaw {
  return {
    name: 'mugs',
    query: { category: categoryId.toString() },
  }
}

function subcategoryRoute(categoryId: number, subcategoryId: number): RouteLocationRaw {
  return {
    name: 'mugs',
    query: {
      category: categoryId.toString(),
      subcategory: subcategoryId.toString(),
    },
  }
}

function toggleCategory(categoryId: number) {
  expandedCategoryId.value = expandedCategoryId.value === categoryId ? null : categoryId
}

function isCategoryExpanded(categoryId: number) {
  return expandedCategoryId.value === categoryId
}

const languages = [
  { code: 'de', flag: '🇩🇪' },
  { code: 'en', flag: '🇬🇧' },
]

function closeMenu() {
  isOpen.value = false
}

watch(isOpen, (open) => {
  if (!open) {
    expandedCategoryId.value = null
    installInstructions.value = null
  }
})

function navigateTo(to: RouteLocationRaw) {
  router.push(to)
  closeMenu()
}

function handleLogout() {
  authStore.logout()
  router.push('/')
  closeMenu()
}

function showManualInstallInstructions() {
  installInstructions.value = pwaStore.isIos ? 'ios' : 'browser'
}

async function handleInstall() {
  if (pwaStore.hasNativePrompt) {
    const result = await pwaStore.installApp()
    if (result === 'accepted') {
      closeMenu()
    } else if (result === 'unavailable') {
      showManualInstallInstructions()
    }
    return
  }

  showManualInstallInstructions()
}

function switchLanguage(code: string) {
  localeStore.setLocale(code)
  locale.value = code
}

const themes: { value: Theme; icon: typeof Sun }[] = [
  { value: 'light', icon: Sun },
  { value: 'dark', icon: Moon },
  { value: 'system', icon: Monitor },
]

const activeThemeIcon = computed(
  () => themes.find((option) => option.value === themeStore.theme)?.icon ?? Sun,
)
</script>

<template>
  <Sheet v-model:open="isOpen">
    <SheetTrigger as-child>
      <Button
        variant="ghost"
        size="icon-sm"
        class="rounded-md text-foreground hover:bg-accent hover:text-accent-foreground"
        aria-label="Open menu"
      >
        <Menu class="size-5" />
      </Button>
    </SheetTrigger>

    <SheetContent
      side="right"
      class="flex w-full max-w-none flex-col p-0 sm:max-w-none"
      close-label="Close"
    >
      <!-- Header -->
      <div class="flex min-h-14 items-center justify-end border-b p-4 pr-14">
        <SheetTitle class="sr-only">{{ t('mobileMenu.navigation') }}</SheetTitle>
        <SheetDescription class="sr-only">
          {{ t('mobileMenu.navigation') }}
        </SheetDescription>
      </div>

      <!-- Content -->
      <div class="flex-1 overflow-y-auto p-4">
        <!-- Navigation Section -->
        <div class="mb-6">
          <h3 class="mb-3 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
            {{ t('mobileMenu.navigation') }}
          </h3>
          <nav class="space-y-1">
            <template v-if="menuCategories.length > 0">
              <div v-for="category in menuCategories" :key="category.id">
                <Button
                  type="button"
                  variant="ghost"
                  class="flex w-full justify-start items-center gap-3 rounded-md px-3 py-2 text-sm font-medium text-foreground hover:bg-accent hover:text-accent-foreground transition-colors"
                  @click="toggleCategory(category.id)"
                >
                  <Package class="size-4" />
                  <span class="min-w-0 flex-1 text-left">{{ category.name }}</span>
                  <ChevronDown
                    class="size-4 shrink-0 transition-transform duration-200"
                    :class="{ 'rotate-180': isCategoryExpanded(category.id) }"
                  />
                </Button>
                <div
                  class="grid transition-[grid-template-rows] duration-200 ease-out"
                  :class="isCategoryExpanded(category.id) ? 'grid-rows-[1fr]' : 'grid-rows-[0fr]'"
                >
                  <div class="overflow-hidden">
                    <div class="space-y-1">
                      <Button
                        type="button"
                        variant="ghost"
                        class="flex w-full justify-start items-center gap-3 rounded-md px-3 py-2 pl-10 text-sm text-muted-foreground hover:bg-accent hover:text-accent-foreground transition-colors"
                        @click="navigateTo(categoryRoute(category.id))"
                      >
                        {{ t('header.allCategory', { category: category.name }) }}
                      </Button>
                      <Button
                        v-for="subcategory in category.subcategories ?? []"
                        :key="subcategory.id"
                        type="button"
                        variant="ghost"
                        class="flex w-full justify-start items-center gap-3 rounded-md px-3 py-2 pl-10 text-sm text-muted-foreground hover:bg-accent hover:text-accent-foreground transition-colors"
                        @click="navigateTo(subcategoryRoute(category.id, subcategory.id))"
                      >
                        {{ subcategory.name }}
                      </Button>
                    </div>
                  </div>
                </div>
              </div>
            </template>
            <Button
              v-else
              type="button"
              variant="ghost"
              class="flex w-full justify-start items-center gap-3 rounded-md px-3 py-2 text-sm font-medium text-foreground hover:bg-accent hover:text-accent-foreground transition-colors"
              @click="navigateTo('/mugs')"
            >
              <Package class="size-4" />
              {{ t('header.products') }}
            </Button>
          </nav>
        </div>

        <!-- Account Section -->
        <div class="mb-6">
          <h3 class="mb-3 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
            {{ t('mobileMenu.account') }}
          </h3>
          <nav class="space-y-1">
            <!-- Guest Links -->
            <template v-if="!authStore.isAuthenticated">
              <Button
                type="button"
                variant="ghost"
                class="flex w-full justify-start items-center gap-3 rounded-md px-3 py-2 text-sm font-medium text-foreground hover:bg-accent hover:text-accent-foreground transition-colors"
                @click="navigateTo('/login')"
              >
                <LogIn class="size-4" />
                {{ t('header.login') }}
              </Button>
            </template>

            <!-- Authenticated Links -->
            <template v-else>
              <Button
                v-if="authStore.isAdmin"
                type="button"
                variant="ghost"
                class="flex w-full justify-start items-center gap-3 rounded-md px-3 py-2 text-sm font-medium text-foreground hover:bg-accent hover:text-accent-foreground transition-colors"
                @click="navigateTo('/admin')"
              >
                <Shield class="size-4" />
                Admin
              </Button>
              <Button
                type="button"
                variant="ghost"
                class="flex w-full justify-start items-center gap-3 rounded-md px-3 py-2 text-sm font-medium text-foreground hover:bg-accent hover:text-accent-foreground transition-colors"
                @click="navigateTo('/orders')"
              >
                <Package class="size-4" />
                {{ t('mobileMenu.orders') }}
              </Button>
              <Button
                type="button"
                variant="ghost"
                class="flex w-full justify-start items-center gap-3 rounded-md px-3 py-2 text-sm font-medium text-foreground hover:bg-accent hover:text-accent-foreground transition-colors"
                @click="navigateTo('/profile')"
              >
                <User class="size-4" />
                {{ t('mobileMenu.profile') }}
              </Button>
              <Button
                type="button"
                variant="ghost"
                class="flex w-full justify-start items-center gap-3 rounded-md px-3 py-2 text-sm font-medium text-foreground hover:bg-accent hover:text-accent-foreground transition-colors"
                @click="handleLogout"
              >
                <LogOut class="size-4" />
                {{ t('mobileMenu.logout') }}
              </Button>
            </template>
          </nav>
        </div>

        <!-- Utilities Section -->
        <div class="space-y-3">
          <!-- Install App -->
          <Button
            v-if="pwaStore.canInstall"
            type="button"
            variant="ghost"
            class="flex w-full justify-start items-center gap-3 rounded-md px-3 py-2 text-sm font-medium text-foreground transition-colors hover:bg-accent hover:text-accent-foreground disabled:cursor-not-allowed disabled:opacity-60"
            :aria-busy="pwaStore.installing"
            :aria-controls="showInstallInstructions ? 'pwa-install-instructions' : undefined"
            :aria-expanded="showInstallInstructions"
            :disabled="pwaStore.installing"
            @click="handleInstall"
          >
            <Download class="size-4" />
            {{ t('pwaInstall.installButton') }}
          </Button>

          <div
            v-if="showInstallInstructions"
            id="pwa-install-instructions"
            aria-live="polite"
            class="ml-3 border-l border-border/70 pl-4 text-sm text-muted-foreground"
          >
            <ol v-if="installInstructions === 'ios'" class="space-y-2">
              <li class="flex items-start gap-2.5">
                <span
                  class="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-muted text-xs font-semibold text-foreground"
                  >1</span
                >
                <span class="flex items-center gap-1.5">
                  {{ t('pwaInstall.ios.step1') }}
                  <ArrowUpFromLine class="size-4 text-primary" />
                </span>
              </li>
              <li class="flex items-start gap-2.5">
                <span
                  class="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-muted text-xs font-semibold text-foreground"
                  >2</span
                >
                <span>{{ t('pwaInstall.ios.step2') }}</span>
              </li>
              <li class="flex items-start gap-2.5">
                <span
                  class="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-muted text-xs font-semibold text-foreground"
                  >3</span
                >
                <span class="flex items-center gap-1.5">
                  {{ t('pwaInstall.ios.step3') }}
                  <Plus class="size-4 text-primary" />
                </span>
              </li>
            </ol>
            <ol v-else class="space-y-2">
              <li class="flex items-start gap-2.5">
                <span
                  class="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-muted text-xs font-semibold text-foreground"
                  >1</span
                >
                <span>{{ t('pwaInstall.browser.step1') }}</span>
              </li>
              <li class="flex items-start gap-2.5">
                <span
                  class="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-muted text-xs font-semibold text-foreground"
                  >2</span
                >
                <span>{{ t('pwaInstall.browser.step2') }}</span>
              </li>
            </ol>
          </div>

          <!-- Magic Coins -->
          <Button
            type="button"
            variant="ghost"
            class="flex w-full justify-start items-center rounded-md px-3 py-1 transition-colors hover:bg-accent"
            :aria-label="t('magicCoins.badgeLabel')"
            @click="navigateTo(MAGIC_COINS_ROUTE)"
          >
            <MagicCoinsBadge :coins="magicCoinsStore.balance" />
          </Button>

          <!-- Language Switcher -->
          <div class="px-3">
            <div class="flex items-center gap-2 text-sm text-muted-foreground">
              <Globe class="size-4" />
              <span>{{ t(`mobileMenu.languages.${localeStore.locale}`) }}</span>
            </div>
            <div class="mt-2 flex gap-2">
              <Button
                v-for="lang in languages"
                :key="lang.code"
                type="button"
                variant="ghost"
                class="flex justify-start items-center gap-1.5 rounded-md px-3 py-1.5 text-sm transition-colors"
                :class="
                  localeStore.locale === lang.code
                    ? 'bg-primary text-primary-foreground hover:bg-primary hover:text-primary-foreground'
                    : 'bg-accent text-accent-foreground hover:bg-accent/80'
                "
                @click="switchLanguage(lang.code)"
              >
                <span>{{ lang.flag }}</span>
                <span>{{ t(`mobileMenu.languages.${lang.code}`) }}</span>
              </Button>
            </div>
          </div>

          <!-- Theme Switcher -->
          <div class="px-3">
            <div class="flex items-center gap-2 text-sm text-muted-foreground">
              <component :is="activeThemeIcon" class="size-4" />
              <span>{{ t('mobileMenu.theme') }}</span>
            </div>
            <div class="mt-2 flex gap-2">
              <Button
                v-for="option in themes"
                :key="option.value"
                type="button"
                variant="ghost"
                class="flex justify-start items-center gap-1.5 rounded-md px-3 py-1.5 text-sm transition-colors"
                :class="
                  themeStore.theme === option.value
                    ? 'bg-primary text-primary-foreground hover:bg-primary hover:text-primary-foreground'
                    : 'bg-accent text-accent-foreground hover:bg-accent/80'
                "
                @click="themeStore.setTheme(option.value)"
              >
                <component :is="option.icon" class="size-4" />
                <span>{{ t(`mobileMenu.themes.${option.value}`) }}</span>
              </Button>
            </div>
          </div>
        </div>
      </div>
    </SheetContent>
  </Sheet>
</template>
