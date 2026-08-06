<script setup lang="ts">
import AuthCard from '@/components/auth/AuthCard.vue'
import AuthStatus from '@/components/auth/AuthStatus.vue'
import { INVALID_LINK_CODE, useAuthStore } from '@/stores/shared/auth'
import { Check, Loader2, X } from 'lucide-vue-next'
import { computed, onMounted, shallowRef } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'

const route = useRoute()
const authStore = useAuthStore()
const { t } = useI18n()

const loading = shallowRef(true)
const success = shallowRef(false)
/**
 * `true` once the backend named the link itself as the cause — an incomplete URL counts as well,
 * because there is nothing to confirm with. Any other failure keeps the generic copy instead of
 * telling the visitor their link is broken when it may not be.
 */
const invalidLink = shallowRef(false)

const errorMessage = computed(() =>
  invalidLink.value
    ? t('auth.confirmEmail.error.invalidLink')
    : t('auth.confirmEmail.error.message'),
)

onMounted(async () => {
  const userId = Number(route.query.userId)
  const token = route.query.token as string

  if (!userId || !token) {
    invalidLink.value = true
    loading.value = false
    return
  }

  const result = await authStore.confirmEmail(userId, token)
  success.value = result.success
  invalidLink.value = !result.success && result.error.code === INVALID_LINK_CODE
  loading.value = false
})
</script>

<template>
  <AuthCard>
    <template v-if="loading">
      <div class="flex flex-col items-center text-center" aria-live="polite">
        <Loader2 class="mb-4 size-8 animate-spin text-muted-foreground" aria-hidden="true" />
        <p class="text-sm leading-6 text-muted-foreground sm:text-base">
          {{ t('auth.confirmEmail.loading') }}
        </p>
      </div>
    </template>

    <AuthStatus
      v-else-if="success"
      :icon="Check"
      :title="t('auth.confirmEmail.success.title')"
      :message="t('auth.confirmEmail.success.message')"
      :action-label="t('auth.confirmEmail.success.loginLink')"
      action-to="/login"
    />

    <AuthStatus
      v-else
      :icon="X"
      :title="t('auth.confirmEmail.error.title')"
      :message="errorMessage"
      :action-label="t('auth.confirmEmail.error.loginLink')"
      action-to="/login"
      action-variant="outline"
      variant="error"
    />
  </AuthCard>
</template>
