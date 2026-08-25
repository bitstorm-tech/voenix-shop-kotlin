<script setup lang="ts">
import AuthCard from '@/components/auth/AuthCard.vue'
import AuthHeader from '@/components/auth/AuthHeader.vue'
import AuthStatus from '@/components/auth/AuthStatus.vue'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { PasswordInput } from '@/components/ui/password-input'
import { useToast } from '@/composables/useToast'
import { INVALID_LINK_CODE, useAuthStore } from '@/stores/shared/auth'
import { Check, X } from 'lucide-vue-next'
import { computed, shallowRef } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'

/**
 * The page an invited supplier login lands on. It is the reset page's twin — same `?email=&token=`
 * link, same `POST /api/auth/reset-password` call — with the one difference that matters: nobody
 * asked for this mail, so the copy invites instead of confirming a request.
 */
const route = useRoute()
const authStore = useAuthStore()
const { t } = useI18n()
const { toast } = useToast()

const newPassword = shallowRef('')
const confirmPassword = shallowRef('')
const loading = shallowRef(false)
const passwordSet = shallowRef(false)

const email = computed(() => (typeof route.query.email === 'string' ? route.query.email : ''))
const token = computed(() => (typeof route.query.token === 'string' ? route.query.token : ''))
const hasInvitationLink = computed(() => Boolean(email.value && token.value))

const handleSetPassword = async () => {
  if (!hasInvitationLink.value) return

  if (newPassword.value !== confirmPassword.value) {
    toast({ title: t('auth.setPassword.errors.passwordMismatch'), variant: 'destructive' })
    return
  }

  loading.value = true

  const result = await authStore.resetPassword(email.value, token.value, newPassword.value)

  loading.value = false

  if (result.success) {
    passwordSet.value = true
    return
  }

  // Same discriminator as the reset page: only an invalid or expired link carries the
  // machine-readable `INVALID_LINK` code, so everything else falls back to the backend's message.
  toast({
    title:
      result.error.code === INVALID_LINK_CODE
        ? t('auth.setPassword.errors.invalidLink')
        : result.error.message || t('auth.setPassword.errors.generic'),
    variant: 'destructive',
  })
}
</script>

<template>
  <AuthCard>
    <AuthStatus
      v-if="passwordSet"
      :icon="Check"
      :title="t('auth.setPassword.success.title')"
      :message="t('auth.setPassword.success.message')"
      :action-label="t('auth.setPassword.success.loginLink')"
      action-to="/login"
    />

    <AuthStatus
      v-else-if="!hasInvitationLink"
      :icon="X"
      :title="t('auth.setPassword.invalid.title')"
      :message="t('auth.setPassword.invalid.message')"
      :action-label="t('auth.setPassword.invalid.requestLink')"
      action-to="/forgot-password"
      action-variant="outline"
      variant="error"
    />

    <template v-else>
      <AuthHeader :title="t('auth.setPassword.title')" :subtitle="t('auth.setPassword.subtitle')" />

      <form @submit.prevent="handleSetPassword" class="space-y-6">
        <div class="flex flex-col gap-2">
          <Label for="newPassword">
            {{ t('auth.setPassword.newPassword') }}
          </Label>
          <PasswordInput
            id="newPassword"
            v-model="newPassword"
            minlength="8"
            required
            autocomplete="new-password"
            :label="t('common.showPassword')"
          />
        </div>

        <div class="flex flex-col gap-2">
          <Label for="confirmPassword">
            {{ t('auth.setPassword.confirmPassword') }}
          </Label>
          <PasswordInput
            id="confirmPassword"
            v-model="confirmPassword"
            required
            autocomplete="new-password"
            :label="t('common.showPassword')"
          />
        </div>

        <Button type="submit" size="lg" class="w-full" :disabled="loading">
          {{ loading ? t('auth.setPassword.submitting') : t('auth.setPassword.submit') }}
        </Button>
      </form>
    </template>
  </AuthCard>
</template>
