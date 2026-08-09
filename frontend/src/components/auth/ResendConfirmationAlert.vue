<script setup lang="ts">
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { useToast } from '@/composables/useToast'
import { useAuthStore } from '@/stores/shared/auth'
import { shallowRef } from 'vue'
import { useI18n } from 'vue-i18n'

/**
 * The retry path for both flows in which a confirmation mail is missing: a login refused with
 * `403` because the address was never confirmed, and a registration whose mail could not be
 * delivered (`502`). `POST /api/auth/resend-confirmation` is enumeration-safe and always answers
 * `204`, so the success state says nothing about whether the address exists.
 */
const props = defineProps<{
  email: string
  message: string
}>()

const authStore = useAuthStore()
const { t } = useI18n()
const { toast } = useToast()

const loading = shallowRef(false)
const sent = shallowRef(false)

const handleResend = async () => {
  loading.value = true
  const result = await authStore.resendConfirmation(props.email)
  loading.value = false

  if (result.success) {
    sent.value = true
    toast({ title: t('auth.resendConfirmation.success'), variant: 'success' })
    return
  }

  toast({
    title: result.error.message || t('auth.resendConfirmation.error'),
    variant: 'destructive',
  })
}
</script>

<template>
  <Alert v-if="sent" variant="info" class="border-success/20 bg-success-surface text-success">
    <p class="m-0 leading-5">
      {{ t('auth.resendConfirmation.success') }}
    </p>
  </Alert>

  <Alert v-else variant="destructive" class="space-y-3">
    <p class="m-0 leading-5">
      {{ props.message }}
    </p>
    <Button
      type="button"
      variant="outline"
      size="sm"
      class="w-full sm:w-auto"
      :disabled="loading"
      @click="handleResend"
    >
      {{ t('auth.resendConfirmation.action') }}
    </Button>
  </Alert>
</template>
