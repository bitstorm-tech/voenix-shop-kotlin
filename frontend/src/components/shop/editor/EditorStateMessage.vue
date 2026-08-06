<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { AlertTriangle, ArrowLeft, Loader2 } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'

const props = defineProps<{
  state: 'guard' | 'missing' | 'invalid' | 'loading'
}>()

const { t } = useI18n()

const isLoading = computed(() => props.state === 'loading')
const titleKey = computed(() => `editor.states.${props.state}.title`)
const bodyKey = computed(() => `editor.states.${props.state}.body`)
</script>

<template>
  <section
    class="flex min-h-[min(56vh,32rem)] flex-col items-center justify-center gap-5 px-4 py-8 text-center"
    :data-testid="`editor-state-${state}`"
  >
    <div
      class="grid size-14 place-items-center rounded-full bg-[oklch(0.96_0.02_45)] text-[oklch(0.55_0.17_35)]"
      aria-hidden="true"
    >
      <Loader2 v-if="isLoading" class="size-6 animate-spin" />
      <AlertTriangle v-else class="size-6" />
    </div>

    <div class="max-w-[34rem]">
      <h1 class="m-0 text-[clamp(1.5rem,3vw,2.25rem)] font-[750] leading-[1.1] text-foreground">
        {{ t(titleKey) }}
      </h1>
      <p class="m-0 mt-3 text-[0.98rem] leading-[1.6] text-muted-foreground">
        {{ t(bodyKey) }}
      </p>
    </div>

    <Button v-if="!isLoading" as-child>
      <RouterLink :to="{ name: 'mugs' }">
        <ArrowLeft class="size-4" />
        {{ t('editor.states.chooseProduct') }}
      </RouterLink>
    </Button>
  </section>
</template>
