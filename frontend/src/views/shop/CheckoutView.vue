<script setup lang="ts">
import { onMounted, computed, shallowRef } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ArrowLeft, CircleCheck, CreditCard, Loader2, Tag } from 'lucide-vue-next'
import { useCartStore } from '@/stores/shop/cart'
import { type Address, useCheckoutStore } from '@/stores/shop/checkout'
import { useCountriesStore } from '@/stores/shop/countries'
import { useAuthStore } from '@/stores/shared/auth'
import { checkoutErrorKeys } from '@/lib/checkoutErrors'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Checkbox } from '@/components/ui/checkbox'
import { Label } from '@/components/ui/label'
import AddressForm from '@/components/shop/AddressForm.vue'
import CountriesUnavailableAlert from '@/components/shop/CountriesUnavailableAlert.vue'
import { useToast } from '@/composables/useToast'

/** The JSON path the backend keys its shipping-country refusal by; it carries no `code`. */
const SHIPPING_COUNTRY_FIELD = 'shippingAddress.country'
/**
 * The JSON path the backend keys the missing phone number of a shirt order by. It is the *nested*
 * path, not a bare `phone`, and it carries no `code` either (`docs/dev/backend/checkout-package.md`).
 */
const SHIPPING_PHONE_FIELD = 'shippingAddress.phone'
const BILLING_COUNTRY_PATTERN = /^[A-Z]{2}$/

const { t } = useI18n()
const router = useRouter()
const cartStore = useCartStore()
const checkoutStore = useCheckoutStore()
const countriesStore = useCountriesStore()
const authStore = useAuthStore()
const { toast } = useToast()

const termsAccepted = shallowRef(false)
/** The inline phone message appears once the customer has tried to place the order, not before. */
const hasAttemptedSubmit = shallowRef(false)

onMounted(async () => {
  await Promise.all([cartStore.fetchCart(), countriesStore.fetchCountries()])
  if (cartStore.isEmpty) {
    router.replace({ name: 'cart' })
    return
  }

  // Pre-fill addresses from user profile if available
  const user = authStore.user
  if (user?.shippingAddress) {
    const s = user.shippingAddress
    const hasData = s.firstName || s.lastName || s.street || s.houseNumber
    if (hasData) {
      checkoutStore.shippingAddress = {
        ...s,
        email: checkoutStore.shippingAddress.email || user.email,
      }
    }
  }
  if (user?.hasSeparateBillingAddress && user.billingAddress) {
    checkoutStore.sameAsShipping = false
    checkoutStore.billingAddress = { ...user.billingAddress }
  }

  // Only the shipping country is resolved against the list; the billing country is free text.
  checkoutStore.shippingAddress = withShippableCountry(checkoutStore.shippingAddress)
})

const isInitialLoading = computed(
  () =>
    (cartStore.isLoading && cartStore.isEmpty) ||
    (countriesStore.isLoading && countriesStore.countries.length === 0),
)

/**
 * A t-shirt is shipped by the print-on-demand partner, who needs a phone number to deliver. The
 * backend refuses a checkout of such a cart without one, so the form demands it before it submits
 * and stays optional for a cart of mugs alone (issue #205, decision D2).
 */
const isPhoneRequired = computed(() => cartStore.hasTshirtItem)
const isPhoneMissing = computed(
  () => isPhoneRequired.value && checkoutStore.shippingAddress.phone.trim() === '',
)

const isFormValid = computed(() => {
  if (isPhoneMissing.value) {
    return false
  }

  const s = checkoutStore.shippingAddress
  if (
    !s.firstName ||
    !s.lastName ||
    !s.street ||
    !s.houseNumber ||
    !s.city ||
    !s.postalCode ||
    !s.email ||
    !countriesStore.isSupportedCountry(s.country)
  ) {
    return false
  }
  if (!checkoutStore.sameAsShipping) {
    const b = checkoutStore.billingAddress
    if (
      !b.firstName ||
      !b.lastName ||
      !b.street ||
      !b.houseNumber ||
      !b.city ||
      !b.postalCode ||
      // Shape only: an invoice may go anywhere, so the shippable list does not apply here.
      !BILLING_COUNTRY_PATTERN.test(b.country)
    ) {
      return false
    }
  }
  return true
})

const hasBillingCountryShape = computed(
  () =>
    checkoutStore.sameAsShipping ||
    BILLING_COUNTRY_PATTERN.test(checkoutStore.billingAddress.country),
)

/** Without a shippable list there is no shipping country to submit, so the form stays blocked. */
const areCountriesUnavailable = computed(
  () => !countriesStore.isLoading && countriesStore.countries.length === 0,
)

const isSubmitDisabled = computed(
  () => checkoutStore.isSubmitting || countriesStore.isLoading || areCountriesUnavailable.value,
)

/**
 * The backend answers an unshippable destination as a field error on this path and deliberately
 * without a `code` (`docs/dev/backend/checkout-package.md`). The path is the discriminator; the
 * text is localized because the server message is English only.
 */
const shippingCountryError = computed(() =>
  checkoutStore.fieldErrors[SHIPPING_COUNTRY_FIELD]
    ? t('checkout.errors.shippingCountryUnavailable')
    : null,
)

/**
 * The phone message, from whichever of the two says it first: the browser, once the customer has
 * tried to submit a shirt cart without a number, or the backend, which keys its own refusal by the
 * nested path. Both mean the same thing, so both read the same localized sentence.
 */
const phoneError = computed(() => {
  if (checkoutStore.fieldErrors[SHIPPING_PHONE_FIELD]) {
    return t('checkout.errors.phoneRequiredForTshirt')
  }

  return hasAttemptedSubmit.value && isPhoneMissing.value
    ? t('checkout.errors.phoneRequiredForTshirt')
    : null
})

/**
 * The message of the last refused submission. Codes are localized from the error table of
 * `docs/dev/backend/checkout-package.md`; the unshippable country carries no code and lands on its
 * own field instead, so it shows up here only as the summary of a failed attempt.
 */
const submitError = computed(() => {
  if (checkoutStore.errorCode) {
    return t(checkoutErrorKeys[checkoutStore.errorCode])
  }

  if (shippingCountryError.value) {
    return shippingCountryError.value
  }

  if (checkoutStore.fieldErrors[SHIPPING_PHONE_FIELD]) {
    return t('checkout.errors.phoneRequiredForTshirt')
  }

  return checkoutStore.error
})

const isZeroTotal = computed(() => cartStore.totalPrice === 0)
const paymentHint = computed(() =>
  t(isZeroTotal.value ? 'checkout.paymentNotRequiredHint' : 'checkout.paymentHint'),
)
const submitLabel = computed(() => {
  if (checkoutStore.isSubmitting) {
    return t('checkout.submitting')
  }

  return t(isZeroTotal.value ? 'checkout.submitFree' : 'checkout.submit')
})

function withShippableCountry(address: Address): Address {
  const country = countriesStore.resolveCountryCode(address.country)
  return address.country === country ? address : { ...address, country }
}

/** A new country or number makes the server's refusal stale, so the inline message goes with it. */
function updateShippingAddress(address: Address) {
  if (address.country !== checkoutStore.shippingAddress.country) {
    checkoutStore.clearFieldError(SHIPPING_COUNTRY_FIELD)
  }

  if (address.phone !== checkoutStore.shippingAddress.phone) {
    checkoutStore.clearFieldError(SHIPPING_PHONE_FIELD)
  }

  checkoutStore.shippingAddress = address
}

async function retryCountries() {
  await countriesStore.fetchCountries({ force: true })
  checkoutStore.shippingAddress = withShippableCountry(checkoutStore.shippingAddress)
}

async function handleSubmit() {
  hasAttemptedSubmit.value = true

  if (areCountriesUnavailable.value) {
    toast({ title: t('checkout.errors.countriesUnavailable'), variant: 'destructive' })
    return
  }

  if (!hasBillingCountryShape.value) {
    toast({ title: t('checkout.errors.invalidBillingCountry'), variant: 'destructive' })
    return
  }

  // Named separately from the other required fields, because "fill in all required fields" would
  // not explain why a field that was optional a moment ago now is not.
  if (isPhoneMissing.value) {
    toast({ title: t('checkout.errors.phoneRequiredForTshirt'), variant: 'destructive' })
    return
  }

  if (!isFormValid.value) {
    toast({ title: t('checkout.errors.requiredFields'), variant: 'destructive' })
    return
  }

  const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
  if (!emailRegex.test(checkoutStore.shippingAddress.email)) {
    toast({ title: t('checkout.errors.invalidEmail'), variant: 'destructive' })
    return
  }

  if (!termsAccepted.value) {
    toast({ title: t('checkout.errors.termsNotAccepted'), variant: 'destructive' })
    return
  }

  try {
    const result = await checkoutStore.submitCheckout()
    // A free order is already confirmed and has nothing to pay: no provider, straight to the
    // confirmation. Any other order is sent to the URL the payment provider answered with.
    if (result.checkoutUrl) {
      window.location.href = result.checkoutUrl
      return
    }

    // The Mollie path leaves the SPA entirely, so its stale cart state dies with the page. The free
    // path stays inside the running app with `ShopLayout` mounted, so the checked-out cart has to be
    // re-read or the header badge keeps counting lines that are now on an order.
    await cartStore.fetchCart()
    await router.push({
      name: 'order-confirmation',
      query: { orderId: String(result.orderId) },
    })
  } catch {
    toast({ title: submitError.value ?? t('checkout.errors.generic'), variant: 'destructive' })
  }
}
</script>

<template>
  <div class="space-y-6 pb-12">
    <!-- Header -->
    <div>
      <h1 class="font-heading text-2xl font-bold tracking-tight sm:text-3xl">
        {{ t('checkout.title') }}
      </h1>
    </div>

    <!-- Loading state -->
    <div v-if="isInitialLoading" class="flex justify-center py-20">
      <Loader2 class="size-8 animate-spin text-muted-foreground" />
    </div>

    <template v-else>
      <div class="grid grid-cols-1 gap-8 lg:grid-cols-3">
        <!-- Left column: forms -->
        <div class="space-y-6 lg:col-span-2">
          <!-- Shipping Address -->
          <Card class="bg-muted/30 p-6">
            <h2 class="font-heading text-lg font-semibold">
              {{ t('checkout.shippingAddress') }}
            </h2>
            <div class="mt-4 space-y-4">
              <CountriesUnavailableAlert
                v-if="areCountriesUnavailable"
                :message="t('checkout.errors.countriesUnavailable')"
                :is-retrying="countriesStore.isLoading"
                @retry="retryCountries"
              />
              <AddressForm
                :model-value="checkoutStore.shippingAddress"
                id-prefix="shipping"
                :country-options="countriesStore.countries"
                country-mode="select"
                :country-error="shippingCountryError"
                show-email
                show-phone
                :phone-required="isPhoneRequired"
                :phone-error="phoneError"
                @update:model-value="updateShippingAddress"
              />
            </div>
          </Card>

          <!-- Billing Address -->
          <Card class="bg-muted/30 p-6">
            <h2 class="font-heading text-lg font-semibold">
              {{ t('checkout.billingAddress') }}
            </h2>
            <div class="mt-4 flex items-center gap-2">
              <Checkbox id="sameAsShipping" v-model="checkoutStore.sameAsShipping" />
              <Label for="sameAsShipping" class="cursor-pointer">
                {{ t('checkout.sameAsShipping') }}
              </Label>
            </div>
            <div v-if="!checkoutStore.sameAsShipping" class="mt-4">
              <AddressForm
                v-model="checkoutStore.billingAddress"
                id-prefix="billing"
                :country-options="countriesStore.countries"
                country-mode="text"
              />
            </div>
          </Card>

          <!-- Payment -->
          <Card class="bg-muted/30 p-6">
            <h2 class="font-heading text-lg font-semibold">
              {{ t('checkout.payment') }}
            </h2>
            <div class="mt-4 flex items-start gap-3 text-sm text-muted-foreground">
              <CircleCheck v-if="isZeroTotal" class="mt-0.5 size-5 shrink-0 text-success" />
              <CreditCard v-else class="mt-0.5 size-5 shrink-0" />
              <p>{{ paymentHint }}</p>
            </div>
          </Card>
        </div>

        <!-- Right column: order summary -->
        <div class="lg:col-span-1">
          <Card class="sticky top-6 bg-muted/30 p-6">
            <h2 class="font-heading text-lg font-semibold">
              {{ t('checkout.orderSummary') }}
            </h2>

            <div
              v-if="cartStore.appliedPromotion"
              class="mt-4 flex min-w-0 items-start gap-2 rounded-lg border border-success/30 bg-success/5 p-3"
              data-testid="checkout-applied-promotion"
            >
              <Tag class="mt-0.5 size-4 shrink-0 text-success" />
              <div class="min-w-0">
                <p class="truncate text-sm font-medium">
                  {{ cartStore.appliedPromotion.name }}
                </p>
                <p class="text-xs text-muted-foreground">
                  {{ t('cart.promotion.applied') }}:
                  <span class="font-mono font-medium">
                    {{ cartStore.appliedPromotion.promotionCode }}
                  </span>
                </p>
              </div>
            </div>

            <!-- Item list -->
            <ul class="mt-4 divide-y divide-border text-sm">
              <li
                v-for="item in cartStore.items"
                :key="item.id"
                class="flex justify-between gap-2 py-3 first:pt-0"
              >
                <div class="min-w-0">
                  <p class="truncate font-medium">{{ item.articleName }}</p>
                  <p class="text-muted-foreground">
                    {{ item.variantName }} &times; {{ item.quantity }}
                  </p>
                </div>
                <span class="shrink-0 font-medium tabular-nums">
                  {{ cartStore.formatPrice((item.price + item.promptPrice) * item.quantity) }}
                </span>
              </li>
            </ul>

            <!-- Totals -->
            <dl class="mt-4 space-y-3 border-t border-border pt-4 text-sm">
              <div class="flex justify-between">
                <dt class="text-muted-foreground">{{ t('cart.subtotal') }}</dt>
                <dd class="font-medium tabular-nums">
                  {{ cartStore.formatPrice(cartStore.subtotal) }}
                </dd>
              </div>
              <div class="flex justify-between">
                <dt class="text-muted-foreground">{{ t('cart.shipping') }}</dt>
                <dd class="font-medium tabular-nums">
                  <span v-if="cartStore.shippingCost === 0" class="text-success">
                    {{ t('cart.shippingFree') }}
                  </span>
                  <span v-else>{{ cartStore.formatPrice(cartStore.shippingCost) }}</span>
                </dd>
              </div>
              <div v-if="cartStore.discountAmount > 0" class="flex justify-between text-success">
                <dt>{{ t('cart.discount') }}</dt>
                <dd class="font-medium tabular-nums">
                  -{{ cartStore.formatPrice(cartStore.discountAmount) }}
                </dd>
              </div>
              <hr class="border-border" />
              <div class="flex justify-between text-base font-semibold">
                <dt>{{ t('cart.total') }}</dt>
                <dd class="tabular-nums">{{ cartStore.formatPrice(cartStore.totalPrice) }}</dd>
              </div>
            </dl>

            <div class="mt-6 flex items-start gap-2">
              <Checkbox id="termsAccepted" v-model="termsAccepted" class="mt-0.5" />
              <Label for="termsAccepted" class="cursor-pointer text-sm">
                <i18n-t keypath="checkout.termsLabel" tag="span">
                  <template #link>
                    <a
                      href="https://voenix.shop/pdf/AGBs.pdf"
                      target="_blank"
                      class="underline hover:text-primary"
                      @click.stop
                      >{{ t('checkout.termsLinkText') }}</a
                    >
                  </template>
                </i18n-t>
              </Label>
            </div>

            <!--
              The refusal stays visible next to the button that repeats the attempt. That matters
              most for `PAYMENT_NOT_STARTED`: the cart is still there, so trying again is the offer.
            -->
            <Alert
              v-if="submitError"
              variant="destructive"
              class="mt-4 p-3 text-sm"
              data-testid="checkout-submit-error"
            >
              {{ submitError }}
            </Alert>

            <Button
              class="mt-4 w-full"
              size="lg"
              :disabled="isSubmitDisabled"
              @click="handleSubmit"
            >
              <Loader2 v-if="checkoutStore.isSubmitting" class="size-4 animate-spin" />
              {{ submitLabel }}
            </Button>
            <Button as-child variant="ghost" class="mt-2 w-full text-muted-foreground">
              <router-link :to="{ name: 'cart' }">
                <ArrowLeft class="size-4" />
                {{ t('checkout.backToCart') }}
              </router-link>
            </Button>
          </Card>
        </div>
      </div>
    </template>
  </div>
</template>
