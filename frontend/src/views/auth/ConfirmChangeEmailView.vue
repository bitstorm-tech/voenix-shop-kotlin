<script setup lang="ts">
import AuthCard from '@/components/auth/AuthCard.vue'
import AuthStatus from '@/components/auth/AuthStatus.vue'
import { useAuthStore } from '@/stores/shared/auth'
import { Check, Loader2, X } from 'lucide-vue-next'
import { onMounted, shallowRef } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'

const route = useRoute()
const authStore = useAuthStore()
const { t } = useI18n()

const loading = shallowRef(true)
const success = shallowRef(false)

onMounted(async () => {
  const userId = Number(route.query.userId)
  const newEmail = route.query.newEmail as string
  const token = route.query.token as string

  if (!userId || !newEmail || !token) {
    loading.value = false
    return
  }

  const result = await authStore.confirmChangeEmail(userId, newEmail, token)
  success.value = result.success
  loading.value = false
})
</script>

<template>
  <AuthCard>
    <template v-if="loading">
      <div class="flex flex-col items-center text-center" aria-live="polite">
        <Loader2 class="mb-4 size-8 animate-spin text-muted-foreground" aria-hidden="true" />
        <p class="text-sm leading-6 text-muted-foreground sm:text-base">
          {{ t('auth.confirmChangeEmail.loading') }}
        </p>
      </div>
    </template>

    <AuthStatus
      v-else-if="success"
      :icon="Check"
      :title="t('auth.confirmChangeEmail.success.title')"
      :message="t('auth.confirmChangeEmail.success.message')"
      :action-label="t('auth.confirmChangeEmail.success.loginLink')"
      action-to="/login"
    />

    <AuthStatus
      v-else
      :icon="X"
      :title="t('auth.confirmChangeEmail.error.title')"
      :message="t('auth.confirmChangeEmail.error.message')"
      :action-label="t('auth.confirmChangeEmail.error.loginLink')"
      action-to="/login"
      action-variant="outline"
      variant="error"
    />
  </AuthCard>
</template>
