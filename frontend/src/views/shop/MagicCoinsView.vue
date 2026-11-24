<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { ArrowRight, Coins } from 'lucide-vue-next'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { useMagicCoinsStore } from '@/stores/shop/magicCoins'

const { t } = useI18n()
const magicCoinsStore = useMagicCoinsStore()

const currentBalanceLabel = computed(() => magicCoinsStore.balance ?? '–')

onMounted(() => {
  magicCoinsStore.fetchBalance()
})
</script>

<template>
  <div class="pb-12">
    <section class="relative overflow-hidden border-y border-border/70 bg-background">
      <div class="absolute inset-x-0 top-0 h-px bg-primary/30" />

      <div class="relative py-8">
        <h1 class="max-w-3xl font-heading text-3xl font-semibold tracking-tight sm:text-5xl">
          {{ t('magicCoins.hero.title') }}
        </h1>
      </div>
    </section>

    <section class="mx-auto max-w-xl pt-8">
      <Card class="bg-card p-6 shadow-sm sm:p-8">
        <div
          class="flex items-center justify-between gap-4 rounded-xl border border-border bg-muted/40 px-4 py-3"
        >
          <span class="flex items-center gap-2 text-sm text-muted-foreground">
            <Coins class="size-4 text-primary" aria-hidden="true" />
            {{ t('magicCoins.summary.currentBalance') }}
          </span>
          <span class="text-lg font-semibold tabular-nums" data-testid="magic-coins-balance">
            {{ currentBalanceLabel }}
          </span>
        </div>

        <h2 class="mt-6 font-heading text-xl font-semibold">
          {{ t('magicCoins.unavailableTitle') }}
        </h2>
        <p class="mt-2 text-sm text-muted-foreground">
          {{ t('magicCoins.unavailableDescription') }}
        </p>

        <Button as-child class="mt-6" size="lg">
          <RouterLink to="/products">
            {{ t('magicCoins.backToProducts') }}
            <ArrowRight class="size-4" />
          </RouterLink>
        </Button>
      </Card>
    </section>
  </div>
</template>
