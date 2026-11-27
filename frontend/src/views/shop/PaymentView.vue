<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Building2, CreditCard, ExternalLink, ShieldCheck } from 'lucide-vue-next'

// Keep in sync with the payment methods activated in the Mollie profile (see Footer.vue)
import VisaIcon from '@/assets/images/payments/visa.svg'
import MastercardIcon from '@/assets/images/payments/mastercard.svg'
import ApplePayIcon from '@/assets/images/payments/apple-pay.svg'
import GooglePayIcon from '@/assets/images/payments/google-pay.svg'
import PaypalIcon from '@/assets/images/payments/paypal.svg'
import KlarnaIcon from '@/assets/images/payments/klarna.svg'

interface PaymentMethodEntry {
  id: string
  name: string
  description: string
}

interface PaymentExternalLink {
  label: string
  href: string
}

interface PaymentContent {
  eyebrow: string
  lead: string
  intro: string
  methodsTitle: string
  methods: PaymentMethodEntry[]
  providerTitle: string
  providerIntro: string
  providerName: string
  providerAddressLines: string[]
  providerRegisterLabel: string
  providerRegisterValue: string
  providerVatLabel: string
  providerVatValue: string
  providerSupervision: string
  notesTitle: string
  notesParagraphs: string[]
  linksTitle: string
  links: PaymentExternalLink[]
}

const METHOD_ICONS: Record<string, string> = {
  visa: VisaIcon,
  mastercard: MastercardIcon,
  'apple-pay': ApplePayIcon,
  'google-pay': GooglePayIcon,
  paypal: PaypalIcon,
  klarna: KlarnaIcon,
}

const { t, tm } = useI18n()

const pageContent = computed(() => tm('footerPages.payment.content') as unknown as PaymentContent)

function iconFor(id: string): string | undefined {
  return METHOD_ICONS[id]
}
</script>

<template>
  <section class="bg-background text-foreground">
    <div class="mx-auto w-full max-w-[1100px] px-6 py-12 sm:px-8 lg:py-20">
      <header class="max-w-3xl space-y-4">
        <div
          class="inline-flex items-center gap-2 text-xs font-bold uppercase tracking-[0.16em] text-primary"
        >
          <CreditCard class="size-4" />
          <span>{{ pageContent.eyebrow }}</span>
        </div>

        <p class="text-sm font-bold uppercase tracking-[0.06em] text-primary">
          {{ pageContent.lead }}
        </p>

        <h1 class="text-4xl font-extrabold leading-tight text-foreground sm:text-5xl">
          {{ t('footerPages.payment.title') }}
        </h1>

        <p class="text-base leading-7 text-foreground-muted">
          {{ pageContent.intro }}
        </p>
      </header>

      <section class="mt-10" data-testid="payment-methods-section">
        <header class="mb-4">
          <h2 class="text-xs font-bold uppercase tracking-[0.14em] text-primary">
            {{ pageContent.methodsTitle }}
          </h2>
        </header>

        <div class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          <article
            v-for="method in pageContent.methods"
            :key="method.id"
            class="flex flex-col gap-4 rounded-lg border border-border bg-card p-5 shadow-sm"
            :data-testid="`payment-method-${method.id}`"
          >
            <div
              class="inline-flex h-11 w-28 items-center justify-center rounded-md border px-3 shadow-sm [background:var(--payment-badge)] [border-color:var(--payment-badge-border)]"
            >
              <img
                v-if="iconFor(method.id)"
                :src="iconFor(method.id)"
                :alt="method.name"
                class="h-6 w-auto"
              />
            </div>

            <div class="space-y-1">
              <h3 class="text-lg font-bold text-foreground">{{ method.name }}</h3>
              <p class="text-sm leading-6 text-foreground-muted">{{ method.description }}</p>
            </div>
          </article>
        </div>
      </section>

      <section class="mt-10 grid gap-4 lg:grid-cols-[1.05fr_0.95fr]">
        <article
          class="flex flex-col gap-5 rounded-lg border border-border bg-background-soft p-6 shadow-sm"
          data-testid="payment-provider-card"
        >
          <div class="flex items-start gap-4">
            <div
              class="inline-flex size-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary"
            >
              <Building2 class="size-5" />
            </div>
            <div class="space-y-1">
              <h2 class="text-xl font-extrabold leading-tight text-foreground">
                {{ pageContent.providerTitle }}
              </h2>
              <p class="text-sm leading-6 text-foreground-muted">
                {{ pageContent.providerIntro }}
              </p>
            </div>
          </div>

          <div class="rounded-lg border border-border bg-background p-5 space-y-4">
            <div>
              <p class="text-lg font-bold text-foreground">{{ pageContent.providerName }}</p>
              <address class="mt-1 space-y-0.5 text-sm leading-6 text-foreground-soft not-italic">
                <p v-for="line in pageContent.providerAddressLines" :key="line">{{ line }}</p>
              </address>
            </div>

            <dl class="grid gap-3 border-t border-border pt-4 sm:grid-cols-2">
              <div>
                <dt class="text-xs font-bold uppercase tracking-[0.1em] text-foreground-faint">
                  {{ pageContent.providerRegisterLabel }}
                </dt>
                <dd class="mt-1 text-sm font-semibold text-foreground-soft">
                  {{ pageContent.providerRegisterValue }}
                </dd>
              </div>
              <div>
                <dt class="text-xs font-bold uppercase tracking-[0.1em] text-foreground-faint">
                  {{ pageContent.providerVatLabel }}
                </dt>
                <dd class="mt-1 text-sm font-semibold text-foreground-soft">
                  {{ pageContent.providerVatValue }}
                </dd>
              </div>
            </dl>
          </div>

          <p class="text-sm leading-6 text-foreground-muted">
            {{ pageContent.providerSupervision }}
          </p>
        </article>

        <article
          class="flex flex-col justify-between gap-6 rounded-lg border border-border bg-background-soft p-6 shadow-sm"
          data-testid="payment-notes-card"
        >
          <div class="space-y-4">
            <div class="flex items-start gap-4">
              <div
                class="inline-flex size-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary"
              >
                <ShieldCheck class="size-5" />
              </div>
              <h2 class="pt-1.5 text-xl font-extrabold leading-tight text-foreground">
                {{ pageContent.notesTitle }}
              </h2>
            </div>

            <div class="space-y-3 text-sm leading-7 text-foreground-muted">
              <p v-for="paragraph in pageContent.notesParagraphs" :key="paragraph">
                {{ paragraph }}
              </p>
            </div>
          </div>

          <div class="space-y-3 border-t border-border pt-5">
            <h3 class="text-xs font-bold uppercase tracking-[0.12em] text-foreground-faint">
              {{ pageContent.linksTitle }}
            </h3>

            <ul class="space-y-2">
              <li v-for="link in pageContent.links" :key="link.href">
                <a
                  :href="link.href"
                  target="_blank"
                  rel="noopener noreferrer"
                  class="inline-flex items-center gap-2 text-sm font-semibold text-primary underline underline-offset-4 hover:opacity-80"
                >
                  <span>{{ link.label }}</span>
                  <ExternalLink class="size-3.5 shrink-0" />
                </a>
              </li>
            </ul>
          </div>
        </article>
      </section>
    </div>
  </section>
</template>
