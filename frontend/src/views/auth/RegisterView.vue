<script setup lang="ts">
import AuthCard from '@/components/auth/AuthCard.vue'
import AuthHeader from '@/components/auth/AuthHeader.vue'
import AuthStatus from '@/components/auth/AuthStatus.vue'
import ResendConfirmationAlert from '@/components/auth/ResendConfirmationAlert.vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  MAIL_DELIVERY_FAILED_STATUS,
  useAuthStore,
  type AuthActionError,
} from '@/stores/shared/auth'
import { MailCheck } from 'lucide-vue-next'
import { shallowRef } from 'vue'
import { useI18n } from 'vue-i18n'
import { useToast } from '@/composables/useToast'

const authStore = useAuthStore()
const { t } = useI18n()
const { toast } = useToast()

const email = shallowRef('')
const password = shallowRef('')
const confirmPassword = shallowRef('')
const loading = shallowRef(false)
const registered = shallowRef(false)
const mailDeliveryFailed = shallowRef(false)

const registerErrorMessage = (error: AuthActionError): string => {
  switch (error.status) {
    case 409:
      return t('auth.register.errors.emailTaken')
    case MAIL_DELIVERY_FAILED_STATUS:
      return t('auth.register.errors.mailDeliveryFailed')
    default:
      return error.message || t('auth.register.errors.generic')
  }
}

const handleRegister = async () => {
  // Validate password match
  if (password.value !== confirmPassword.value) {
    toast({ title: t('auth.register.errors.passwordMismatch'), variant: 'destructive' })
    return
  }

  mailDeliveryFailed.value = false
  loading.value = true

  const result = await authStore.register(email.value, password.value)

  loading.value = false

  if (result.success) {
    registered.value = true
    return
  }

  // `502` means the account exists but its confirmation mail did not go out. Registering again
  // would only answer `409`, so the retry path is a resend — not another registration.
  mailDeliveryFailed.value = result.error.status === MAIL_DELIVERY_FAILED_STATUS
  toast({ title: registerErrorMessage(result.error), variant: 'destructive' })
}
</script>

<template>
  <AuthCard>
    <!-- Success state: check your email -->
    <AuthStatus
      v-if="registered"
      :icon="MailCheck"
      :title="t('auth.register.success.title')"
      :message="t('auth.register.success.message')"
      :action-label="t('auth.register.success.loginLink')"
      action-to="/login"
      action-variant="outline"
    />

    <!-- Registration form -->
    <template v-else>
      <AuthHeader :title="t('auth.register.title')" :subtitle="t('auth.register.subtitle')" />

      <form @submit.prevent="handleRegister" class="space-y-6">
        <ResendConfirmationAlert
          v-if="mailDeliveryFailed"
          :email="email"
          :message="t('auth.register.errors.mailDeliveryFailed')"
        />

        <div class="flex flex-col gap-2">
          <Label for="email">
            {{ t('auth.register.email') }}
          </Label>
          <Input
            id="email"
            v-model="email"
            type="email"
            :placeholder="t('auth.register.emailPlaceholder')"
            required
            autocomplete="email"
          />
        </div>

        <div class="flex flex-col gap-2">
          <Label for="password">
            {{ t('auth.register.password') }}
          </Label>
          <Input
            id="password"
            v-model="password"
            type="password"
            required
            autocomplete="new-password"
          />
        </div>

        <div class="flex flex-col gap-2">
          <Label for="confirmPassword">
            {{ t('auth.register.confirmPassword') }}
          </Label>
          <Input
            id="confirmPassword"
            v-model="confirmPassword"
            type="password"
            required
            autocomplete="new-password"
          />
        </div>

        <Button type="submit" size="lg" class="w-full" :disabled="loading">
          {{ loading ? t('auth.register.submitting') : t('auth.register.submit') }}
        </Button>

        <p class="text-center text-sm leading-6 text-muted-foreground sm:text-base">
          {{ t('auth.register.hasAccount') }}
          <RouterLink to="/login" class="text-primary hover:text-primary-hover hover:underline">
            {{ t('auth.register.loginLink') }}
          </RouterLink>
        </p>
      </form>
    </template>
  </AuthCard>
</template>
