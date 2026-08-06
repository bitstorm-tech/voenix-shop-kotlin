<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { AlertCircle, Loader2, RefreshCw } from 'lucide-vue-next'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Checkbox } from '@/components/ui/checkbox'
import AddressForm from '@/components/shop/AddressForm.vue'
import {
  MAIL_DELIVERY_FAILED_STATUS,
  useAuthStore,
  type AuthActionError,
} from '@/stores/shared/auth'
import { type Address, createEmptyAddress } from '@/stores/shop/checkout'
import { useCountriesStore } from '@/stores/shop/countries'
import { useToast } from '@/composables/useToast'

const { t } = useI18n()
const authStore = useAuthStore()
const countriesStore = useCountriesStore()
const { toast } = useToast()

// Address section
const shippingAddress = ref<Address>(createEmptyAddress())
const billingAddress = ref<Address>(createEmptyAddress())
const hasSeparateBilling = ref(false)
const addressLoading = ref(false)

// Change email section
const newEmail = ref('')
const emailPassword = ref('')
const emailLoading = ref(false)

// Change password section
const currentPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const passwordLoading = ref(false)

const countriesUnavailable = computed(
  () => !!countriesStore.error && countriesStore.countries.length === 0,
)
const isAddressSaveDisabled = computed(
  () => addressLoading.value || countriesStore.isLoading || countriesUnavailable.value,
)

onMounted(async () => {
  await countriesStore.fetchCountries()

  if (authStore.user) {
    if (authStore.user.shippingAddress) {
      shippingAddress.value = withSupportedCountry({ ...authStore.user.shippingAddress })
    }
    if (authStore.user.billingAddress) {
      billingAddress.value = withSupportedCountry({ ...authStore.user.billingAddress })
    }
    hasSeparateBilling.value = authStore.user.hasSeparateBillingAddress
  }
})

async function retryCountries() {
  await countriesStore.fetchCountries({ force: true })
  applySupportedCountries()
}

/** `PUT /api/auth/profile` only ever refuses with `400` validation or `401` no session. */
function profileErrorMessage(error: AuthActionError): string {
  return error.message || t('profile.errors.saveAddresses')
}

/**
 * `POST /api/auth/change-email` discriminates by status, not by a code
 * (`docs/dev/backend/account-package.md`). `502` is the retryable one: the confirmation token was
 * issued but its mail did not go out, and submitting the form again issues a fresh one.
 */
function changeEmailErrorMessage(error: AuthActionError): string {
  switch (error.status) {
    case 401:
      return t('profile.changeEmail.errors.wrongPassword')
    case 409:
      return t('profile.changeEmail.errors.emailTaken')
    case MAIL_DELIVERY_FAILED_STATUS:
      return t('profile.changeEmail.errors.mailDeliveryFailed')
    default:
      return error.message || t('profile.changeEmail.errors.generic')
  }
}

function changePasswordErrorMessage(error: AuthActionError): string {
  if (error.status === 401) {
    return t('profile.changePassword.errors.wrongPassword')
  }

  return error.message || t('profile.changePassword.errors.generic')
}

async function handleSaveAddresses() {
  addressLoading.value = true

  const result = await authStore.updateProfile({
    shippingAddress: shippingAddress.value,
    hasSeparateBillingAddress: hasSeparateBilling.value,
    billingAddress: hasSeparateBilling.value ? billingAddress.value : null,
  })

  addressLoading.value = false

  if (result.success) {
    toast({ title: t('profile.addressesSaved'), variant: 'success' })
    return
  }

  toast({ title: profileErrorMessage(result.error), variant: 'destructive' })
}

async function handleChangeEmail() {
  emailLoading.value = true

  const result = await authStore.changeEmail(newEmail.value, emailPassword.value)

  emailLoading.value = false

  if (result.success) {
    toast({ title: t('profile.changeEmail.success'), variant: 'success' })
    newEmail.value = ''
    emailPassword.value = ''
    return
  }

  // The form keeps its values so a `502` can be retried as it stands.
  toast({ title: changeEmailErrorMessage(result.error), variant: 'destructive' })
}

async function handleChangePassword() {
  passwordLoading.value = true

  if (newPassword.value !== confirmPassword.value) {
    toast({
      title: t('profile.changePassword.errors.passwordMismatch'),
      variant: 'destructive',
    })
    passwordLoading.value = false
    return
  }

  const result = await authStore.changePassword(currentPassword.value, newPassword.value)

  passwordLoading.value = false

  if (result.success) {
    toast({ title: t('profile.changePassword.success'), variant: 'success' })
    currentPassword.value = ''
    newPassword.value = ''
    confirmPassword.value = ''
    return
  }

  toast({ title: changePasswordErrorMessage(result.error), variant: 'destructive' })
}

function formatDate(dateString: string) {
  return new Date(dateString).toLocaleDateString('de-DE', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
  })
}

function applySupportedCountries() {
  shippingAddress.value = withSupportedCountry(shippingAddress.value)
  billingAddress.value = withSupportedCountry(billingAddress.value)
}

function withSupportedCountry(address: Address): Address {
  if (countriesStore.countries.length === 0) {
    return address
  }

  const country = countriesStore.resolveCountryCode(address.country)
  return address.country === country ? address : { ...address, country }
}
</script>

<template>
  <div class="mx-auto max-w-2xl space-y-8 pb-12">
    <div class="flex flex-col gap-2 sm:flex-row sm:items-baseline sm:justify-between">
      <h1 class="font-heading text-2xl font-bold tracking-tight sm:text-3xl">
        {{ t('profile.title') }}
      </h1>
      <p v-if="authStore.user?.createdAt" class="text-sm text-muted-foreground">
        {{ t('profile.memberSince') }} {{ formatDate(authStore.user.createdAt) }}
      </p>
    </div>

    <div class="space-y-8">
      <!-- Addresses Section -->
      <Card as="form" class="space-y-6 bg-card p-6" @submit.prevent="handleSaveAddresses">
        <!-- Shipping Address -->
        <div>
          <h2 class="mb-4 text-xl font-semibold">{{ t('profile.shippingAddress.title') }}</h2>
          <AddressForm
            v-model="shippingAddress"
            id-prefix="shipping"
            :country-options="countriesStore.countries"
            show-phone
          />
        </div>

        <!-- Billing Address -->
        <div>
          <h2 class="mb-4 text-xl font-semibold">{{ t('profile.billingAddress.title') }}</h2>
          <div class="mb-4 flex items-center gap-2">
            <Checkbox id="separate-billing" v-model="hasSeparateBilling" />
            <Label for="separate-billing" class="cursor-pointer">
              {{ t('profile.billingAddress.separateBilling') }}
            </Label>
          </div>
          <AddressForm
            v-if="hasSeparateBilling"
            v-model="billingAddress"
            id-prefix="billing"
            :country-options="countriesStore.countries"
            show-phone
          />
        </div>

        <Alert
          v-if="countriesUnavailable"
          variant="destructive"
          class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between"
        >
          <div class="flex gap-2">
            <AlertCircle class="mt-0.5 size-4 shrink-0" />
            <p class="font-medium">{{ t('profile.countriesUnavailable') }}</p>
          </div>
          <Button
            type="button"
            variant="outline"
            size="sm"
            :disabled="countriesStore.isLoading"
            class="self-start border-destructive/40 text-destructive hover:text-destructive sm:self-auto"
            @click="retryCountries"
          >
            <Loader2 v-if="countriesStore.isLoading" class="size-3.5 animate-spin" />
            <RefreshCw v-else class="size-3.5" />
            {{ t('profile.retryCountries') }}
          </Button>
        </Alert>

        <div class="border-t pt-4">
          <Button type="submit" :disabled="isAddressSaveDisabled">
            {{ addressLoading ? t('profile.savingAddresses') : t('profile.saveAddresses') }}
          </Button>
        </div>
      </Card>

      <!-- Change Email Section -->
      <Card as="form" class="space-y-6 bg-card p-6" @submit.prevent="handleChangeEmail">
        <h2 class="text-xl font-semibold">{{ t('profile.changeEmail.title') }}</h2>
        <div class="flex flex-col gap-2">
          <Label>{{ t('profile.changeEmail.currentEmail') }}</Label>
          <Input :model-value="authStore.user?.email" disabled />
        </div>

        <div class="flex flex-col gap-2">
          <Label for="new-email">{{ t('profile.changeEmail.newEmail') }}</Label>
          <Input
            id="new-email"
            v-model="newEmail"
            type="email"
            :placeholder="t('profile.changeEmail.newEmailPlaceholder')"
            required
          />
        </div>

        <div class="flex flex-col gap-2">
          <Label for="email-password">{{ t('profile.changeEmail.currentPassword') }}</Label>
          <Input id="email-password" v-model="emailPassword" type="password" required />
        </div>

        <div class="pt-4 border-t">
          <Button type="submit" :disabled="emailLoading">
            {{
              emailLoading ? t('profile.changeEmail.submitting') : t('profile.changeEmail.submit')
            }}
          </Button>
        </div>
      </Card>

      <!-- Change Password Section -->
      <Card as="form" class="space-y-6 bg-card p-6" @submit.prevent="handleChangePassword">
        <h2 class="text-xl font-semibold">{{ t('profile.changePassword.title') }}</h2>
        <div class="flex flex-col gap-2">
          <Label for="current-password">{{ t('profile.changePassword.currentPassword') }}</Label>
          <Input id="current-password" v-model="currentPassword" type="password" required />
        </div>

        <div class="flex flex-col gap-2">
          <Label for="new-password">{{ t('profile.changePassword.newPassword') }}</Label>
          <Input id="new-password" v-model="newPassword" type="password" minlength="8" required />
        </div>

        <div class="flex flex-col gap-2">
          <Label for="confirm-password">{{ t('profile.changePassword.confirmPassword') }}</Label>
          <Input id="confirm-password" v-model="confirmPassword" type="password" required />
        </div>

        <div class="pt-4 border-t">
          <Button type="submit" :disabled="passwordLoading">
            {{
              passwordLoading
                ? t('profile.changePassword.submitting')
                : t('profile.changePassword.submit')
            }}
          </Button>
        </div>
      </Card>
    </div>
  </div>
</template>
