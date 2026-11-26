<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { ArrowRight, BadgeCheck, Globe, Loader2, Mail, RefreshCw, Truck } from 'lucide-vue-next'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { formatPrice } from '@/lib/formatPrice'
import { type Country, useCountriesStore } from '@/stores/shop/countries'

interface ShippingContent {
  eyebrow: string
  title: string
  lead: string
  intro: string
  sectionOverview: string
  countryColumn: string
  priceLabel: string
  thresholdLabel: string
  supportedCountriesLabel: string
  overviewLabel: string
  overviewValue: string
  loadingCountries: string
  loadError: string
  retry: string
  noCountries: string
  alwaysFree: string
  noFreeThreshold: string
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

const { t, tm, locale } = useI18n()
const countriesStore = useCountriesStore()

const pageContent = computed(() => tm('footerPages.shipping.content') as unknown as ShippingContent)
const regionNames = computed(() => new Intl.DisplayNames([locale.value], { type: 'region' }))

function localizedCountryName(country: Country): string {
  return regionNames.value.of(country.countryCode) ?? country.name
}

/** Germany first when present, then all other destinations alphabetically in the active locale. */
const sortedCountries = computed(() =>
  [...countriesStore.countries].sort((a, b) => {
    if (a.countryCode === 'DE' && b.countryCode !== 'DE') return -1
    if (b.countryCode === 'DE' && a.countryCode !== 'DE') return 1
    return localizedCountryName(a).localeCompare(localizedCountryName(b), locale.value)
  }),
)

onMounted(() => {
  void countriesStore.fetchCountries()
})

function retryCountries() {
  void countriesStore.fetchCountries({ force: true })
}
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

            <div
              v-if="countriesStore.isLoading && sortedCountries.length === 0"
              class="mt-4 flex items-center gap-2 text-sm text-foreground-muted"
            >
              <Loader2 class="size-4 animate-spin" />
              <span>{{ pageContent.loadingCountries }}</span>
            </div>

            <ul
              v-else-if="sortedCountries.length > 0"
              class="mt-4 flex flex-wrap gap-2"
              data-testid="shipping-supported-countries"
            >
              <li
                v-for="country in sortedCountries"
                :key="country.countryCode"
                class="rounded-md border border-border bg-background px-3 py-2 text-sm text-foreground-soft"
              >
                {{ localizedCountryName(country) }}
              </li>
            </ul>

            <p v-else class="mt-4 text-sm text-foreground-muted">
              {{ pageContent.noCountries }}
            </p>
          </div>
        </aside>
      </header>

      <section class="mt-10">
        <header class="mb-4">
          <div class="text-xs font-bold uppercase tracking-[0.14em] text-primary">
            {{ pageContent.sectionOverview }}
          </div>
        </header>

        <Card
          v-if="countriesStore.isLoading && sortedCountries.length === 0"
          class="flex items-center justify-center gap-2 px-5 py-12 text-sm text-muted-foreground"
        >
          <Loader2 class="size-4 animate-spin" />
          <span>{{ pageContent.loadingCountries }}</span>
        </Card>

        <Alert
          v-else-if="countriesStore.error && sortedCountries.length === 0"
          variant="destructive"
          data-testid="shipping-countries-error"
        >
          <div class="flex flex-col items-start justify-between gap-3 sm:flex-row sm:items-center">
            <p class="m-0 text-sm">{{ pageContent.loadError }}</p>
            <Button variant="outline" size="sm" @click="retryCountries">
              <RefreshCw class="size-4" />
              {{ pageContent.retry }}
            </Button>
          </div>
        </Alert>

        <Card
          v-else-if="sortedCountries.length === 0"
          class="px-5 py-12 text-center text-sm text-muted-foreground"
        >
          {{ pageContent.noCountries }}
        </Card>

        <Card v-else class="overflow-hidden" data-testid="shipping-rates-table">
          <div class="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{{ pageContent.countryColumn }}</TableHead>
                  <TableHead>{{ pageContent.priceLabel }}</TableHead>
                  <TableHead>{{ pageContent.thresholdLabel }}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                <TableRow
                  v-for="country in sortedCountries"
                  :key="country.countryCode"
                  :data-testid="`shipping-rate-${country.countryCode}`"
                >
                  <TableCell class="font-medium text-foreground">
                    <span>{{ localizedCountryName(country) }}</span>
                    <span class="ml-2 font-mono text-xs text-muted-foreground">
                      {{ country.countryCode }}
                    </span>
                  </TableCell>
                  <TableCell class="whitespace-nowrap font-semibold tabular-nums">
                    <span v-if="country.shippingCostCents === 0" class="text-success">
                      {{ t('cart.shippingFree') }}
                    </span>
                    <span v-else>{{ formatPrice(country.shippingCostCents) }}</span>
                  </TableCell>
                  <TableCell class="whitespace-nowrap tabular-nums">
                    <span v-if="country.shippingCostCents === 0" class="text-success">
                      {{ pageContent.alwaysFree }}
                    </span>
                    <span
                      v-else-if="country.freeShippingThresholdCents !== null"
                      class="font-semibold text-[var(--price-accent)]"
                    >
                      {{
                        t('footerPages.shipping.content.freeFromAmount', {
                          amount: formatPrice(country.freeShippingThresholdCents),
                        })
                      }}
                    </span>
                    <span v-else class="text-muted-foreground">
                      {{ pageContent.noFreeThreshold }}
                    </span>
                  </TableCell>
                </TableRow>
              </TableBody>
            </Table>
          </div>
        </Card>
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
