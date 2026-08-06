<script setup lang="ts">
import { computed, onMounted, shallowRef } from 'vue'
import { ArrowRight, Coins } from 'lucide-vue-next'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { SelectableCard } from '@/components/ui/selectable-card'
import { useToast } from '@/composables/useToast'
import { formatPrice } from '@/lib/formatPrice'
import {
  MAGIC_COINS_ROUTE,
  featuredMagicCoinsPlan,
  getMagicCoinsPlan,
  getTotalMagicCoins,
  magicCoinsPlans,
  type MagicCoinsPlanId,
} from '@/lib/magicCoins'
import { useAuthStore } from '@/stores/shared/auth'
import { useMagicCoinsStore } from '@/stores/shop/magicCoins'

const { t } = useI18n()
const router = useRouter()
const authStore = useAuthStore()
const magicCoinsStore = useMagicCoinsStore()
const { toast } = useToast()

const selectedPlanId = shallowRef<MagicCoinsPlanId>(featuredMagicCoinsPlan.id)

const selectedPlan = computed(() => getMagicCoinsPlan(selectedPlanId.value))
const currentBalanceLabel = computed(() => magicCoinsStore.balance ?? '–')
const projectedBalance = computed(() =>
  magicCoinsStore.balance === null
    ? '–'
    : magicCoinsStore.balance + getTotalMagicCoins(selectedPlan.value),
)

const summaryRows = computed(() => [
  {
    label: t('magicCoins.summary.currentBalance'),
    value: currentBalanceLabel.value,
  },
  {
    label: t('magicCoins.summary.selectedPlan'),
    value: t(`magicCoins.plans.items.${selectedPlan.value.id}.name`),
  },
  {
    label: t('magicCoins.summary.packagePrice'),
    value: formatPrice(selectedPlan.value.priceInCents),
    valueClass: 'tabular-nums',
  },
])

function selectPlan(planId: MagicCoinsPlanId) {
  selectedPlanId.value = planId
}

onMounted(() => {
  magicCoinsStore.fetchBalance()
})

function handlePurchase() {
  if (!authStore.isAuthenticated) {
    router.push({
      path: '/login',
      query: { redirect: MAGIC_COINS_ROUTE },
    })
    return
  }

  toast({
    title: t('magicCoins.toast.title', {
      plan: t(`magicCoins.plans.items.${selectedPlan.value.id}.name`),
    }),
    description: t('magicCoins.toast.description'),
  })
}
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

    <section id="magic-coin-plans" class="grid gap-6 pt-8 xl:grid-cols-[minmax(0,1fr)_22rem]">
      <div class="grid gap-3 lg:grid-cols-3">
        <SelectableCard
          v-for="plan in magicCoinsPlans"
          :key="plan.id"
          :selected="selectedPlanId === plan.id"
          class="group flex min-h-64 flex-col rounded-2xl bg-card p-5 transition-all duration-200 hover:-translate-y-0.5 hover:border-primary/25 data-[state=selected]:border-primary/45 data-[state=selected]:shadow-[0_24px_70px_-52px_rgba(229,114,44,0.75)]"
          @click="selectPlan(plan.id)"
        >
          <div class="flex items-start justify-between gap-3">
            <div>
              <p class="text-xs font-semibold uppercase tracking-[0.18em] text-muted-foreground">
                {{ t(`magicCoins.plans.items.${plan.id}.name`) }}
              </p>
              <p class="mt-3 font-heading text-5xl font-semibold leading-none">
                {{ getTotalMagicCoins(plan) }}
              </p>
            </div>

            <Badge v-if="plan.featured" class="bg-primary/10 text-primary">
              {{ t('magicCoins.plans.popular') }}
            </Badge>
            <Badge
              v-else-if="selectedPlanId === plan.id"
              class="bg-background-soft text-foreground"
            >
              {{ t('magicCoins.plans.selected') }}
            </Badge>
          </div>

          <div class="mt-4 flex items-center gap-2 text-sm text-muted-foreground">
            <Coins class="size-4 text-primary" />
            <span>{{ t('magicCoins.plans.coinsLabel') }}</span>
          </div>

          <div class="mt-auto flex items-end justify-between gap-4 border-t border-border/80 pt-5">
            <div>
              <p class="text-2xl font-semibold tabular-nums">
                {{ formatPrice(plan.priceInCents) }}
              </p>
              <p class="text-xs text-muted-foreground">
                {{ t('magicCoins.plans.priceSuffix') }}
              </p>
            </div>

            <Badge
              v-if="plan.bonusCoins > 0"
              class="border border-primary/20 bg-primary/10 text-primary"
            >
              {{ t('magicCoins.plans.bonusLabel', { coins: plan.bonusCoins }) }}
            </Badge>
          </div>
        </SelectableCard>
      </div>

      <Card as="aside" class="h-fit bg-card p-5 shadow-sm xl:sticky xl:top-6">
        <p class="text-xs font-semibold uppercase tracking-[0.22em] text-muted-foreground">
          {{ t('magicCoins.summary.eyebrow') }}
        </p>
        <h2 class="mt-2 font-heading text-2xl font-semibold">
          {{ t(`magicCoins.plans.items.${selectedPlan.id}.name`) }}
        </h2>

        <dl class="mt-6 space-y-4 text-sm">
          <div
            v-for="row in summaryRows"
            :key="row.label"
            class="flex items-center justify-between gap-3"
          >
            <dt class="text-muted-foreground">{{ row.label }}</dt>
            <dd class="font-medium" :class="row.valueClass">{{ row.value }}</dd>
          </div>
          <div
            class="flex items-center justify-between gap-3 rounded-xl border border-primary/15 bg-primary/8 px-4 py-3"
          >
            <dt class="text-foreground">{{ t('magicCoins.summary.afterPurchase') }}</dt>
            <dd class="font-semibold">{{ projectedBalance }}</dd>
          </div>
        </dl>

        <Button class="mt-6 w-full" size="lg" @click="handlePurchase">
          {{
            t(authStore.isAuthenticated ? 'magicCoins.summary.cta' : 'magicCoins.summary.guestCta')
          }}
          <ArrowRight class="size-4" />
        </Button>
      </Card>
    </section>
  </div>
</template>
