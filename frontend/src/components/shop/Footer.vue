<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { useI18n } from 'vue-i18n'

// Payment icons
import VisaIcon from '@/assets/images/payments/visa.svg'
import MastercardIcon from '@/assets/images/payments/mastercard.svg'
import ApplePayIcon from '@/assets/images/payments/apple-pay.svg'
import PaypalIcon from '@/assets/images/payments/paypal.svg'
import AmexIcon from '@/assets/images/payments/amex.svg'
import KlarnaIcon from '@/assets/images/payments/klarna.svg'

// Shipping icon
import DhlLogo from '@/assets/images/dhl-logo.svg'

const { t } = useI18n()
const currentYear = new Date().getFullYear()

type FooterLink = {
  routeName: string
  labelKey: string
}

type PaymentMethod = {
  alt: string
  src: string
}

const quickLinks: FooterLink[] = [
  { routeName: 'about', labelKey: 'footer.quickAccess.about' },
  { routeName: 'faq', labelKey: 'footer.quickAccess.faq' },
  { routeName: 'contact', labelKey: 'footer.quickAccess.contact' },
  { routeName: 'support', labelKey: 'footer.quickAccess.support' },
]

const legalLinks: FooterLink[] = [
  { routeName: 'privacy', labelKey: 'footer.legal.privacy' },
  { routeName: 'terms', labelKey: 'footer.legal.terms' },
  { routeName: 'shipping', labelKey: 'footer.legal.shipping' },
  { routeName: 'returns', labelKey: 'footer.legal.returns' },
  { routeName: 'payment', labelKey: 'footer.legal.payment' },
  { routeName: 'imprint', labelKey: 'footer.legal.imprint' },
]

const paymentMethods: PaymentMethod[] = [
  { alt: 'Visa', src: VisaIcon },
  { alt: 'Mastercard', src: MastercardIcon },
  { alt: 'Apple Pay', src: ApplePayIcon },
  { alt: 'PayPal', src: PaypalIcon },
  { alt: 'American Express', src: AmexIcon },
  { alt: 'Klarna', src: KlarnaIcon },
]
</script>

<template>
  <footer class="bg-surface-inverse text-[color:var(--surface-inverse-foreground-muted)]">
    <!-- Main Footer Content -->
    <div class="mx-auto max-w-7xl px-8 py-12">
      <div class="grid grid-cols-1 gap-8 sm:grid-cols-2 lg:grid-cols-4">
        <!-- Brand Column -->
        <div>
          <h3 class="text-surface-inverse-foreground mb-4 text-lg font-bold">
            {{ t('footer.brand.name') }}
          </h3>
          <p class="mb-4 text-sm leading-relaxed">
            {{ t('footer.brand.description') }}
          </p>
          <p class="text-sm">
            {{ t('footer.brand.contact') }}
            <a href="mailto:write-us@voenix.shop" class="hover:text-surface-inverse-foreground">
              write-us@voenix.shop
            </a>
          </p>
        </div>

        <!-- Quick Access Column -->
        <div>
          <h4
            class="text-surface-inverse-foreground mb-4 text-sm font-semibold uppercase tracking-wider"
          >
            {{ t('footer.quickAccess.title') }}
          </h4>
          <nav class="flex flex-col space-y-3">
            <RouterLink
              v-for="link in quickLinks"
              :key="link.routeName"
              :to="{ name: link.routeName }"
              class="text-sm hover:text-surface-inverse-foreground"
            >
              {{ t(link.labelKey) }}
            </RouterLink>
          </nav>
        </div>

        <!-- Legal Column -->
        <div>
          <h4
            class="text-surface-inverse-foreground mb-4 text-sm font-semibold uppercase tracking-wider"
          >
            {{ t('footer.legal.title') }}
          </h4>
          <nav class="flex flex-col space-y-3">
            <RouterLink
              v-for="link in legalLinks"
              :key="link.routeName"
              :to="{ name: link.routeName }"
              class="text-sm hover:text-surface-inverse-foreground"
            >
              {{ t(link.labelKey) }}
            </RouterLink>
          </nav>
        </div>

        <!-- Trust & Security Column -->
        <div>
          <h4
            class="text-surface-inverse-foreground mb-4 text-sm font-semibold uppercase tracking-wider"
          >
            {{ t('footer.trust.title') }}
          </h4>

          <!-- Payment Methods -->
          <p class="mb-3 text-sm">{{ t('footer.trust.paymentMethods') }}</p>
          <div class="mb-6 flex flex-wrap items-center gap-2">
            <div
              v-for="paymentMethod in paymentMethods"
              :key="paymentMethod.alt"
              class="flex h-10 min-w-28 items-center justify-center rounded-md border px-3 shadow-sm [background:var(--payment-badge)] [border-color:var(--payment-badge-border)]"
            >
              <img :src="paymentMethod.src" :alt="paymentMethod.alt" class="h-6 w-auto" />
            </div>
          </div>

          <!-- Shipping -->
          <p class="mb-3 text-sm">{{ t('footer.trust.shipping') }}</p>
          <div class="flex items-center">
            <img :src="DhlLogo" alt="DHL" class="h-8 w-auto" />
          </div>
        </div>
      </div>
    </div>

    <!-- Copyright Bar -->
    <div class="border-t [border-color:var(--surface-inverse-border)]">
      <div class="mx-auto max-w-7xl px-8 py-6 text-center text-sm">
        {{ t('footer.copyright', { year: currentYear }) }}
      </div>
    </div>
  </footer>
</template>
