<script setup lang="ts">
import { Button } from '@/components/ui/button'
import { usePwaUpdateStore } from '@/stores/shared/pwaUpdate'
import { RefreshCw } from 'lucide-vue-next'
import { useI18n } from 'vue-i18n'

const pwaStore = usePwaUpdateStore()
const { t } = useI18n()
</script>

<template>
  <Transition
    enter-active-class="transition duration-300 ease-out"
    enter-from-class="translate-y-full opacity-0"
    enter-to-class="translate-y-0 opacity-100"
    leave-active-class="transition duration-200 ease-in"
    leave-from-class="translate-y-0 opacity-100"
    leave-to-class="translate-y-full opacity-0"
  >
    <div
      v-if="pwaStore.needsRefresh"
      class="fixed bottom-4 left-1/2 z-50 w-[calc(100%-2rem)] max-w-lg -translate-x-1/2"
    >
      <div class="rounded-2xl border border-border bg-background p-4 shadow-lg">
        <div class="flex items-start gap-3">
          <div
            class="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-primary/10 text-primary"
          >
            <RefreshCw class="h-5 w-5" />
          </div>
          <div class="min-w-0">
            <h3 class="text-sm font-semibold leading-tight text-foreground">
              {{ t('pwaUpdate.title') }}
            </h3>
            <p class="mt-0.5 text-sm text-muted-foreground">
              {{ t('pwaUpdate.description') }}
            </p>
          </div>
        </div>

        <div class="mt-3 flex justify-end gap-2">
          <Button type="button" variant="outline" size="sm" @click="pwaStore.dismissUpdate()">
            {{ t('pwaUpdate.dismiss') }}
          </Button>
          <Button type="button" variant="default" size="sm" @click="pwaStore.applyUpdate()">
            {{ t('pwaUpdate.updateButton') }}
          </Button>
        </div>
      </div>
    </div>
  </Transition>
</template>
