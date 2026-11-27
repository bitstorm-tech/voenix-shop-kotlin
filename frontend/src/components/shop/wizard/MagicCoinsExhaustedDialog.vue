<script setup lang="ts">
import { computed, shallowRef, watch } from 'vue'
import { CheckCircle2, Coins, Loader2, LogIn, Sparkles, UserPlus } from 'lucide-vue-next'
import { useI18n } from 'vue-i18n'
import { RouterLink, useRoute } from 'vue-router'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { ApiError, fetchJson } from '@/lib/api'
import {
  MAGIC_COIN_REQUEST_ALREADY_PENDING_CODE,
  MAGIC_COINS_AVAILABLE_CODE,
  MAGIC_COINS_REQUEST_BLOCKED_CODE,
  type CustomerMagicCoinRequestStatus,
} from '@/lib/magicCoins'
import { useAuthStore } from '@/stores/shared/auth'

const props = defineProps<{
  open: boolean
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
  'coins-available': []
}>()

const { t } = useI18n()
const authStore = useAuthStore()
const route = useRoute()

type DialogState = CustomerMagicCoinRequestStatus | 'COINS_AVAILABLE'

const requestState = shallowRef<DialogState | null>(null)
const isLoadingStatus = shallowRef(false)
const isSubmitting = shallowRef(false)
const errorMessage = shallowRef<string | null>(null)

const redirectTarget = computed(() => route.fullPath || '/wizard')
const loginRoute = computed(() => ({ path: '/login', query: { redirect: redirectTarget.value } }))
const registerRoute = computed(() => ({
  path: '/register',
  query: { redirect: redirectTarget.value },
}))

async function loadStatus() {
  if (!authStore.isAuthenticated) {
    requestState.value = null
    errorMessage.value = null
    return
  }

  isLoadingStatus.value = true
  errorMessage.value = null
  try {
    const response = await fetchJson<{ status: CustomerMagicCoinRequestStatus }>(
      '/api/magic-coins/request-status',
      { cache: 'no-store' },
    )
    requestState.value = response.status
  } catch {
    requestState.value = 'CAN_REQUEST'
    errorMessage.value = t('magicCoins.dialog.loadError')
  } finally {
    isLoadingStatus.value = false
  }
}

async function submitRequest() {
  isSubmitting.value = true
  errorMessage.value = null
  try {
    await fetchJson('/api/magic-coins/requests', { method: 'POST' })
    requestState.value = 'PENDING'
  } catch (err) {
    if (err instanceof ApiError) {
      if (err.code === MAGIC_COIN_REQUEST_ALREADY_PENDING_CODE) {
        requestState.value = 'PENDING'
        return
      }
      if (err.code === MAGIC_COINS_REQUEST_BLOCKED_CODE) {
        requestState.value = 'BLOCKED'
        return
      }
      if (err.code === MAGIC_COINS_AVAILABLE_CODE) {
        requestState.value = 'COINS_AVAILABLE'
        emit('coins-available')
        return
      }
      if (err.status === 429) {
        errorMessage.value = t('magicCoins.dialog.rateLimited')
        return
      }
    }
    errorMessage.value = t('magicCoins.dialog.submitError')
  } finally {
    isSubmitting.value = false
  }
}

function close() {
  emit('update:open', false)
}

watch(
  () => [props.open, authStore.isAuthenticated] as const,
  ([open]) => {
    if (open) {
      void loadStatus()
    } else {
      errorMessage.value = null
    }
  },
  { immediate: true },
)
</script>

<template>
  <Dialog :open="open" @update:open="emit('update:open', $event)">
    <DialogContent class="sm:max-w-md" data-testid="magic-coins-dialog">
      <DialogHeader class="text-left">
        <div
          class="mb-3 flex size-11 items-center justify-center rounded-full bg-primary/10 text-primary"
        >
          <CheckCircle2 v-if="requestState === 'PENDING'" class="size-5" aria-hidden="true" />
          <Sparkles
            v-else-if="requestState === 'COINS_AVAILABLE'"
            class="size-5"
            aria-hidden="true"
          />
          <Coins v-else class="size-5" aria-hidden="true" />
        </div>
        <DialogTitle>
          {{
            requestState === 'PENDING'
              ? t('magicCoins.dialog.pendingTitle')
              : requestState === 'COINS_AVAILABLE'
                ? t('magicCoins.dialog.availableTitle')
                : t('magicCoins.dialog.title')
          }}
        </DialogTitle>
        <DialogDescription>
          <template v-if="!authStore.isAuthenticated">
            {{ t('magicCoins.dialog.guestDescription') }}
          </template>
          <template v-else-if="isLoadingStatus">
            {{ t('magicCoins.dialog.loading') }}
          </template>
          <template v-else-if="requestState === 'PENDING'">
            {{ t('magicCoins.dialog.pendingDescription') }}
          </template>
          <template v-else-if="requestState === 'BLOCKED'">
            {{ t('magicCoins.dialog.blockedDescription') }}
          </template>
          <template v-else-if="requestState === 'COINS_AVAILABLE'">
            {{ t('magicCoins.dialog.availableDescription') }}
          </template>
          <template v-else>
            {{ t('magicCoins.dialog.requestDescription') }}
          </template>
        </DialogDescription>
      </DialogHeader>

      <div v-if="isLoadingStatus" class="flex justify-center py-4">
        <Loader2 class="size-6 animate-spin text-primary" aria-hidden="true" />
      </div>

      <Alert v-if="errorMessage" variant="destructive" data-testid="magic-coins-dialog-error">
        {{ errorMessage }}
      </Alert>

      <DialogFooter class="flex-col gap-2 sm:flex-row sm:justify-end">
        <template v-if="!authStore.isAuthenticated">
          <Button as-child variant="outline">
            <RouterLink :to="registerRoute" @click="close">
              <UserPlus class="size-4" />
              {{ t('magicCoins.dialog.registerButton') }}
            </RouterLink>
          </Button>
          <Button as-child>
            <RouterLink :to="loginRoute" @click="close">
              <LogIn class="size-4" />
              {{ t('magicCoins.dialog.loginButton') }}
            </RouterLink>
          </Button>
        </template>

        <template v-else-if="isLoadingStatus">
          <Button type="button" variant="outline" @click="close">
            {{ t('magicCoins.dialog.closeButton') }}
          </Button>
        </template>

        <template v-else-if="requestState === 'CAN_REQUEST'">
          <Button type="button" variant="outline" :disabled="isSubmitting" @click="close">
            {{ t('magicCoins.dialog.closeButton') }}
          </Button>
          <Button
            type="button"
            :disabled="isSubmitting"
            data-testid="request-magic-coins-button"
            @click="submitRequest"
          >
            <Loader2 v-if="isSubmitting" class="size-4 animate-spin" />
            <Coins v-else class="size-4" />
            {{ t('magicCoins.dialog.requestButton') }}
          </Button>
        </template>

        <template v-else>
          <Button type="button" @click="close">
            {{ t('magicCoins.dialog.closeButton') }}
          </Button>
        </template>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
