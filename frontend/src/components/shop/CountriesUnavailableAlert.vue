<script setup lang="ts">
import { AlertCircle, Loader2, RefreshCw } from 'lucide-vue-next'
import { useI18n } from 'vue-i18n'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'

/**
 * The explicit, retryable message for a missing shippable-country list. Without the list there is
 * no shipping country to pick, so the surrounding form blocks its submit instead of falling back
 * to free text.
 */
defineProps<{
  message: string
  isRetrying?: boolean
}>()

const emit = defineEmits<{
  retry: []
}>()

const { t } = useI18n()
</script>

<template>
  <Alert
    variant="destructive"
    data-testid="countries-unavailable"
    class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between"
  >
    <div class="flex gap-2">
      <AlertCircle class="mt-0.5 size-4 shrink-0" />
      <p class="font-medium">{{ message }}</p>
    </div>
    <Button
      type="button"
      variant="outline"
      size="sm"
      :disabled="isRetrying"
      class="self-start border-destructive/40 text-destructive hover:text-destructive sm:self-auto"
      @click="emit('retry')"
    >
      <Loader2 v-if="isRetrying" class="size-3.5 animate-spin" />
      <RefreshCw v-else class="size-3.5" />
      {{ t('checkout.address.retryCountries') }}
    </Button>
  </Alert>
</template>
