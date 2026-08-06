<script setup lang="ts">
import AuthCard from '@/components/auth/AuthCard.vue'
import AuthHeader from '@/components/auth/AuthHeader.vue'
import AuthStatus from '@/components/auth/AuthStatus.vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useToast } from '@/composables/useToast'
import { INVALID_LINK_CODE, useAuthStore } from '@/stores/shared/auth'
import { Check, X } from 'lucide-vue-next'
import { computed, shallowRef } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'

const route = useRoute()
const authStore = useAuthStore()
const { t } = useI18n()
const { toast } = useToast()

const newPassword = shallowRef('')
const confirmPassword = shallowRef('')
const loading = shallowRef(false)
const passwordReset = shallowRef(false)

const email = computed(() => (typeof route.query.email === 'string' ? route.query.email : ''))
const token = computed(() => (typeof route.query.token === 'string' ? route.query.token : ''))
const hasResetLink = computed(() => Boolean(email.value && token.value))

const handleResetPassword = async () => {
  if (!hasResetLink.value) return

  if (newPassword.value !== confirmPassword.value) {
    toast({ title: t('auth.resetPassword.errors.passwordMismatch'), variant: 'destructive' })
    return
  }

  loading.value = true

  const result = await authStore.resetPassword(email.value, token.value, newPassword.value)

  loading.value = false

  if (result.success) {
    passwordReset.value = true
    return
  }

  // `400` covers both an invalid input and an invalid/expired link. Only the link case carries
  // the machine-readable `INVALID_LINK` code, so it gets localized copy; everything else falls
  // back to the backend's own English message.
  toast({
    title:
      result.error.code === INVALID_LINK_CODE
        ? t('auth.resetPassword.errors.invalidLink')
        : result.error.message || t('auth.resetPassword.errors.generic'),
    variant: 'destructive',
  })
}
</script>

<template>
  <AuthCard>
    <AuthStatus
      v-if="passwordReset"
      :icon="Check"
      :title="t('auth.resetPassword.success.title')"
      :message="t('auth.resetPassword.success.message')"
      :action-label="t('auth.resetPassword.success.loginLink')"
      action-to="/login"
    />

    <AuthStatus
      v-else-if="!hasResetLink"
      :icon="X"
      :title="t('auth.resetPassword.invalid.title')"
      :message="t('auth.resetPassword.invalid.message')"
      :action-label="t('auth.resetPassword.invalid.requestLink')"
      action-to="/forgot-password"
      action-variant="outline"
      variant="error"
    />

    <template v-else>
      <AuthHeader
        :title="t('auth.resetPassword.title')"
        :subtitle="t('auth.resetPassword.subtitle')"
      />

      <form @submit.prevent="handleResetPassword" class="space-y-6">
        <div class="flex flex-col gap-2">
          <Label for="newPassword">
            {{ t('auth.resetPassword.newPassword') }}
          </Label>
          <Input
            id="newPassword"
            v-model="newPassword"
            type="password"
            minlength="8"
            required
            autocomplete="new-password"
          />
        </div>

        <div class="flex flex-col gap-2">
          <Label for="confirmPassword">
            {{ t('auth.resetPassword.confirmPassword') }}
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
          {{ loading ? t('auth.resetPassword.submitting') : t('auth.resetPassword.submit') }}
        </Button>
      </form>
    </template>
  </AuthCard>
</template>
