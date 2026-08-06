<script setup lang="ts">
import AuthCard from '@/components/auth/AuthCard.vue'
import AuthHeader from '@/components/auth/AuthHeader.vue'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useAuthStore } from '@/stores/shared/auth'
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
const resendLoading = shallowRef(false)
const resendSuccess = shallowRef(false)

const handleLogin = async () => {
  emailNotConfirmed.value = false
  resendSuccess.value = false
  loading.value = true

  try {
    const result = await authStore.login(email.value, password.value)

    if (result.success) {
      // Redirect to intended page or the shared post-login landing page.
      const redirect = route.query.redirect as string
      if (redirect) {
        router.push(redirect)
      } else {
        router.push(getDefaultAuthenticatedRedirect())
      }
    } else if (result.code === 'EMAIL_NOT_CONFIRMED') {
      emailNotConfirmed.value = true
      toast({ title: t('auth.login.errors.emailNotConfirmed'), variant: 'destructive' })
    } else {
      toast({
        title: result.message || t('auth.login.errors.invalid'),
        variant: 'destructive',
      })
    }
  } catch (err) {
    toast({ title: t('auth.login.errors.generic'), variant: 'destructive' })
    console.error('Login error:', err)
  } finally {
    loading.value = false
  }
}

const handleResendConfirmation = async () => {
  resendLoading.value = true
  resendSuccess.value = false

  const result = await authStore.resendConfirmation(email.value)

  resendLoading.value = false

  if (result.success) {
    resendSuccess.value = true
    toast({ title: t('auth.login.resendSuccess'), variant: 'success' })
    return
  }

  toast({
    title: result.message || t('auth.login.errors.generic'),
    variant: 'destructive',
  })
}
</script>

<template>
  <AuthCard>
    <AuthHeader :title="t('auth.login.title')" :subtitle="t('auth.login.subtitle')" />

    <form @submit.prevent="handleLogin" class="space-y-6">
      <Alert v-if="emailNotConfirmed && !resendSuccess" variant="destructive" class="space-y-3">
        <p class="m-0 leading-5">
          {{ t('auth.login.errors.emailNotConfirmed') }}
        </p>
        <Button
          type="button"
          variant="outline"
          size="sm"
          class="w-full sm:w-auto"
          :disabled="resendLoading"
          @click="handleResendConfirmation"
        >
          {{ t('auth.login.resendConfirmation') }}
        </Button>
      </Alert>

      <Alert
        v-else-if="resendSuccess"
        variant="info"
        class="border-success/20 bg-success-surface text-success"
      >
        <p class="m-0 leading-5">
          {{ t('auth.login.resendSuccess') }}
        </p>
      </Alert>

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
