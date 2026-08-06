<script setup lang="ts">
import AuthCard from '@/components/auth/AuthCard.vue'
import AuthHeader from '@/components/auth/AuthHeader.vue'
import AuthStatus from '@/components/auth/AuthStatus.vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useToast } from '@/composables/useToast'
import { useAuthStore } from '@/stores/shared/auth'
import { MailCheck } from 'lucide-vue-next'
import { shallowRef } from 'vue'
import { useI18n } from 'vue-i18n'

const authStore = useAuthStore()
const { t } = useI18n()
const { toast } = useToast()

const email = shallowRef('')
const loading = shallowRef(false)
const resetRequested = shallowRef(false)

const handleForgotPassword = async () => {
  loading.value = true

  const result = await authStore.forgotPassword(email.value)

  loading.value = false

  if (result.success) {
    resetRequested.value = true
    return
  }

  // The route is enumeration-safe and always answers `204`; only an invalid input reaches here.
  toast({
    title: result.error.message || t('auth.forgotPassword.errors.generic'),
    variant: 'destructive',
  })
}
</script>

<template>
  <AuthCard>
    <AuthStatus
      v-if="resetRequested"
      :icon="MailCheck"
      :title="t('auth.forgotPassword.success.title')"
      :message="t('auth.forgotPassword.success.message')"
      :action-label="t('auth.forgotPassword.success.loginLink')"
      action-to="/login"
      action-variant="outline"
    />

    <template v-else>
      <AuthHeader
        :title="t('auth.forgotPassword.title')"
        :subtitle="t('auth.forgotPassword.subtitle')"
      />

      <form @submit.prevent="handleForgotPassword" class="space-y-6">
        <div class="flex flex-col gap-2">
          <Label for="email">
            {{ t('auth.forgotPassword.email') }}
          </Label>
          <Input
            id="email"
            v-model="email"
            type="email"
            :placeholder="t('auth.forgotPassword.emailPlaceholder')"
            required
            autocomplete="email"
          />
        </div>

        <Button type="submit" size="lg" class="w-full" :disabled="loading">
          {{ loading ? t('auth.forgotPassword.submitting') : t('auth.forgotPassword.submit') }}
        </Button>

        <p class="text-center text-sm leading-6 text-muted-foreground sm:text-base">
          <RouterLink to="/login" class="text-primary hover:text-primary-hover hover:underline">
            {{ t('auth.forgotPassword.backToLogin') }}
          </RouterLink>
        </p>
      </form>
    </template>
  </AuthCard>
</template>
