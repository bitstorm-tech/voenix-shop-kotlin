<script setup lang="ts">
import { Building2, FileText, Mail, MapPin } from 'lucide-vue-next'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

const { t, locale } = useI18n()

const companyName = 'JoTo AI GbR'
const representatives = 'Josef Bauer & Thomas Pränger'
const street = 'Wallbergstr. 18'
const city = '83607 Holzkirchen'
const emailAddress = 'write-us@voenix.shop'
const vatId = 'DE455554144'

const copy = computed(() =>
  locale.value.startsWith('de')
    ? {
        intro: t('footerPages.imprint.description'),
        providerTitle: 'Herausgeber',
        representativesLabel: 'Vertreten durch',
        addressLabel: 'Anschrift',
        contactTitle: 'Kontakt',
        contactLabel: 'E-Mail-Adresse',
        taxTitle: 'Steuer',
        vatLabel: 'USt-IdNr.',
        responsibleTitle: 'Inhaltlich verantwortlich',
        responsibleLabel: 'Verantwortlich nach § 18 Abs. 2 MStV',
        country: 'Deutschland',
      }
    : {
        intro: t('footerPages.imprint.description'),
        providerTitle: 'Publisher',
        representativesLabel: 'Represented by',
        addressLabel: 'Address',
        contactTitle: 'Contact',
        contactLabel: 'Email',
        taxTitle: 'Tax',
        vatLabel: 'VAT ID',
        responsibleTitle: 'Responsible for content',
        responsibleLabel: 'Responsible pursuant to Sec. 18 para. 2 MStV',
        country: 'Germany',
      },
)

const addressLines = computed(() => [street, city, copy.value.country])

const facts = computed(() => [
  {
    key: 'contact',
    title: copy.value.contactTitle,
    label: copy.value.contactLabel,
    value: emailAddress,
    href: `mailto:${emailAddress}`,
    icon: Mail,
  },
  {
    key: 'tax',
    title: copy.value.taxTitle,
    label: copy.value.vatLabel,
    value: vatId,
    icon: FileText,
  },
  {
    key: 'responsible',
    title: copy.value.responsibleTitle,
    label: copy.value.responsibleLabel,
    value: representatives,
    icon: Building2,
  },
])
</script>

<template>
  <section class="bg-background text-foreground">
    <div class="mx-auto max-w-6xl px-6 py-14 sm:px-8 lg:py-20">
      <header class="mb-8 lg:mb-10">
        <div class="max-w-3xl space-y-4">
          <h1 class="max-w-[10ch] text-5xl font-bold leading-none text-foreground sm:text-6xl">
            {{ t('footerPages.imprint.title') }}
          </h1>
          <p class="max-w-2xl text-base leading-7 text-foreground-muted">
            {{ copy.intro }}
          </p>
        </div>
      </header>

      <div class="grid gap-4 md:grid-cols-[minmax(0,1.3fr)_minmax(18rem,0.7fr)] md:items-start">
        <article
          class="relative overflow-hidden rounded-lg border border-border bg-card p-6 shadow-sm"
        >
          <div class="absolute left-0 top-0 h-2 w-36 rounded-br-full bg-primary" />

          <div class="space-y-8">
            <div class="space-y-3">
              <div
                class="inline-flex items-center gap-3 text-xs font-extrabold uppercase tracking-[0.12em] text-foreground"
              >
                <Building2 class="size-5" />
                <span>{{ copy.providerTitle }}</span>
              </div>

              <div class="space-y-3">
                <p class="text-4xl font-bold leading-none text-foreground sm:text-5xl">
                  {{ companyName }}
                </p>
                <div class="grid gap-1">
                  <span
                    class="text-xs font-extrabold uppercase tracking-[0.12em] text-foreground-faint"
                  >
                    {{ copy.representativesLabel }}
                  </span>
                  <p class="text-base font-semibold leading-7 text-foreground-soft">
                    {{ representatives }}
                  </p>
                </div>
              </div>
            </div>

            <div class="grid gap-4 rounded-lg bg-background-soft p-5">
              <div
                class="inline-flex items-center gap-3 text-xs font-extrabold uppercase tracking-[0.12em] text-foreground"
              >
                <MapPin class="size-5" />
                <span>{{ copy.addressLabel }}</span>
              </div>

              <address class="grid gap-1 not-italic">
                <p
                  v-for="line in addressLines"
                  :key="line"
                  class="text-base leading-7 text-foreground-soft"
                >
                  {{ line }}
                </p>
              </address>
            </div>
          </div>
        </article>

        <div class="grid gap-4">
          <article
            v-for="fact in facts"
            :key="fact.key"
            class="grid grid-cols-[auto_1fr] items-start gap-4 rounded-lg border border-border bg-card p-5 shadow-sm transition-colors hover:border-primary/40"
          >
            <div
              class="inline-flex size-11 items-center justify-center rounded-lg bg-primary/10 text-primary"
            >
              <component :is="fact.icon" class="size-5" />
            </div>

            <div class="space-y-2">
              <p class="text-base font-bold text-foreground">{{ fact.title }}</p>
              <p class="text-xs font-extrabold uppercase tracking-[0.11em] text-foreground-faint">
                {{ fact.label }}
              </p>

              <a
                v-if="'href' in fact"
                :href="fact.href"
                class="inline-block break-words text-base font-semibold leading-7 text-foreground-soft transition-colors hover:text-primary"
              >
                {{ fact.value }}
              </a>
              <p v-else class="break-words text-base font-semibold leading-7 text-foreground-soft">
                {{ fact.value }}
              </p>
            </div>
          </article>
        </div>
      </div>
    </div>
  </section>
</template>
