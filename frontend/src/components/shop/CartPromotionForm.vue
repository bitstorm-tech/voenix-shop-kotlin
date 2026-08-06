<script setup lang="ts">
import { computed, shallowRef } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { CheckCircle2, Loader2, Tag, X } from 'lucide-vue-next'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import type { AppliedPromotion, PromotionApplicationErrorCode } from '@/stores/shop/cart'

interface Props {
  appliedPromotion: AppliedPromotion | null
  isLoading: boolean
  errorCode: PromotionApplicationErrorCode | null
}

interface Emits {
  apply: [promotionCode: string]
  remove: []
}

const props = defineProps<Props>()
const emit = defineEmits<Emits>()
const { t } = useI18n()
const promotionCode = shallowRef('')
const isLoginRequired = computed(() => props.errorCode === 'PROMOTION_LOGIN_REQUIRED')
const errorKeys: Partial<Record<PromotionApplicationErrorCode, string>> = {
  PROMOTION_INVALID_CODE: 'cart.promotion.errors.invalidCode',
  PROMOTION_INACTIVE: 'cart.promotion.errors.inactive',
  PROMOTION_NOT_STARTED: 'cart.promotion.errors.notStarted',
  PROMOTION_EXPIRED: 'cart.promotion.errors.expired',
  PROMOTION_TOTAL_EXHAUSTED: 'cart.promotion.errors.totalExhausted',
  PROMOTION_PER_USER_EXHAUSTED: 'cart.promotion.errors.perUserExhausted',
  PROMOTION_REMOVE_FAILED: 'cart.promotion.errors.removeFailed',
}
const errorMessageKey = computed(() => {
  return props.errorCode
    ? (errorKeys[props.errorCode] ?? 'cart.promotion.errors.applicationFailed')
    : null
})

function submitPromotion() {
  const normalizedCode = promotionCode.value.trim()
  if (!normalizedCode || props.isLoading) {
    return
  }

  emit('apply', normalizedCode)
}
</script>

<template>
  <section class="space-y-3" data-testid="cart-promotion-form">
    <div class="flex items-center gap-2">
      <Tag class="size-4 text-muted-foreground" />
      <h3 class="font-heading text-sm font-semibold">{{ t('cart.promotion.title') }}</h3>
    </div>

    <div
      v-if="appliedPromotion"
      class="flex items-center justify-between gap-3 rounded-lg border border-success/30 bg-success/5 p-3"
    >
      <div class="flex min-w-0 items-start gap-2">
        <CheckCircle2 class="mt-0.5 size-4 shrink-0 text-success" />
        <div class="min-w-0">
          <p class="truncate text-sm font-medium">{{ appliedPromotion.name }}</p>
          <p class="text-xs text-muted-foreground">
            {{ t('cart.promotion.applied') }}:
            <span class="font-mono font-medium">{{ appliedPromotion.promotionCode }}</span>
          </p>
        </div>
      </div>
      <Button
        type="button"
        variant="ghost"
        size="icon-sm"
        :disabled="isLoading"
        :aria-label="t('cart.promotion.remove')"
        data-testid="cart-promotion-remove"
        @click="emit('remove')"
      >
        <X class="size-4" />
      </Button>
    </div>

    <form class="space-y-2" @submit.prevent="submitPromotion">
      <Label for="cart-promotion-code">{{ t('cart.promotion.codeLabel') }}</Label>
      <div class="flex gap-2">
        <Input
          id="cart-promotion-code"
          v-model="promotionCode"
          :placeholder="t('cart.promotion.codePlaceholder')"
          :disabled="isLoading"
          autocomplete="off"
        />
        <Button type="submit" variant="outline" :disabled="isLoading || !promotionCode.trim()">
          <Loader2 v-if="isLoading" class="size-4 animate-spin" />
          <span v-else>{{ t('cart.promotion.apply') }}</span>
        </Button>
      </div>
    </form>

    <Alert v-if="errorCode" :variant="isLoginRequired ? 'info' : 'destructive'">
      <template v-if="isLoginRequired">
        <p>{{ t('cart.promotion.errors.loginRequired') }}</p>
        <div class="mt-2 flex flex-wrap gap-2">
          <Button as-child type="button" size="sm">
            <RouterLink :to="{ name: 'login' }">{{ t('cart.promotion.login') }}</RouterLink>
          </Button>
          <Button as-child type="button" size="sm" variant="outline">
            <RouterLink :to="{ name: 'register' }">
              {{ t('cart.promotion.register') }}
            </RouterLink>
          </Button>
        </div>
      </template>
      <p v-else-if="errorMessageKey">{{ t(errorMessageKey) }}</p>
    </Alert>
  </section>
</template>
