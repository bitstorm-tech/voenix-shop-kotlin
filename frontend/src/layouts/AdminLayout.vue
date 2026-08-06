<script setup lang="ts">
import { computed, shallowRef } from 'vue'
import { House, LogOut, Menu, Shield } from 'lucide-vue-next'
import { RouterLink, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import AdminNavigation from '@/components/admin/AdminNavigation.vue'
import { adminNavigationItems } from '@/components/admin/adminNavigation'
import { Button } from '@/components/ui/button'
import {
  Sheet,
  SheetClose,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
  SheetTrigger,
} from '@/components/ui/sheet'
import { useAuthStore } from '@/stores/shared/auth'

const authStore = useAuthStore()
const router = useRouter()
const { t } = useI18n()

const mobileNavOpen = shallowRef(false)

const userLabel = computed(() => authStore.user?.email ?? t('admin.layout.adminUser'))

async function handleLogout() {
  await authStore.logout()
  await router.push('/login')
}

function closeMobileNav() {
  mobileNavOpen.value = false
}
</script>

<template>
  <div class="min-h-dvh bg-muted/30 text-foreground">
    <div class="flex min-h-dvh">
      <aside
        class="hidden h-dvh w-72 shrink-0 border-r border-border bg-background lg:sticky lg:top-0 lg:flex lg:flex-col"
      >
        <div class="border-b border-border px-5 py-4">
          <div class="flex items-center gap-3">
            <span
              class="flex size-9 items-center justify-center rounded-lg bg-primary/10 text-primary"
            >
              <Shield class="size-4" />
            </span>
            <div>
              <p class="text-sm font-semibold text-foreground">Voenix Admin</p>
              <p class="text-xs text-muted-foreground">{{ t('admin.layout.operations') }}</p>
            </div>
          </div>
        </div>

        <div class="flex-1 overflow-y-auto px-3 py-4">
          <AdminNavigation :items="adminNavigationItems" />
        </div>

        <div class="border-t border-border px-5 py-4">
          <p class="truncate text-sm text-muted-foreground">{{ userLabel }}</p>
          <div class="mt-3 space-y-2">
            <Button as-child variant="outline" size="sm" class="w-full justify-center">
              <RouterLink to="/">
                <House class="size-4" />
                {{ t('admin.layout.goToShop') }}
              </RouterLink>
            </Button>
            <Button variant="outline" size="sm" class="w-full justify-center" @click="handleLogout">
              <LogOut class="size-4" />
              {{ t('admin.layout.logout') }}
            </Button>
          </div>
        </div>
      </aside>

      <div class="flex min-w-0 flex-1 flex-col">
        <main class="flex-1 overflow-y-auto px-4 py-4 sm:px-6 lg:px-8 lg:py-6">
          <div class="mx-auto max-w-7xl">
            <div class="mb-4 lg:hidden">
              <Sheet v-model:open="mobileNavOpen">
                <SheetTrigger as-child>
                  <Button variant="outline" size="sm">
                    <Menu class="size-4" />
                    {{ t('admin.layout.openNavigation') }}
                  </Button>
                </SheetTrigger>

                <SheetContent
                  side="left"
                  class="flex w-full max-w-xs flex-col px-4 py-4 shadow-2xl sm:max-w-xs"
                  overlay-class="z-40 bg-black/50"
                  :close-label="t('admin.layout.closeNavigation')"
                >
                  <SheetHeader class="border-b border-border pb-3 pr-10">
                    <SheetTitle class="text-base">
                      {{ t('admin.layout.navigationTitle') }}
                    </SheetTitle>
                    <SheetDescription class="sr-only">
                      {{ t('admin.layout.navigationDescription') }}
                    </SheetDescription>
                  </SheetHeader>

                  <div class="flex-1 overflow-y-auto">
                    <AdminNavigation :items="adminNavigationItems" @navigate="closeMobileNav" />
                  </div>

                  <div class="border-t border-border pt-4">
                    <p class="truncate text-sm text-muted-foreground">{{ userLabel }}</p>
                    <div class="mt-3 space-y-2">
                      <SheetClose as-child>
                        <Button as-child variant="outline" size="sm" class="w-full justify-center">
                          <RouterLink to="/">
                            <House class="size-4" />
                            {{ t('admin.layout.goToShop') }}
                          </RouterLink>
                        </Button>
                      </SheetClose>
                      <Button
                        variant="outline"
                        size="sm"
                        class="w-full justify-center"
                        @click="handleLogout"
                      >
                        <LogOut class="size-4" />
                        {{ t('admin.layout.logout') }}
                      </Button>
                    </div>
                  </div>
                </SheetContent>
              </Sheet>
            </div>

            <RouterView />
          </div>
        </main>
      </div>
    </div>
  </div>
</template>
