<script setup lang="ts">
import AuthCard from '@/components/auth/AuthCard.vue'
import AuthHeader from '@/components/auth/AuthHeader.vue'
import ResendConfirmationAlert from '@/components/auth/ResendConfirmationAlert.vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useAuthStore, type AuthActionError } from '@/stores/shared/auth'
import { shallowRef } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useToast } from '@/composables/useToast'
import { getDefaultAuthenticatedRedirect } from '@/router/authRedirect'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()
const { t } = useI18n()
const { toast } = useToast()

const email = shallowRef('')
const password = shallowRef('')
const loading = shallowRef(false)
const emailNotConfirmed = shallowRef(false)

/**
 * `POST /api/auth/login` carries no machine-readable code; the status is the discriminator
 * (`docs/dev/backend/packages/account-package.md`). `401` is deliberately uniform for an unknown address
 * and a wrong password, so both share one message.
 */
const loginErrorMessage = (error: AuthActionError): string => {
  switch (error.status) {
    case 401:
      return t('auth.login.errors.invalid')
    case 403:
      return t('auth.login.errors.emailNotConfirmed')
    case 429:
      return t('auth.login.errors.lockedOut')
    default:
      return error.message || t('auth.login.errors.generic')
  }
}

const handleLogin = async () => {
  emailNotConfirmed.value = false
  loading.value = true

  const result = await authStore.login(email.value, password.value)

  loading.value = false

  if (result.success) {
    // Redirect to intended page or the shared post-login landing page.
    const redirect = route.query.redirect as string
    router.push(redirect || getDefaultAuthenticatedRedirect())
    return
  }

  emailNotConfirmed.value = result.error.status === 403
  toast({ title: loginErrorMessage(result.error), variant: 'destructive' })
}
</script>

<template>
  <AuthCard>
    <AuthHeader :title="t('auth.login.title')" :subtitle="t('auth.login.subtitle')" />

    <form @submit.prevent="handleLogin" class="space-y-6">
      <ResendConfirmationAlert
        v-if="emailNotConfirmed"
        :email="email"
        :message="t('auth.login.errors.emailNotConfirmed')"
      />

      <div class="flex flex-col gap-2">
        <Label for="email">
          {{ t('auth.login.email') }}
        </Label>
        <Input
          id="email"
          v-model="email"
          type="email"
          :placeholder="t('auth.login.emailPlaceholder')"
          required
          autocomplete="email"
        />
      </div>

      <div class="flex flex-col gap-2">
        <div class="flex items-center justify-between gap-4">
          <Label for="password">
            {{ t('auth.login.password') }}
          </Label>
          <RouterLink
            to="/forgot-password"
            class="text-sm font-medium text-primary hover:text-primary-hover hover:underline"
          >
            {{ t('auth.login.forgotPasswordLink') }}
          </RouterLink>
        </div>
        <Input
          id="password"
          v-model="password"
          type="password"
          required
          autocomplete="current-password"
        />
      </div>

      <Button type="submit" size="lg" class="w-full" :disabled="loading">
        {{ loading ? t('auth.login.submitting') : t('auth.login.submit') }}
      </Button>

      <p class="text-center text-sm leading-6 text-muted-foreground sm:text-base">
        {{ t('auth.login.noAccount') }}
        <RouterLink to="/register" class="text-primary hover:text-primary-hover hover:underline">
          {{ t('auth.login.registerLink') }}
        </RouterLink>
      </p>
    </form>
  </AuthCard>
</template>
