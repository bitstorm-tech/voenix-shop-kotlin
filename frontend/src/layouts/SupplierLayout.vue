<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { LogOut, Package } from 'lucide-vue-next'
import { RouterLink, RouterView, useRouter } from 'vue-router'
import { Button } from '@/components/ui/button'
import { useAuthStore } from '@/stores/shared/auth'
import { useSupplierJobsStore } from '@/stores/supplier/jobs'

const authStore = useAuthStore()
const jobsStore = useSupplierJobsStore()
const router = useRouter()

/**
 * The supplier this login acts for. It is asked once here instead of on every job row, and it is
 * only a label — the backend decides what this login may read on every single request.
 */
const supplierName = computed(() => jobsStore.identity?.supplierName ?? 'Supplier')
const userLabel = computed(() => authStore.user?.email ?? 'Supplier login')

onMounted(() => {
  void jobsStore.fetchIdentity()
})

async function handleLogout() {
  await authStore.logout()
  await router.push('/login')
}
</script>

<template>
  <div class="min-h-dvh bg-muted/30 text-foreground">
    <header class="border-b border-border bg-background">
      <div
        class="mx-auto flex max-w-5xl flex-col gap-3 px-4 py-4 sm:flex-row sm:items-center sm:justify-between sm:px-6"
      >
        <div class="flex items-center gap-3">
          <span
            class="flex size-9 items-center justify-center rounded-lg bg-primary/10 text-primary"
          >
            <Package class="size-4" />
          </span>
          <div class="min-w-0">
            <p class="truncate text-sm font-semibold text-foreground">{{ supplierName }}</p>
            <p class="text-xs text-muted-foreground">Voenix fulfillment</p>
          </div>
        </div>

        <div class="flex items-center gap-3">
          <p class="hidden truncate text-sm text-muted-foreground sm:block">{{ userLabel }}</p>
          <Button variant="outline" size="sm" @click="handleLogout">
            <LogOut class="size-4" />
            Logout
          </Button>
        </div>
      </div>

      <nav class="mx-auto flex max-w-5xl gap-1 px-4 pb-2 sm:px-6" aria-label="Supplier navigation">
        <RouterLink
          :to="{ name: 'supplier-jobs' }"
          class="rounded-md px-3 py-1.5 text-sm font-medium text-muted-foreground hover:text-foreground"
          active-class="bg-muted text-foreground"
        >
          Production jobs
        </RouterLink>
      </nav>
    </header>

    <main class="mx-auto max-w-5xl px-4 py-6 sm:px-6">
      <p class="mb-4 truncate text-sm text-muted-foreground sm:hidden">{{ userLabel }}</p>
      <RouterView />
    </main>
  </div>
</template>
