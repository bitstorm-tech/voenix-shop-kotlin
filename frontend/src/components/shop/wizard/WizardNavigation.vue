<script setup lang="ts">
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { ArrowLeft, ArrowRight, Loader2 } from 'lucide-vue-next'
import { useI18n } from 'vue-i18n'
import type { Step } from './types'

interface Props {
  currentStep: number
  totalSteps: number
  steps: Step[]
  canProceed?: boolean
  isSubmitting?: boolean
  isLastStep?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  canProceed: true,
  isSubmitting: false,
  isLastStep: false,
})
const emit = defineEmits<{
  back: []
  next: []
}>()

const { t } = useI18n()
</script>

<template>
  <div>
    <!-- Mobile: Segmented progress bar + compact buttons -->
    <div class="md:hidden">
      <!-- Segmented progress bar -->
      <div class="flex gap-1.5">
        <div
          v-for="step in totalSteps"
          :key="step"
          :class="
            cn(
              'h-1 flex-1 rounded-full',
              step <= currentStep ? 'bg-primary' : 'bg-muted-foreground/30',
            )
          "
        />
      </div>

      <!-- Step label with nav buttons -->
      <div class="mt-2 flex items-center justify-between">
        <Button v-if="currentStep > 1" variant="ghost" size="icon-sm" @click="emit('back')">
          <ArrowLeft class="h-4 w-4" />
        </Button>
        <div v-else class="size-8" />

        <p class="text-sm text-muted-foreground">
          {{ t('mugConfigurator.nav.stepOf', { current: currentStep, total: totalSteps }) }}
          — {{ steps[currentStep - 1]?.label }}
        </p>

        <Button
          variant="ghost"
          size="icon-sm"
          :disabled="!props.canProceed || props.isSubmitting"
          @click="emit('next')"
        >
          <Loader2 v-if="props.isSubmitting" class="h-4 w-4 animate-spin" />
          <ArrowRight v-else class="h-4 w-4" />
        </Button>
      </div>
    </div>

    <!-- Desktop: Original buttons -->
    <div class="hidden md:flex md:items-center md:justify-between">
      <Button v-if="currentStep > 1" variant="outline" @click="emit('back')">
        <ArrowLeft class="h-4 w-4" />
        {{ t('mugConfigurator.nav.back') }}
      </Button>
      <div v-else />

      <Button :disabled="!props.canProceed || props.isSubmitting" @click="emit('next')">
        <Loader2 v-if="props.isSubmitting" class="h-4 w-4 animate-spin" />
        <template v-else>
          {{ props.isLastStep ? t('mugConfigurator.nav.finish') : t('mugConfigurator.nav.next') }}
          <ArrowRight v-if="!props.isLastStep" class="h-4 w-4" />
        </template>
      </Button>
    </div>
  </div>
</template>
