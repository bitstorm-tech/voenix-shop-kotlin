<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { ArrowRight, BadgeCheck, Globe, Mail, PackageCheck, Truck } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'

interface ShippingCard {
  id: string
  label: string
  badge: string
  carrier: string
  price: string
  freeFrom: string
  details: string
}

interface ShippingContent {
  eyebrow: string
  title: string
  lead: string
  intro: string
  sectionOverview: string
  priceLabel: string
  thresholdLabel: string
  supportedCountriesLabel: string
  supportedCountries: string[]
  overviewLabel: string
  overviewValue: string
  cards: ShippingCard[]
  requestTitle: string
  requestText: string
  requestHint: string
  returnsTitle: string
  returnsText: string
  returnsAction: string
  emailLabel: string
  emailValue: string
  legalTitle: string
  legalParagraphs: string[]
}

const { tm } = useI18n()

const pageContent = computed(() => tm('footerPages.shipping.content') as unknown as ShippingContent)
</script>

<template>
  <section class="bg-background text-foreground">
    <div class="mx-auto w-full max-w-[1100px] px-6 py-12 sm:px-8 lg:py-20">
      <header class="grid gap-8 lg:grid-cols-[minmax(0,1.3fr)_minmax(18rem,0.8fr)] lg:items-end">
        <div>
          <div
            class="inline-flex items-center gap-2 text-xs font-bold uppercase tracking-[0.16em] text-primary"
          >
            <Truck class="size-4" />
            <span>{{ pageContent.eyebrow }}</span>
          </div>

          <p class="mt-4 max-w-2xl text-sm font-bold uppercase tracking-[0.06em] text-primary">
            {{ pageContent.lead }}
          </p>
          <h1
            class="mt-3 max-w-[12ch] text-4xl font-extrabold leading-none text-foreground sm:text-5xl lg:text-6xl"
          >
            {{ pageContent.title }}
          </h1>
          <p class="mt-5 max-w-2xl text-base leading-7 text-foreground-muted">
            {{ pageContent.intro }}
          </p>

          <div class="mt-6 flex flex-wrap gap-3">
            <div
              class="inline-flex items-center gap-2 rounded-md border border-border bg-background-soft px-4 py-2 text-sm font-semibold text-foreground-soft"
            >
              <PackageCheck class="size-4" />
              <span>{{ pageContent.cards[0]?.freeFrom }}</span>
            </div>
            <div
              class="inline-flex items-center gap-2 rounded-md border border-border bg-background-soft px-4 py-2 text-sm font-semibold text-foreground-soft"
            >
              <Globe class="size-4" />
              <span>{{ pageContent.cards[1]?.freeFrom }}</span>
            </div>
          </div>
        </div>

        <aside class="grid gap-4">
          <div class="rounded-lg border border-border bg-background-soft p-4">
            <span class="text-xs font-bold uppercase tracking-[0.12em] text-foreground-faint">
              {{ pageContent.overviewLabel }}
            </span>
            <strong class="mt-2 block text-base leading-7 text-foreground">
              {{ pageContent.overviewValue }}
            </strong>
          </div>

          <div class="rounded-lg border border-border bg-background-soft p-4">
            <div class="inline-flex items-center gap-2 text-sm font-bold text-foreground-muted">
              <Globe class="size-4" />
              <span>{{ pageContent.supportedCountriesLabel }}</span>
            </div>

            <ul class="mt-4 flex flex-wrap gap-2">
              <li
                v-for="country in pageContent.supportedCountries"
                :key="country"
                class="rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground-soft"
              >
                {{ country }}
              </li>
            </ul>
          </div>
        </aside>
      </header>

      <section class="mt-10">
        <header class="mb-4">
          <div class="text-xs font-bold uppercase tracking-[0.14em] text-primary">
            {{ pageContent.sectionOverview }}
          </div>
        </header>

        <div class="grid gap-4 lg:grid-cols-2">
          <article
            v-for="card in pageContent.cards"
            :key="card.id"
            class="rounded-lg border border-border bg-background-soft p-5 shadow-sm"
          >
            <div class="flex items-center justify-between text-primary">
              <span
                class="rounded-sm bg-primary/10 px-2 py-1 text-xs font-bold uppercase tracking-[0.08em] text-primary"
              >
                {{ card.badge }}
              </span>
              <PackageCheck class="size-5" />
            </div>

            <h3 class="mt-5 text-2xl font-extrabold leading-tight text-foreground">
              {{ card.label }}
            </h3>
            <p class="mt-2 text-base font-semibold leading-7 text-foreground">
              {{ card.carrier }}
            </p>
            <p class="mt-3 leading-7 text-foreground-muted">{{ card.details }}</p>

            <dl class="mt-5 grid gap-4 border-t border-border pt-5 sm:grid-cols-2">
              <div class="grid gap-1">
                <dt class="text-xs font-bold uppercase tracking-[0.08em] text-foreground-faint">
                  {{ pageContent.priceLabel }}
                </dt>
                <dd class="text-lg font-bold text-foreground">{{ card.price }}</dd>
              </div>
              <div class="grid gap-1">
                <dt class="text-xs font-bold uppercase tracking-[0.08em] text-foreground-faint">
                  {{ pageContent.thresholdLabel }}
                </dt>
                <dd class="text-lg font-bold text-[var(--price-accent)]">{{ card.freeFrom }}</dd>
              </div>
            </dl>
          </article>
        </div>
      </section>

      <section class="mt-4 grid gap-4 lg:grid-cols-[1.15fr_0.85fr]">
        <article
          class="grid gap-4 rounded-lg border border-border bg-background-soft p-5 shadow-sm"
        >
          <div class="flex items-start gap-4">
            <div
              class="inline-flex size-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary"
            >
              <Mail class="size-5" />
            </div>
            <div>
              <h2 class="text-xl font-extrabold leading-tight text-foreground">
                {{ pageContent.requestTitle }}
              </h2>
              <p class="mt-2 leading-7 text-foreground-muted">{{ pageContent.requestText }}</p>
            </div>
          </div>

          <p class="leading-7 text-foreground-muted">{{ pageContent.requestHint }}</p>

          <a
            class="inline-flex flex-col items-start gap-2 rounded-lg border border-border bg-background px-4 py-3 text-foreground transition-colors hover:border-foreground-faint hover:bg-accent sm:flex-row sm:items-center sm:justify-between"
            :href="`mailto:${pageContent.emailValue}`"
          >
            <span class="text-xs font-bold uppercase tracking-[0.08em]">
              {{ pageContent.emailLabel }}
            </span>
            <strong class="break-all text-sm sm:text-base">{{ pageContent.emailValue }}</strong>
          </a>
        </article>

        <article
          class="grid gap-4 rounded-lg border border-border bg-background-soft p-5 shadow-sm"
        >
          <div class="flex items-start gap-4">
            <div
              class="inline-flex size-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary"
            >
              <BadgeCheck class="size-5" />
            </div>
            <div>
              <h2 class="text-xl font-extrabold leading-tight text-foreground">
                {{ pageContent.returnsTitle }}
              </h2>
              <p class="mt-2 leading-7 text-foreground-muted">{{ pageContent.returnsText }}</p>
            </div>
          </div>

          <Button as-child variant="outline" class="w-full justify-between rounded-lg">
            <RouterLink :to="{ name: 'returns' }">
              <span>{{ pageContent.returnsAction }}</span>
              <ArrowRight class="size-4" />
            </RouterLink>
          </Button>
        </article>
      </section>

      <section class="mt-10">
        <header class="mb-4">
          <div class="text-xs font-bold uppercase tracking-[0.14em] text-primary">
            {{ pageContent.legalTitle }}
          </div>
        </header>

        <div class="space-y-4 rounded-lg border border-border bg-background-soft p-5 shadow-sm">
          <p
            v-for="paragraph in pageContent.legalParagraphs"
            :key="paragraph"
            class="leading-8 text-foreground-muted"
          >
            {{ paragraph }}
          </p>
        </div>
      </section>
    </div>
  </section>
</template>
